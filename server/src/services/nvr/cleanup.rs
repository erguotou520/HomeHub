//! Cleanup loop: retain_days expiry + optional max_gb cap. Order matters:
//! delete the file FIRST, then the DB row — a crash in between leaves an
//! orphan row, which the next round self-heals (file missing → drop row).
//! Frigate uses the same order (record/cleanup.py).

use crate::services::nvr::NvrState;

/// One full cleanup round (expiry → max_gb → empty dirs → WAL truncate).
pub async fn round(state: &NvrState) {
    let cfg = state.config.get().runtime.nvr.clone();
    if cfg.retain_days == 0 && cfg.max_gb == 0 {
        return;
    }
    let now = crate::db::now();
    let mut deleted_files = 0u64;
    let mut deleted_rows = 0u64;

    // ── 1. retain_days expiry ────────────────────────────────────────────
    if cfg.retain_days > 0 {
        let cutoff = now - (cfg.retain_days as i64) * 86400;
        loop {
            let ids: Vec<i64> = sqlx::query_scalar(
                "SELECT id FROM nvr_segments WHERE end_time < ? ORDER BY end_time LIMIT 1000",
            )
            .bind(cutoff)
            .fetch_all(&state.db)
            .await
            .unwrap_or_default();
            if ids.is_empty() {
                break;
            }
            for id in &ids {
                // Resolve the path so we can remove the file first.
                let path: Option<String> =
                    sqlx::query_scalar("SELECT path FROM nvr_segments WHERE id = ?")
                        .bind(id)
                        .fetch_optional(&state.db)
                        .await
                        .ok()
                        .flatten();
                if let Some(rel) = path {
                    let abs = state.segment_abs_path(&rel);
                    match std::fs::remove_file(&abs) {
                        Ok(()) => deleted_files += 1,
                        Err(_) => {} // already gone → orphan row, drop below
                    }
                }
            }
            let ph: Vec<String> = ids.iter().map(|_| "?".into()).collect();
            let sql = format!(
                "DELETE FROM nvr_segments WHERE id IN ({})",
                ph.join(",")
            );
            let mut q = sqlx::query(&sql);
            for id in &ids {
                q = q.bind(id);
            }
            match q.execute(&state.db).await {
                Ok(r) => deleted_rows += r.rows_affected(),
                Err(e) => tracing::error!("nvr cleanup: batch delete failed: {e}"),
            }
            if ids.len() < 1000 {
                break;
            }
        }
    }

    // ── 2. orphan rows (file already missing) ────────────────────────────
    // Bounded sample (newest 5000 rows) so a huge table doesn't stall the
    // loop; the retain_days pass above keeps the tail clean anyway.
    let sample: Vec<(i64, String)> = sqlx::query_as(
        "SELECT id, path FROM nvr_segments ORDER BY start_time DESC LIMIT 5000",
    )
    .fetch_all(&state.db)
    .await
    .unwrap_or_default();
    let mut missing: Vec<i64> = Vec::new();
    for (id, rel) in &sample {
        if !state.segment_abs_path(rel).exists() {
            missing.push(*id);
        }
    }
    if !missing.is_empty() {
        let ph: Vec<String> = missing.iter().map(|_| "?".into()).collect();
        let sql = format!(
            "DELETE FROM nvr_segments WHERE id IN ({})",
            ph.join(",")
        );
        let mut q = sqlx::query(&sql);
        for id in &missing {
            q = q.bind(id);
        }
        if let Ok(r) = q.execute(&state.db).await {
            deleted_rows += r.rows_affected();
        }
    }

    // ── 3. max_gb cap (oldest first) ─────────────────────────────────────
    if cfg.max_gb > 0 {
        let cap_bytes = cfg.max_gb as i64 * 1024 * 1024 * 1024;
        let (total, _count): (i64, i64) =
            sqlx::query_as("SELECT COALESCE(SUM(size_bytes),0), COUNT(*) FROM nvr_segments")
                .fetch_one(&state.db)
                .await
                .unwrap_or((0, 0));
        if total > cap_bytes {
            let mut to_free = total - cap_bytes;
            let mut last_end: i64 = 0;
            let mut guard = 0;
            while to_free > 0 && guard < 200 {
                guard += 1;
                let rows: Vec<(i64, String, i64, i64)> = sqlx::query_as(
                    "SELECT id, path, size_bytes, end_time FROM nvr_segments \
                     WHERE end_time < ? ORDER BY end_time ASC LIMIT 500",
                )
                .bind(if last_end == 0 { i64::MAX } else { last_end })
                .fetch_all(&state.db)
                .await
                .unwrap_or_default();
                if rows.is_empty() {
                    break;
                }
                let mut batch: Vec<i64> = Vec::new();
                for (id, rel, size, end_time) in &rows {
                    if to_free <= 0 {
                        break;
                    }
                    let abs = state.segment_abs_path(rel);
                    match std::fs::remove_file(&abs) {
                        Ok(()) => {
                            deleted_files += 1;
                            to_free -= size;
                        }
                        Err(_) => {}
                    }
                    batch.push(*id);
                }
                if !batch.is_empty() {
                    let ph: Vec<String> = batch.iter().map(|_| "?".into()).collect();
                    let sql = format!(
                        "DELETE FROM nvr_segments WHERE id IN ({})",
                        ph.join(",")
                    );
                    let mut q = sqlx::query(&sql);
                    for id in &batch {
                        q = q.bind(id);
                    }
                    if let Ok(r) = q.execute(&state.db).await {
                        deleted_rows += r.rows_affected();
                    }
                }
                last_end = rows.iter().map(|r| r.3).max().unwrap_or(last_end);
            }
            tracing::info!(
                "nvr cleanup: max_gb trim freed ~{} bytes of {} (cap {})",
                total - to_free.max(0),
                total,
                cap_bytes
            );
        }
    }

    // ── 4. prune stale cache + empty dirs ────────────────────────────────
    super::maintainer::prune_stale_cache(state).await;
    remove_empty_record_dirs(&state.record_root);
    // `.cache` is recreated at startup only, so a cleanup pass that treated it
    // as an ordinary empty dir left ffmpeg without an output dir: it exited
    // instantly and the manager respawned it every 30s forever. Re-assert it.
    if let Err(e) = std::fs::create_dir_all(&state.cache_dir) {
        tracing::warn!("nvr cleanup: recreate cache dir {:?} failed: {e}", state.cache_dir);
    }

    // ── 5. WAL checkpoint (Frigate does the same after cleanup) ─────────
    let _ = sqlx::query("PRAGMA wal_checkpoint(TRUNCATE)")
        .execute(&state.db)
        .await;

    if deleted_files > 0 || deleted_rows > 0 {
        tracing::info!(
            "nvr cleanup: removed {deleted_files} files, {deleted_rows} rows"
        );
    }
}

