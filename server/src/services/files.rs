//! NAS file management: browse, upload, rename, copy, move, delete (soft).

use std::path::Path;

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};

use crate::config::Config;
use crate::db::Db;
use crate::models::dir::DirRecord;
use crate::services::dirs::DirRegistry;
use crate::services::paths::{ext_of, is_image, is_music, is_video, safe_join};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileEntry {
    pub name: String,
    pub path: String,
    pub is_dir: bool,
    pub size: Option<u64>,
    pub modified: Option<i64>,
    pub mime_type: Option<String>,
    pub thumbnail: Option<String>,
    pub media_kind: &'static str,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SortKey {
    Name,
    Mtime,
    Size,
}

impl SortKey {
    pub fn parse(s: Option<&str>) -> Self {
        match s.unwrap_or("name").to_lowercase().as_str() {
            "mtime" | "time" | "modified" => Self::Mtime,
            "size" => Self::Size,
            _ => Self::Name,
        }
    }
}

/// List the contents of `<dir>/rel_path`.
pub async fn list_directory(
    dir: &DirRecord,
    rel_path: &str,
    sort: SortKey,
    desc: bool,
) -> Result<Vec<FileEntry>> {
    let root = Path::new(&dir.path);
    let target = match safe_join(root, rel_path) {
        Some(p) => p,
        None => anyhow::bail!("invalid path"),
    };
    if !target.is_dir() {
        anyhow::bail!("not a directory: {}", target.display());
    }

    let mut entries = Vec::new();
    let mut rd = tokio::fs::read_dir(&target).await?;
    while let Some(entry) = rd.next_entry().await? {
        let name = entry.file_name().to_string_lossy().to_string();
        if name.starts_with('.') {
            continue;
        }
        let metadata = entry.metadata().await?;
        let is_dir = metadata.is_dir();
        let size = if is_dir { None } else { Some(metadata.len()) };
        let modified = metadata
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_secs() as i64);
        let mime_type = if is_dir {
            None
        } else {
            mime_guess::from_path(entry.path())
                .first()
                .map(|m| m.to_string())
        };
        let ext = ext_of(&name);
        let media_kind = if is_dir {
            "dir"
        } else if is_image(&ext) {
            "image"
        } else if is_video(&ext) {
            "video"
        } else if is_music(&ext) {
            "music"
        } else {
            "file"
        };
        let virtual_path = if rel_path.is_empty() {
            format!("{}/{}", dir.name, name)
        } else {
            format!("{}/{}/{}", dir.name, rel_path.trim_end_matches('/'), name)
        };

        entries.push(FileEntry {
            name,
            path: virtual_path,
            is_dir,
            size,
            modified,
            mime_type,
            thumbnail: None,
            media_kind,
        });
    }

    entries.sort_by(|a, b| {
        // Directories first, then the requested key.
        let ord = match (a.is_dir, b.is_dir) {
            (true, false) => std::cmp::Ordering::Less,
            (false, true) => std::cmp::Ordering::Greater,
            _ => match sort {
                SortKey::Name => a.name.to_lowercase().cmp(&b.name.to_lowercase()),
                SortKey::Mtime => a.modified.unwrap_or(0).cmp(&b.modified.unwrap_or(0)),
                SortKey::Size => a.size.unwrap_or(0).cmp(&b.size.unwrap_or(0)),
            },
        };
        if desc { ord.reverse() } else { ord }
    });
    if desc {
        // Keep directories pinned to the top regardless of direction.
        entries.sort_by_key(|e| !e.is_dir);
    }

    Ok(entries)
}

pub async fn create_dir(dir: &DirRecord, rel_path: &str, name: &str) -> Result<()> {
    let target = safe_join(Path::new(&dir.path), rel_path).context("invalid path")?;
    let safe_name = name.replace(['/', '\\'], "_");
    if safe_name.trim().is_empty() || safe_name == "." || safe_name == ".." {
        anyhow::bail!("invalid directory name");
    }
    tokio::fs::create_dir_all(target.join(safe_name)).await?;
    Ok(())
}

#[derive(Debug, Clone, Serialize)]
pub struct UploadOutcome {
    pub name: String,
    pub size: u64,
    pub duplicate_of: Option<String>,
    /// true when the server discarded the upload because a duplicate exists
    /// and the caller asked for `on-duplicate=skip`.
    #[serde(rename = "skipped", skip_serializing_if = "std::ops::Not::not")]
    pub skipped: bool,
    pub queued: bool,
}

