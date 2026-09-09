//! `.originals/` archive: pixel backups for destructive operations.
//!
//! Only destructive edits (manual rotation today, lossy features in the future)
//! create an original. Lossless compression never does — the pixels are
//! identical, there is nothing to roll back to.

use std::path::{Path, PathBuf};

use anyhow::Result;
use walkdir::WalkDir;

use crate::config::Config;
use crate::models::dir::DirRecord;
use crate::services::paths::safe_join;

/// Copy the current file bytes into the originals archive.
///
/// Returns the archive path, or `None` when protection is disabled / already present.
pub async fn archive(file: &Path, dir: &DirRecord, rel_path: &str, config: &Config) -> Result<Option<PathBuf>> {
    if !config.runtime.originals.enabled {
        return Ok(None);
    }
    let Some(target) = target_path(dir, rel_path, config) else {
        return Ok(None);
    };
    // Keep the very first archived version: it is the true original.
    if target.exists() {
        return Ok(None);
    }
    if let Some(parent) = target.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    // create_new makes "keep the very first version" atomic: a concurrent
    // first-edit cannot overwrite the archive with already-edited pixels.
    let bytes = tokio::fs::read(file).await?;
    match tokio::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&target)
        .await
    {
        Ok(mut f) => {
            tokio::io::AsyncWriteExt::write_all(&mut f, &bytes).await?;
            tracing::info!("archived original -> {}", target.display());
            Ok(Some(target))
        }
        Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => Ok(None),
        Err(e) => Err(e.into()),
    }
}

fn target_path(dir: &DirRecord, rel_path: &str, config: &Config) -> Option<PathBuf> {
    safe_join(&config.originals_dir().join(&dir.name), rel_path)
}

/// Total size and file count of the archive.
pub async fn usage(config: &Config) -> Result<(u64, u64)> {
    let root = config.originals_dir();
    if !root.exists() {
        return Ok((0, 0));
    }
    let mut count = 0u64;
    let mut bytes = 0u64;
    for entry in WalkDir::new(&root).into_iter().filter_map(|e| e.ok()) {
        if entry.file_type().is_file() {
            count += 1;
            bytes += entry.metadata().map(|m| m.len()).unwrap_or(0);
        }
    }
    Ok((count, bytes))
}

/// Delete archived originals older than the retention period.
pub async fn purge_expired(config: &Config) -> Result<(u64, u64)> {
    let retention = config.runtime.originals.retention_days as i64;
    let cutoff = crate::db::now() - retention * 86400;
    let root = config.originals_dir();
    if !root.exists() {
        return Ok((0, 0));
    }
    let mut removed = 0u64;
    let mut freed = 0u64;
    for entry in WalkDir::new(&root).into_iter().filter_map(|e| e.ok()) {
        if !entry.file_type().is_file() {
            continue;
        }
        let mtime = entry
            .metadata()
            .ok()
            .and_then(|m| m.modified().ok())
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        if mtime < cutoff {
            let size = entry.metadata().map(|m| m.len()).unwrap_or(0);
            if tokio::fs::remove_file(entry.path()).await.is_ok() {
                removed += 1;
                freed += size;
            }
        }
    }
    // Drop empty directories left behind.
    let _ = prune_empty_dirs(&root).await;
    Ok((removed, freed))
}

async fn prune_empty_dirs(root: &Path) -> Result<()> {
    for entry in WalkDir::new(root).min_depth(1).into_iter().filter_map(|e| e.ok()) {
        if entry.file_type().is_dir() {
            let _ = tokio::fs::remove_dir(entry.path()).await;
        }
    }
    Ok(())
}
