//! Periodic SQLite snapshots (PRD §6: "SQLite WAL 模式 + 定期备份 homehub.db").
//!
//! A snapshot is produced with `VACUUM INTO`, which SQLite runs inside a read
//! transaction: the copy is transactionally consistent even while the server
//! keeps writing, and it comes out defragmented (no `-wal` / `-shm` sidecars to
//! worry about). Snapshots older than `keep` are pruned after every run.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use serde::Serialize;

use crate::config::Config;
use crate::db::Db;

/// One snapshot on disk.
#[derive(Debug, Clone, Serialize)]
pub struct BackupInfo {
    pub name: String,
    pub bytes: u64,
    pub created_at: i64,
}

/// Everything the admin UI needs to render the backup card.
#[derive(Debug, Clone, Serialize)]
pub struct BackupStatus {
    pub enabled: bool,
    pub interval_hours: u32,
    pub keep: u32,
    pub dir: String,
    pub items: Vec<BackupInfo>,
    pub total_bytes: u64,
    pub last_backup_at: Option<i64>,
    pub last_error: Option<String>,
}

const PREFIX: &str = "homehub-";
const SUFFIX: &str = ".db";

/// Copy the database to `<backup-dir>/homehub-<yyyyMMdd-HHmmss>.db`.
///
/// Returns the created path. The temporary `.tmp` target is renamed into place
/// so a crash mid-copy never leaves a truncated file that looks like a backup.
pub async fn run(db: &Db, config: &Config) -> Result<PathBuf> {
    let dir = config.backup_dir();
    tokio::fs::create_dir_all(&dir)
        .await
        .with_context(|| format!("create backup dir {}", dir.display()))?;

    let name = format!("{}{}{}", PREFIX, timestamp(), SUFFIX);
    let target = dir.join(&name);
    let tmp = dir.join(format!("{}.tmp", name));

    // VACUUM INTO refuses to overwrite an existing file.
    if tmp.exists() {
        tokio::fs::remove_file(&tmp).await.ok();
    }

    // The target must be an absolute path: SQLite resolves it relative to the
    // process CWD otherwise.
    let absolute = std::fs::canonicalize(&dir)
        .unwrap_or_else(|_| dir.clone())
        .join(&tmp.file_name().unwrap_or_default());

    sqlx::query("VACUUM INTO ?")
        .bind(absolute.to_string_lossy().to_string())
        .execute(db)
        .await
        .context("VACUUM INTO failed")?;

    tokio::fs::rename(&tmp, &target)
        .await
        .with_context(|| format!("rename snapshot to {}", target.display()))?;

    let _ = crate::db::set_setting(db, "last_backup_at", &crate::db::now().to_string()).await;
    let _ = crate::db::set_setting(db, "last_backup_error", "").await;

    prune(&dir, config.runtime.backup.keep).await?;
    tracing::info!("sqlite backup -> {}", target.display());
    Ok(target)
}

/// Delete the oldest snapshots so at most `keep` remain.
pub async fn prune(dir: &Path, keep: u32) -> Result<u64> {
    let mut items = list(dir)?;
    if items.len() <= keep as usize {
        return Ok(0);
    }
    items.sort_by(|a, b| a.created_at.cmp(&b.created_at));
    let excess = items.len() - keep as usize;
    let mut removed = 0u64;
    for item in items.into_iter().take(excess) {
        if tokio::fs::remove_file(dir.join(&item.name)).await.is_ok() {
            removed += 1;
        }
    }
    Ok(removed)
}

/// Snapshots currently on disk, newest first.
pub fn list(dir: &Path) -> Result<Vec<BackupInfo>> {
    let mut out = Vec::new();
    if !dir.exists() {
        return Ok(out);
    }
    for entry in std::fs::read_dir(dir)?.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if !name.starts_with(PREFIX) || !name.ends_with(SUFFIX) {
            continue;
        }
        let meta = entry.metadata().ok();
        let created_at = meta
            .as_ref()
            .and_then(|m| m.modified().ok())
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_secs() as i64)
            .or_else(|| parse_timestamp(&name))
            .unwrap_or(0);
        out.push(BackupInfo {
            name,
            bytes: meta.map(|m| m.len()).unwrap_or(0),
            created_at,
        });
    }
    out.sort_by(|a, b| b.created_at.cmp(&a.created_at));
    Ok(out)
}

/// Snapshot list plus the runtime configuration, for the admin UI.
pub async fn status(db: &Db, config: &Config) -> Result<BackupStatus> {
    let dir = config.backup_dir();
    let items = list(&dir)?;
    let total_bytes = items.iter().map(|i| i.bytes).sum();
    let last_backup_at = crate::db::get_setting(db, "last_backup_at")
        .await?
        .and_then(|v| v.parse::<i64>().ok());
    let last_error = crate::db::get_setting(db, "last_backup_error")
        .await?
        .filter(|v| !v.is_empty());
    Ok(BackupStatus {
        enabled: config.runtime.backup.enabled,
        interval_hours: config.runtime.backup.interval_hours,
        keep: config.runtime.backup.keep,
        dir: dir.to_string_lossy().to_string(),
        items,
        total_bytes,
        last_backup_at,
        last_error,
    })
}

fn timestamp() -> String {
    chrono::Local::now().format("%Y%m%d-%H%M%S").to_string()
}

/// Fall back to the timestamp embedded in the file name when mtime is missing.
fn parse_timestamp(name: &str) -> Option<i64> {
    let raw = name.strip_prefix(PREFIX)?.strip_suffix(SUFFIX)?;
    let (date, time) = raw.split_once('-')?;
    // `time` is HHMMSS; split it into colon separated fields.
    let fields: Vec<&str> = (0..3)
        .filter_map(|i| time.get(i * 2..i * 2 + 2))
        .collect();
    if fields.len() != 3 {
        return None;
    }
    let naive = chrono::NaiveDateTime::parse_from_str(
        &format!("{} {}", date, fields.join(":")),
        "%Y%m%d %H:%M:%S",
    )
    .ok()?;
    naive.and_local_timezone(chrono::Local).single().map(|d| d.timestamp())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_backup_timestamp() {
        let ts = parse_timestamp("homehub-20260904-031500.db").expect("parses");
        let expected = chrono::NaiveDate::from_ymd_opt(2026, 9, 4)
            .unwrap()
            .and_hms_opt(3, 15, 0)
            .unwrap()
            .and_local_timezone(chrono::Local)
            .single()
            .unwrap()
            .timestamp();
        assert_eq!(ts, expected);
        assert!(parse_timestamp("not-a-backup.txt").is_none());
        assert!(parse_timestamp("homehub-20260904.db").is_none());
    }
}
