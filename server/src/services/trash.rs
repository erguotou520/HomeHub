//! Recycle bin: every delete from any client is a soft delete.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result};

use crate::config::Config;
use crate::db::Db;
use crate::models::dir::DirRecord;
use crate::models::trash::TrashEntry;

/// Move `dir/rel_path` into the trash, keeping the relative layout.
pub async fn move_to_trash(
    db: &Db,
    config: &Config,
    dir: &DirRecord,
    rel_path: &str,
    is_dir: bool,
) -> Result<TrashEntry> {
    let src = Path::new(&dir.path).join(rel_path);
    if !src.exists() {
        anyhow::bail!("{} does not exist", src.display());
    }

    let size = if is_dir {
        dir_size(&src)
    } else {
        std::fs::metadata(&src).map(|m| m.len()).unwrap_or(0)
    };

    let now = crate::db::now();
    let safe_rel = rel_path.replace(['/', '\\'], "__");
    let trash_root = config.trash_dir();
    let mut target: PathBuf = trash_root
        .join(&dir.name)
        .join(format!("{}-{}", now, safe_rel));
    let mut n = 1;
    while target.exists() {
        target = trash_root
            .join(&dir.name)
            .join(format!("{}-{}-{}", now, n, safe_rel));
        n += 1;
    }
    if let Some(parent) = target.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }

    if let Some(parent) = src.parent() {
        tokio::fs::create_dir_all(parent).await.ok();
    }
    // The NAS directories are CIFS mounts while the trash lives on the local
    // data disk: `rename` across filesystems fails with EXDEV. Fall back to a
    // verified copy + delete, and only unlink the source once the copy is
    // complete so a failed delete never leaves two copies behind.
    move_path(&src, &target)
        .await
        .with_context(|| format!("move {} -> {}", src.display(), target.display()))?;

    let retention = config.runtime.trash.retention_days as i64;
    let purged_due = now + retention * 86400;
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO trash_entries (dir_id, dir_name, rel_path, trash_path, is_dir, size, trashed_at, purged_due) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
    )
    .bind(dir.id)
    .bind(&dir.name)
    .bind(rel_path)
    .bind(target.to_string_lossy().to_string())
    .bind(if is_dir { 1 } else { 0 })
    .bind(size as i64)
    .bind(now)
    .bind(purged_due)
    .fetch_one(db)
    .await?;

    Ok(TrashEntry {
        id,
        dir_id: Some(dir.id),
        dir_name: dir.name.clone(),
        rel_path: rel_path.to_string(),
        trash_path: target.to_string_lossy().to_string(),
        is_dir: if is_dir { 1 } else { 0 },
        size: size as i64,
        trashed_at: now,
        purged_due,
    })
}

pub async fn list(db: &Db) -> Result<Vec<TrashEntry>> {
    Ok(
        sqlx::query_as::<_, TrashEntry>(
            "SELECT id, dir_id, dir_name, rel_path, trash_path, is_dir, size, trashed_at, purged_due \
             FROM trash_entries ORDER BY trashed_at DESC",
        )
        .fetch_all(db)
        .await?,
    )
}

pub async fn get(db: &Db, id: i64) -> Result<Option<TrashEntry>> {
    Ok(
        sqlx::query_as::<_, TrashEntry>(
            "SELECT id, dir_id, dir_name, rel_path, trash_path, is_dir, size, trashed_at, purged_due \
             FROM trash_entries WHERE id = ?",
        )
        .bind(id)
        .fetch_optional(db)
        .await?,
    )
}

/// Put a trashed item back where it came from.
pub async fn restore(
    db: &Db,
    registry: &crate::services::dirs::DirRegistry,
    id: i64,
) -> Result<()> {
    let entry = get(db, id)
        .await?
        .ok_or_else(|| anyhow::anyhow!("trash entry {} not found", id))?;
    let dir = registry
        .all()
        .into_iter()
        .find(|d| d.name == entry.dir_name)
        .or_else(|| entry.dir_id.and_then(|i| registry.by_id(i)))
        .ok_or_else(|| anyhow::anyhow!("source directory '{}' no longer registered", entry.dir_name))?;

    let dest = Path::new(&dir.path).join(&entry.rel_path);
    if dest.exists() {
        anyhow::bail!("{} already exists", dest.display());
    }
    if let Some(parent) = dest.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    move_path(Path::new(&entry.trash_path), &dest)
        .await
        .with_context(|| format!("restore {} -> {}", entry.trash_path, dest.display()))?;

    sqlx::query("DELETE FROM trash_entries WHERE id = ?")
        .bind(id)
        .execute(db)
        .await?;
    Ok(())
}

/// Remove a trashed item from disk for good.
pub async fn purge(db: &Db, id: i64) -> Result<()> {
    let entry = get(db, id)
        .await?
        .ok_or_else(|| anyhow::anyhow!("trash entry {} not found", id))?;
    let path = Path::new(&entry.trash_path);
    if path.is_dir() {
        tokio::fs::remove_dir_all(path).await.ok();
    } else {
        tokio::fs::remove_file(path).await.ok();
    }
    sqlx::query("DELETE FROM trash_entries WHERE id = ?")
        .bind(id)
        .execute(db)
        .await?;
    Ok(())
}

/// Empty the trash.
pub async fn purge_all(db: &Db) -> Result<u64> {
    let entries = list(db).await?;
    let mut n = 0;
    for entry in entries {
        if purge(db, entry.id).await.is_ok() {
            n += 1;
        }
    }
    Ok(n)
}

