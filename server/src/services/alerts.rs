//! Health alerting: thresholds -> ServerChan (sct.ftqq.com) push notifications.

use std::path::Path;

use anyhow::Result;

use crate::config::{AlertConfig, Config};
use crate::db::Db;
use crate::models::alert::AlertLevel;
use crate::models::trash::Alert;
use crate::services::dirs::DirRegistry;

/// Raise an alert (deduped by kind while an identical unresolved alert exists).
pub async fn raise(
    db: &Db,
    config: &Config,
    level: AlertLevel,
    kind: &str,
    message: &str,
) -> Result<bool> {
    if !config.runtime.alerts.enabled {
        return Ok(false);
    }

    let existing: Option<(i64,)> = sqlx::query_as(
        "SELECT id FROM alerts WHERE kind = ? AND resolved_at IS NULL AND message = ? LIMIT 1",
    )
    .bind(kind)
    .bind(message)
    .fetch_optional(db)
    .await?;
    if existing.is_some() {
        return Ok(false);
    }

    let now = crate::db::now();
    sqlx::query("INSERT INTO alerts (level, kind, message, created_at, notified) VALUES (?, ?, ?, ?, 0)")
        .bind(level.as_str())
        .bind(kind)
        .bind(message)
        .bind(now)
        .execute(db)
        .await?;

    let notified = notify(db, config, level, kind, message).await;
    sqlx::query("UPDATE alerts SET notified = ? WHERE kind = ? AND message = ? AND resolved_at IS NULL")
        .bind(if notified { 1 } else { 0 })
        .bind(kind)
        .bind(message)
        .execute(db)
        .await?;
    Ok(true)
}

/// Mark every alert of a kind as resolved (called when the condition clears).
pub async fn resolve(db: &Db, kind: &str) -> Result<u64> {
    let res = sqlx::query("UPDATE alerts SET resolved_at = ? WHERE kind = ? AND resolved_at IS NULL")
        .bind(crate::db::now())
        .bind(kind)
        .execute(db)
        .await?;
    Ok(res.rows_affected())
}

pub async fn list(db: &Db, limit: i64) -> Result<Vec<Alert>> {
    Ok(sqlx::query_as::<_, Alert>(
        "SELECT id, level, kind, message, created_at, resolved_at, notified \
         FROM alerts ORDER BY created_at DESC LIMIT ?",
    )
    .bind(limit)
    .fetch_all(db)
    .await?)
}

/// Evaluate every configured health condition.
pub async fn check(db: &Db, config: &Config, registry: &DirRegistry) -> Result<()> {
    let alerts = &config.runtime.alerts;
    if !alerts.enabled {
        return Ok(());
    }

    // 1. Disk usage of the data dir and of every registered directory.
    let mut roots: Vec<String> = vec![config.global.data_dir.clone()];
    for dir in registry.enabled() {
        roots.push(dir.path.clone());
    }
    for root in roots {
        match disk_usage_percent(Path::new(&root)) {
            Some(pct) if pct >= alerts.disk_usage_percent => {
                raise(
                    db,
                    config,
                    AlertLevel::Warn,
                    "disk",
                    &format!("磁盘 {} 使用率 {:.1}% 超过阈值 {:.1}%", root, pct, alerts.disk_usage_percent),
                )
                .await?;
            }
            _ => {
                resolve(db, "disk").await?;
            }
        }
    }

    // 2. Consecutive task failures.
    let failed: (i64,) =
        sqlx::query_as("SELECT COUNT(*) FROM tasks WHERE status = 'failed' AND attempts >= 1")
            .fetch_one(db)
            .await
            .unwrap_or((0,));
    if failed.0 as u32 >= alerts.task_failure_threshold {
        raise(
            db,
            config,
            AlertLevel::Error,
            "task",
            &format!("任务队列累计失败 {} 条，超过阈值 {}", failed.0, alerts.task_failure_threshold),
        )
        .await?;
    } else {
        resolve(db, "task").await?;
    }

    // 3. Originals / trash oversize.
    let (_, originals_bytes) = crate::services::originals::usage(config).await?;
    let originals_cap = (config.runtime.originals.max_usage_percent / 100.0)
        * total_capacity(Path::new(&config.global.data_dir)).max(1) as f64;
    if config.runtime.originals.enabled && originals_cap > 0.0 && originals_bytes as f64 > originals_cap {
        raise(
            db,
            config,
            AlertLevel::Warn,
            "originals",
            &format!(".originals 占用 {:.1} MB，超过上限", originals_bytes as f64 / 1_048_576.0),
        )
        .await?;
    } else {
        resolve(db, "originals").await?;
    }

    let (trash_count, trash_bytes) = crate::services::trash::usage(db).await?;
    if trash_count > 0 && trash_bytes > 20 * 1024 * 1024 * 1024 {
        raise(
            db,
            config,
            AlertLevel::Info,
            "trash",
            &format!("回收站占用 {:.1} GB（{} 项），建议清理", trash_bytes as f64 / 1_073_741_824.0, trash_count),
        )
        .await?;
    } else {
        resolve(db, "trash").await?;
    }

    // 4. SQLite write probe.
    if let Err(e) = sqlx::query("INSERT OR REPLACE INTO settings (key, value) VALUES ('_health', ?)")
        .bind(crate::db::now().to_string())
        .execute(db)
        .await
    {
        raise(db, config, AlertLevel::Error, "sqlite", &format!("SQLite 写入异常: {}", e)).await?;
    } else {
        resolve(db, "sqlite").await?;
    }

    Ok(())
}