/// What to do when an upload is byte-identical (or pixel-identical) to an
/// already-stored file.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OnDuplicate {
    /// Discard the new upload and report where the existing copy lives.
    Skip,
    /// Keep both (current behaviour).
    Keep,
}

impl OnDuplicate {
    pub fn from_query(v: Option<&String>) -> Self {
        match v.map(|s| s.as_str()) {
            Some("skip") => OnDuplicate::Skip,
            _ => OnDuplicate::Keep,
        }
    }
}

/// Persist an uploaded file, run the dedup check and enqueue the pipeline.
/// Persist already-read bytes as a file and hand them to the pipeline.
pub async fn save_upload_bytes(
    db: &Db,
    config: &Config,
    registry: &DirRegistry,
    queue: &crate::services::tasks::TaskQueue,
    dir: &DirRecord,
    rel_path: &str,
    file_name: &str,
    data: &[u8],
) -> Result<UploadOutcome> {
    save_upload_bytes_with_policy(db, config, registry, queue, dir, rel_path, file_name, data, OnDuplicate::Keep).await
}

#[allow(clippy::too_many_arguments)]
pub async fn save_upload_bytes_with_policy(
    db: &Db,
    config: &Config,
    registry: &DirRegistry,
    queue: &crate::services::tasks::TaskQueue,
    dir: &DirRecord,
    rel_path: &str,
    file_name: &str,
    data: &[u8],
    on_duplicate: OnDuplicate,
) -> Result<UploadOutcome> {
    let target_dir = safe_join(Path::new(&dir.path), rel_path).context("invalid path")?;
    tokio::fs::create_dir_all(&target_dir).await?;

    let safe_name = file_name.replace(['/', '\\'], "_").replace("..", "_");
    let mut final_path = target_dir.join(&safe_name);
    if final_path.exists() {
        let stem = Path::new(&safe_name)
            .file_stem()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_default();
        let ext = ext_of(&safe_name);
        for i in 1..1000 {
            let candidate = target_dir.join(format!("{}-{}.{}", stem, i, ext));
            if !candidate.exists() {
                final_path = candidate;
                break;
            }
        }
    }

    // Stream to a temp file first so a partial upload never shows up as a photo.
    let tmp = final_path.with_extension("upload.tmp");
    tokio::fs::write(&tmp, data).await?;

    // Dedup: compute the hashes of the *source* bytes before any processing.
    let file_hash = crate::services::hash::bytes_sha256(data);
    let ext = ext_of(&safe_name);
    let pixel_hash = if is_image(&ext) {
        crate::services::hash::pixel_hash(&tmp, 1)
    } else {
        None
    };

    let mut duplicate_of = None;
    if is_image(&ext) {
        if let Ok(Some(existing)) =
            crate::services::dedup::find_duplicate(db, registry, &file_hash, pixel_hash.as_deref())
                .await
        {
            let location = format!("{}/{}", existing.dir_name, existing.rel_path);
            if on_duplicate == OnDuplicate::Skip {
                // Discard the upload entirely — the bytes already live at `location`.
                tokio::fs::remove_file(&tmp).await?;
                tracing::info!(
                    "upload skipped as duplicate of {} ({} bytes)",
                    location,
                    data.len()
                );
                return Ok(UploadOutcome {
                    name: safe_name,
                    size: data.len() as u64,
                    duplicate_of: Some(location),
                    skipped: true,
                    queued: false,
                });
            }
            duplicate_of = Some(location);
        }
    }

    tokio::fs::rename(&tmp, &final_path).await?;

    let rel = relative_of(dir, &final_path);
    let metadata = std::fs::metadata(&final_path)?;
    let mtime = metadata
        .modified()?
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    let size = metadata.len();
    let fp = crate::services::paths::fingerprint(mtime, size);

    sqlx::query(
        "INSERT INTO file_index (dir_id, rel_path, name, is_dir, size, mtime, fingerprint) \
         VALUES (?, ?, ?, 0, ?, ?, ?) \
         ON CONFLICT(dir_id, rel_path) DO UPDATE SET size = excluded.size, mtime = excluded.mtime, \
             fingerprint = excluded.fingerprint",
    )
    .bind(dir.id)
    .bind(&rel)
    .bind(&safe_name)
    .bind(size as i64)
    .bind(mtime)
    .bind(&fp)
    .execute(db)
    .await?;
    crate::services::search::index_file(db, dir.id, &dir.name, &rel, &safe_name).await?;

    let mut queued = false;
    if is_image(&ext) && dir.has_mark(crate::models::dir::DirMark::Album) {
        let meta = crate::services::exif::read_metadata(&final_path).unwrap_or_default();
        let photo_id = crate::services::photos::upsert(
            db,
            dir,
            &rel,
            &fp,
            size as i64,
            mtime,
            &meta,
            &file_hash,
            pixel_hash.as_deref(),
        )
        .await?;
        if let Some(asset) = crate::services::photos::get(db, photo_id).await? {
            crate::services::search::index_photo(db, &asset, &dir.name).await?;
        }
        queue
            .enqueue_photo_pipeline(
                dir.id,
                &rel,
                crate::services::tasks::PRIORITY_HIGH,
                true,
            )
            .await?;
        queued = true;
    } else {
        // Non-image uploads still need to be discoverable.
        queue.enqueue_scan(dir.id, false, crate::services::tasks::PRIORITY_HIGH).await?;
    }
    let _ = config;

    Ok(UploadOutcome {
        name: safe_name,
        size,
        duplicate_of,
        skipped: false,
        queued,
    })
}