/// Delete entries past their retention date.
pub async fn purge_expired(db: &Db) -> Result<(u64, u64)> {
    let now = crate::db::now();
    let entries: Vec<(i64, String, i64)> =
        sqlx::query_as("SELECT id, trash_path, size FROM trash_entries WHERE purged_due <= ?")
            .bind(now)
            .fetch_all(db)
            .await?;
    let mut count = 0u64;
    let mut freed = 0u64;
    for (id, path, size) in entries {
        let p = PathBuf::from(&path);
        if p.is_dir() {
            tokio::fs::remove_dir_all(&p).await.ok();
        } else {
            tokio::fs::remove_file(&p).await.ok();
        }
        sqlx::query("DELETE FROM trash_entries WHERE id = ?")
            .bind(id)
            .execute(db)
            .await?;
        count += 1;
        freed += size as u64;
    }
    Ok((count, freed))
}

/// Number of entries and bytes currently held in the trash.
pub async fn usage(db: &Db) -> Result<(i64, i64)> {
    let row: (i64, i64) = sqlx::query_as(
        "SELECT COUNT(*), COALESCE(SUM(size), 0) FROM trash_entries",
    )
    .fetch_one(db)
    .await?;
    Ok(row)
}

/// Move a path to another filesystem. `rename` cannot cross a CIFS mount and
/// the local data disk (EXDEV), so fall back to copy + delete. The source is
/// removed only after the whole tree has been copied; if the removal fails,
/// the copy is rolled back so a failed delete never leaves two copies.
async fn move_path(src: &Path, dst: &Path) -> Result<()> {
    if tokio::fs::rename(src, dst).await.is_ok() {
        return Ok(());
    }

    let copied = if src.is_dir() {
        copy_tree(src, dst).await
    } else {
        if let Some(parent) = dst.parent() {
            tokio::fs::create_dir_all(parent).await?;
        }
        tokio::fs::copy(src, dst)
            .await
            .map(|_| ())
            .map_err(anyhow::Error::from)
    };
    if let Err(e) = copied {
        return Err(e).with_context(|| {
            format!(
                "copy fallback failed after rename {} -> {}",
                src.display(),
                dst.display()
            )
        });
    }

    let removed = if src.is_dir() {
        tokio::fs::remove_dir_all(src).await
    } else {
        tokio::fs::remove_file(src).await
    };
    if let Err(e) = removed {
        let _ = if dst.is_dir() {
            tokio::fs::remove_dir_all(dst).await
        } else {
            tokio::fs::remove_file(dst).await
        };
        return Err(e).with_context(|| {
            format!(
                "remove source {} failed after copying to {}",
                src.display(),
                dst.display()
            )
        });
    }
    Ok(())
}

/// Recursive copy that preserves directory permissions and symlinks.
async fn copy_tree(src: &Path, dst: &Path) -> Result<()> {
    tokio::fs::create_dir_all(dst).await?;
    for entry in walkdir::WalkDir::new(src).min_depth(1) {
        let entry = entry?;
        let rel = entry.path().strip_prefix(src)?;
        let to = dst.join(rel);
        let file_type = entry.file_type();
        if file_type.is_dir() {
            tokio::fs::create_dir_all(&to).await?;
            #[cfg(unix)]
            {
                let permissions = entry.metadata()?.permissions();
                tokio::fs::set_permissions(&to, permissions).await?;
            }
        } else if file_type.is_file() {
            tokio::fs::copy(entry.path(), &to).await?;
        } else if file_type.is_symlink() {
            #[cfg(unix)]
            {
                let target = std::fs::read_link(entry.path())?;
                std::os::unix::fs::symlink(target, &to)?;
            }
        }
    }
    Ok(())
}

fn dir_size(path: &Path) -> u64 {
    walkdir::WalkDir::new(path)
        .into_iter()
        .filter_map(|e| e.ok())
        .filter(|e| e.file_type().is_file())
        .filter_map(|e| e.metadata().ok())
        .map(|m| m.len())
        .sum()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A missing destination parent forces `rename` to fail, exercising the
    /// same fallback path a CIFS -> local-disk EXDEV error takes.
    #[tokio::test]
    async fn move_path_falls_back_for_a_file() {
        let base = std::env::temp_dir().join(format!("hh-trash-file-{}", std::process::id()));
        let _ = tokio::fs::remove_dir_all(&base).await;
        let src = base.join("src.txt");
        tokio::fs::create_dir_all(&base).await.unwrap();
        tokio::fs::write(&src, "hello").await.unwrap();
        let dst = base.join("missing").join("trash").join("src.txt");

        move_path(&src, &dst).await.unwrap();

        assert!(!src.exists());
        assert_eq!(tokio::fs::read_to_string(&dst).await.unwrap(), "hello");
        let _ = tokio::fs::remove_dir_all(&base).await;
    }

    #[tokio::test]
    async fn move_path_falls_back_for_a_directory_tree() {
        let base = std::env::temp_dir().join(format!("hh-trash-dir-{}", std::process::id()));
        let _ = tokio::fs::remove_dir_all(&base).await;
        let src = base.join("src");
        tokio::fs::create_dir_all(src.join("nested")).await.unwrap();
        tokio::fs::write(src.join("nested").join("a.txt"), "data").await.unwrap();
        let dst = base.join("missing").join("trash").join("src");

        move_path(&src, &dst).await.unwrap();

        assert!(!src.exists());
        assert_eq!(
            tokio::fs::read_to_string(dst.join("nested").join("a.txt"))
                .await
                .unwrap(),
            "data"
        );
        let _ = tokio::fs::remove_dir_all(&base).await;
    }
}
