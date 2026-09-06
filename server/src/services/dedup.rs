//! Global de-duplication using two fingerprints.
//!
//! * `file_hash` – SHA-256 of the **source bytes**, computed when a file is
//!   uploaded (before the server has touched it). Exact duplicates only.
//! * `pixel_hash` – dHash of the **decoded pixels**, insensitive to lossless
//!   re-compression. Catches files that the server already processed.

use anyhow::Result;

use crate::db::Db;
use crate::models::photo::{DuplicateGroup, PhotoItem};
use crate::services::dirs::DirRegistry;
use crate::services::photos;

/// Look up an existing photo by either fingerprint.
///
/// Used at upload time *before* the file enters the processing pipeline.
pub async fn find_duplicate(
    db: &Db,
    _registry: &DirRegistry,
    file_hash: &str,
    pixel_hash: Option<&str>,
) -> Result<Option<PhotoItem>> {
    let sql = "SELECT p.*, d.name AS dir_name FROM photo_assets p JOIN dirs d ON d.id = p.dir_id \
               WHERE p.status = 'ok' AND (p.file_hash = ? OR (? IS NOT NULL AND p.pixel_hash = ?)) \
               LIMIT 1";
    let row = sqlx::query_as::<_, photos::PhotoRow>(sql)
        .bind(file_hash)
        .bind(pixel_hash)
        .bind(pixel_hash)
        .fetch_optional(db)
        .await?;
    match row {
        Some(row) => Ok(photos::to_items(db, vec![row]).await?.into_iter().next()),
        None => Ok(None),
    }
}

/// Every duplicate group in the library (exact first, perceptual second).
pub async fn scan(db: &Db, registry: &DirRegistry) -> Result<Vec<DuplicateGroup>> {
    photos::duplicates(db, registry).await
}

/// Move all but the first item of a duplicate group into the trash.
pub async fn move_group_to_trash(
    db: &Db,
    config: &crate::config::Config,
    registry: &DirRegistry,
    fingerprint: &str,
) -> Result<u64> {
    let groups = scan(db, registry).await?;
    let group = groups
        .into_iter()
        .find(|g| g.fingerprint == fingerprint)
        .ok_or_else(|| anyhow::anyhow!("duplicate group not found"))?;

    let mut moved = 0u64;
    for item in group.items.into_iter().skip(1) {
        let Some(dir) = registry.by_id(item.dir_id) else {
            continue;
        };
        match crate::services::trash::move_to_trash(db, config, &dir, &item.rel_path, false).await {
            Ok(_) => {
                photos::mark_trashed(db, dir.id, &item.rel_path).await?;
                moved += 1;
            }
            Err(e) => tracing::warn!("cannot trash {}: {}", item.rel_path, e),
        }
    }
    Ok(moved)
}