pub fn relative_of(dir: &DirRecord, full: &Path) -> String {
    full.strip_prefix(&dir.path)
        .map(|p| p.to_string_lossy().replace('\\', "/"))
        .unwrap_or_else(|_| full.to_string_lossy().to_string())
}

/// Rename a file or directory (keeps it inside the same parent).
pub async fn rename(dir: &DirRecord, rel_path: &str, new_name: &str) -> Result<String> {
    let src = safe_join(Path::new(&dir.path), rel_path).context("invalid path")?;
    let safe_name = new_name.replace(['/', '\\'], "_");
    if safe_name.trim().is_empty() {
        anyhow::bail!("invalid name");
    }
    let dest = src.parent().unwrap_or(Path::new(".")).join(&safe_name);
    if dest.exists() {
        anyhow::bail!("{} already exists", safe_name);
    }
    tokio::fs::rename(&src, &dest).await?;
    Ok(relative_of(dir, &dest))
}

/// Copy a file or directory.
pub async fn copy(
    registry: &DirRegistry,
    from: (&DirRecord, &str),
    to: (&DirRecord, &str),
) -> Result<()> {
    let src = safe_join(Path::new(&from.0.path), from.1).context("invalid source")?;
    let dest = safe_join(Path::new(&to.0.path), to.1).context("invalid destination")?;
    if dest.exists() {
        anyhow::bail!("destination already exists");
    }
    if let Some(parent) = dest.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    if src.is_dir() {
        copy_dir_recursive(&src, &dest).await?;
    } else {
        tokio::fs::copy(&src, &dest).await?;
    }
    let _ = registry;
    Ok(())
}

async fn copy_dir_recursive(src: &Path, dest: &Path) -> Result<()> {
    tokio::fs::create_dir_all(dest).await?;
    let mut rd = tokio::fs::read_dir(src).await?;
    while let Some(entry) = rd.next_entry().await? {
        let target = dest.join(entry.file_name());
        if entry.file_type().await?.is_dir() {
            Box::pin(copy_dir_recursive(&entry.path(), &target)).await?;
        } else {
            tokio::fs::copy(entry.path(), &target).await?;
        }
    }
    Ok(())
}

/// Move a file or directory (across directories too).
pub async fn move_path(
    registry: &DirRegistry,
    from: (&DirRecord, &str),
    to: (&DirRecord, &str),
) -> Result<()> {
    let src = safe_join(Path::new(&from.0.path), from.1).context("invalid source")?;
    let dest = safe_join(Path::new(&to.0.path), to.1).context("invalid destination")?;
    if dest.exists() {
        anyhow::bail!("destination already exists");
    }
    if let Some(parent) = dest.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    match tokio::fs::rename(&src, &dest).await {
        Ok(()) => Ok(()),
        Err(_) => {
            // Cross-device move: copy then delete.
            copy(registry, from, to).await?;
            if src.is_dir() {
                tokio::fs::remove_dir_all(&src).await?;
            } else {
                tokio::fs::remove_file(&src).await?;
            }
            Ok(())
        }
    }
}
