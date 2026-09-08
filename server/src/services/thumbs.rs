//! Thumbnail generation: a single 256px tier stored in `.thumbnails/`.

use std::path::{Path, PathBuf};

use anyhow::Result;
use image::ImageFormat;

use super::hash::apply_orientation;

pub const THUMBNAIL_SIZE: u32 = 256;
pub const THUMBNAIL_DIR: &str = ".thumbnails";

/// Path of the thumbnail for `file_path`, embedding the mtime/size fingerprint
/// so a changed source automatically invalidates the cached thumbnail.
pub fn thumbnail_path(file_path: &Path, fp: &str) -> PathBuf {
    let parent = file_path.parent().unwrap_or_else(|| Path::new(""));
    let file_name = file_path
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "file".into());
    parent
        .join(THUMBNAIL_DIR)
        .join(format!("{}.{}.jpg", file_name, fp))
}

/// Ensure a thumbnail exists and return its path.
pub async fn ensure_thumbnail(file_path: &Path, fp: &str, orientation: u32) -> Result<PathBuf> {
    let target = thumbnail_path(file_path, fp);
    if target.exists() {
        return Ok(target);
    }

    if let Some(parent) = target.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }

    // Remove stale variants generated for an older version of the file.
    if let Some(parent) = target.parent() {
        if let Ok(mut rd) = tokio::fs::read_dir(parent).await {
            let prefix = format!(
                "{}.",
                file_path
                    .file_name()
                    .map(|n| n.to_string_lossy().to_string())
                    .unwrap_or_default()
            );
            while let Ok(Some(entry)) = rd.next_entry().await {
                let name = entry.file_name().to_string_lossy().to_string();
                if name.starts_with(&prefix) && name.ends_with(".jpg") && entry.path() != target {
                    let _ = tokio::fs::remove_file(entry.path()).await;
                }
            }
        }
    }

    let src = file_path.to_path_buf();
    let dst = target.clone();
    tokio::task::spawn_blocking(move || generate(&src, &dst, orientation)).await??;
    Ok(target)
}

fn generate(src: &Path, dst: &Path, orientation: u32) -> Result<()> {
    let img = image::open(src)?;
    let img = apply_orientation(img, orientation);
    let thumb = img.thumbnail(THUMBNAIL_SIZE, THUMBNAIL_SIZE);
    thumb.save_with_format(dst, ImageFormat::Jpeg)?;
    Ok(())
}

/// Best-effort poster lookup for videos (real transcoding is a phase-2 item).
pub async fn video_poster(file_path: &Path) -> Option<PathBuf> {
    let parent = file_path.parent()?;
    let stem = file_path.file_stem()?.to_string_lossy().to_string();
    for name in [
        format!("{}-poster.jpg", stem),
        format!("{}.jpg", stem),
        "poster.jpg".to_string(),
        "folder.jpg".to_string(),
    ] {
        let candidate = parent.join(&name);
        if candidate.exists() {
            return Some(candidate);
        }
    }
    None
}

/// Build a video thumbnail: ffmpeg first-frame extraction, falling back to a
/// poster image sitting next to the file, falling back to the source itself
/// (clients then show a generic dark tile until ffmpeg is installed).
pub async fn ensure_video_thumbnail(
    file_path: &Path,
    fp: &str,
    ffmpeg_path: &str,
) -> Result<PathBuf> {
    let target = thumbnail_path(file_path, fp);
    if target.exists() {
        return Ok(target);
    }
    if let Some(parent) = target.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }

    // extract_poster just awaits a subprocess — no blocking work needed.
    let bin = ffmpeg_path.to_string();
    let extracted =
        crate::services::probe::extract_poster(file_path, &target, &bin).await;

    match extracted {
        Ok(_) => Ok(target),
        Err(e) => {
            tracing::debug!("video poster extraction failed for {}: {}", file_path.display(), e);
            let _ = tokio::fs::remove_file(&target).await;
            // Fallback: neighbouring poster (movie-library convention).
            if let Some(poster) = video_poster(file_path).await {
                std::fs::copy(&poster, &target)?;
                return Ok(target);
            }
            Err(e)
        }
    }
}