/// One-shot startup sweep: cache leftovers older than 1h + orphan segment
/// files older than 1 day that have no DB row (protects in-flight segments
/// from a crash during the rename+insert window).
pub async fn startup_sweep(state: &NvrState) -> anyhow::Result<()> {
    super::maintainer::prune_stale_cache(state).await;

    let now = crate::db::now();
    let mut pruned = 0u64;
    let mut stack = vec![state.record_root.clone()];
    while let Some(dir) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                stack.push(path);
                continue;
            }
            if path.extension().and_then(|e| e.to_str()) != Some("mp4") {
                continue;
            }
            let age = entry
                .metadata()
                .ok()
                .and_then(|m| m.modified().ok())
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| now - d.as_secs() as i64)
                .unwrap_or(0);
            if age < 86400 {
                continue; // recent — may be in-flight
            }
            let rel = path
                .strip_prefix(std::path::Path::new(
                    &state.config.get().global.data_dir,
                ))
                .ok()
                .map(|p| p.to_string_lossy().to_string());
            let rel = match rel {
                Some(r) if r.starts_with("recordings/") => r,
                _ => {
                    // Record root is outside data_dir; reconstruct the
                    // `recordings/...` rel path from the root itself.
                    match path.strip_prefix(&state.record_root) {
                        Ok(rest) => format!("recordings/{}", rest.to_string_lossy()),
                        Err(_) => continue,
                    }
                }
            };
            let in_db: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM nvr_segments WHERE path = ?")
                .bind(&rel)
                .fetch_one(&state.db)
                .await?;
            if in_db.0 == 0 {
                if std::fs::remove_file(&path).is_ok() {
                    pruned += 1;
                }
            }
        }
    }
    if pruned > 0 {
        tracing::info!("nvr startup sweep: pruned {pruned} orphan segment files");
    }
    Ok(())
}

/// Infra dirs under `record_root` that are legitimately empty between writes
/// and must never be pruned as "empty".
fn is_infra_dir(path: &std::path::Path) -> bool {
    matches!(
        path.file_name().and_then(|n| n.to_str()),
        Some(".cache") | Some("live")
    )
}

/// Bottom-up removal of empty subdirs under `root` (leaf-up; never removes
/// `root` itself). Infrastructure dirs that merely look empty (`.cache`,
/// `live`) are always preserved — deleting them breaks live recording.
fn remove_empty_record_dirs(root: &std::path::Path) {
    fn prune(dir: &std::path::Path, root: &std::path::Path) {
        let Ok(entries) = std::fs::read_dir(dir) else {
            return;
        };
        for entry in entries.flatten() {
            let p = entry.path();
            if p.is_dir() && p != root && !is_infra_dir(&p) {
                prune(&p, root);
                let still_empty = std::fs::read_dir(&p)
                    .map(|mut it| it.next().is_none())
                    .unwrap_or(false);
                if still_empty {
                    let _ = std::fs::remove_dir(&p);
                }
            }
        }
    }
    prune(root, root);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_dir_pruning() {
        let tmp = std::env::temp_dir().join(format!(
            "hh-nvr-test-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let deep = tmp.join("2026-01-01/08/home");
        std::fs::create_dir_all(&deep).unwrap();
        // Non-leaf file keeps its chain alive.
        std::fs::write(deep.join("08.00.mp4"), b"x").unwrap();
        remove_empty_record_dirs(&tmp);
        assert!(deep.join("08.00.mp4").exists());

        std::fs::remove_file(deep.join("08.00.mp4")).unwrap();
        remove_empty_record_dirs(&tmp);
        assert!(!deep.exists(), "empty chain should be pruned");
        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn infra_dirs_are_never_pruned() {
        let tmp = std::env::temp_dir().join(format!(
            "hh-nvr-infra-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        // Empty on a fresh boot — exactly the state that used to kill recording.
        let cache = tmp.join(".cache");
        let live = tmp.join("live");
        std::fs::create_dir_all(&cache).unwrap();
        std::fs::create_dir_all(&live).unwrap();

        remove_empty_record_dirs(&tmp);

        assert!(cache.exists(), ".cache must survive empty-dir pruning");
        assert!(live.exists(), "live must survive empty-dir pruning");
        let _ = std::fs::remove_dir_all(&tmp);
    }
}