// ─────────────────────────────────── notify ────────────────────────────────

/// Send the alert through every enabled channel, respecting the cooldown.
async fn notify(db: &Db, config: &Config, level: AlertLevel, kind: &str, message: &str) -> bool {
    let alerts = &config.runtime.alerts;
    let cooldown = crate::db::now() - alerts.cooldown_secs;
    let recent: Option<(i64,)> =
        sqlx::query_as("SELECT id FROM alerts WHERE kind = ? AND notified = 1 AND created_at > ? LIMIT 1")
            .bind(kind)
            .bind(cooldown)
            .fetch_optional(db)
            .await
            .ok()
            .flatten();
    if recent.is_some() {
        tracing::debug!("alert {} suppressed by cooldown", kind);
        return false;
    }

    let title = format!("[HomeHub][{}] {}", level.as_str(), kind);
    let mut sent = false;
    if let Ok(true) = notify_serverchan(alerts, &title, message).await {
        sent = true;
    }
    sent
}

/// ServerChan (sct.ftqq.com): one POST with the SendKey, done.
async fn notify_serverchan(cfg: &AlertConfig, title: &str, message: &str) -> Result<bool> {
    if !cfg.serverchan.enabled || cfg.serverchan.send_key.is_empty() {
        return Ok(false);
    }
    let url = format!(
        "https://sctapi.ftqq.com/{}.send",
        cfg.serverchan.send_key.trim()
    );
    let resp = reqwest::Client::new()
        .post(&url)
        .form(&[("title", title), ("desp", message)])
        .send()
        .await?;
    Ok(resp.status().is_success())
}

// ────────────────────────────────── helpers ────────────────────────────────

/// Used bytes / total bytes of the filesystem holding `path`, in percent.
pub fn disk_usage_percent(path: &Path) -> Option<f64> {
    let (total, used) = fs_usage(path)?;
    if total == 0 {
        return None;
    }
    Some(used as f64 / total as f64 * 100.0)
}

fn total_capacity(path: &Path) -> u64 {
    fs_usage(path).map(|(t, _)| t).unwrap_or(0)
}

#[cfg(unix)]
fn fs_usage(path: &Path) -> Option<(u64, u64)> {
    use std::ffi::CString;
    use std::mem::MaybeUninit;
    use std::os::raw::c_char;

    let target = first_existing(path);
    let c_path = CString::new(target.to_string_lossy().as_bytes()).ok()?;
    unsafe {
        let mut stat: MaybeUninit<libc::statvfs> = MaybeUninit::uninit();
        if libc::statvfs(c_path.as_ptr() as *const c_char, stat.as_mut_ptr()) != 0 {
            return None;
        }
        let stat = stat.assume_init();
        let total = stat.f_blocks as u64 * stat.f_frsize as u64;
        let free = stat.f_bfree as u64 * stat.f_frsize as u64;
        Some((total, total.saturating_sub(free)))
    }
}

#[cfg(not(unix))]
fn fs_usage(_path: &Path) -> Option<(u64, u64)> {
    None
}

fn first_existing(path: &Path) -> std::path::PathBuf {
    let mut current = path.to_path_buf();
    while !current.exists() {
        if !current.pop() {
            break;
        }
    }
    if current.as_os_str().is_empty() {
        std::path::PathBuf::from(".")
    } else {
        current
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disk_usage_is_sane() {
        let pct = disk_usage_percent(Path::new("/")).unwrap();
        assert!(pct >= 0.0 && pct <= 100.0);
    }
}
