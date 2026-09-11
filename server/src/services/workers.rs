//! Task handlers executed by the worker pool.

use std::path::PathBuf;

use anyhow::{Context, Result};

use crate::models::task::{FileTaskPayload, TaskKind};
use crate::services::tasks::TaskQueue;

/// Resolve a `(dir_id, rel_path)` payload into a real filesystem path.
fn resolve(queue: &TaskQueue, payload: &FileTaskPayload) -> Result<(crate::models::dir::DirRecord, PathBuf)> {
    queue
        .registry()
        .resolve_id(payload.dir_id, &payload.rel_path)
        .ok_or_else(|| anyhow::anyhow!("cannot resolve {} in dir {}", payload.rel_path, payload.dir_id))
}

async fn load_photo(
    queue: &TaskQueue,
    payload: &FileTaskPayload,
) -> Result<Option<crate::models::photo::PhotoAsset>> {
    crate::services::photos::get_path(queue.db(), payload.dir_id, &payload.rel_path).await
}

/// Build (or rebuild) the 256px thumbnail.
pub async fn make_thumbnail(queue: &TaskQueue, payload: &FileTaskPayload) -> Result<()> {
    let (dir, full) = resolve(queue, payload)?;
    if !full.exists() {
        anyhow::bail!("file missing: {}", full.display());
    }
    let Some(asset) = load_photo(queue, payload).await? else {
        anyhow::bail!("photo not indexed: {}", payload.rel_path);
    };
    let fp = crate::services::paths::fingerprint(asset.mtime, asset.size as u64);
    if asset.media_kind == "video" {
        let ffmpeg_path = queue.config().runtime.video.ffmpeg_path.clone();
        crate::services::thumbs::ensure_video_thumbnail(&full, &fp, &ffmpeg_path).await?;
    } else {
        crate::services::thumbs::ensure_thumbnail(&full, &fp, asset.orientation as u32).await?;
    }
    let _ = dir;
    Ok(())
}

/// ffprobe a video and persist duration / resolution / capture time / codec.
pub async fn probe_video(queue: &TaskQueue, payload: &FileTaskPayload) -> Result<()> {
    let (_dir, full) = resolve(queue, payload)?;
    if !full.exists() {
        anyhow::bail!("file missing: {}", full.display());
    }
    let Some(asset) = load_photo(queue, payload).await? else {
        anyhow::bail!("video not indexed: {}", payload.rel_path);
    };
    if asset.media_kind != "video" {
        return Ok(());
    }

    let ffprobe_path = queue.config().runtime.video.ffprobe_path.clone();
    let meta = crate::services::probe::probe(&full, &ffprobe_path).await?;

    // EXIF-only fields stay untouched when ffprobe yields nothing.
    let taken_at = meta.taken_at.or(asset.taken_at);
    let gps_lat = meta.gps_lat.or(asset.gps_lat);
    let gps_lng = meta.gps_lng.or(asset.gps_lng);
    let width = meta.width.or(asset.width);
    let height = meta.height.or(asset.height);

    sqlx::query(
        "UPDATE photo_assets SET duration_ms = ?, video_codec = ?, taken_at = ?, \
         gps_lat = ?, gps_lng = ?, width = ?, height = ?, updated_at = ? \
         WHERE id = ?",
    )
    .bind(meta.duration_ms)
    .bind(meta.codec)
    .bind(taken_at)
    .bind(gps_lat)
    .bind(gps_lng)
    .bind(width)
    .bind(height)
    .bind(crate::db::now())
    .bind(asset.id)
    .execute(queue.db())
    .await?;
    Ok(())
}

/// Re-read EXIF (capture time / GPS) for a photo.
pub async fn refresh_geo(queue: &TaskQueue, payload: &FileTaskPayload) -> Result<()> {
    let (_dir, full) = resolve(queue, payload)?;
    if !full.exists() {
        anyhow::bail!("file missing: {}", full.display());
    }
    let meta = crate::services::exif::read_metadata(&full)?;
    let now = crate::db::now();
    sqlx::query(
        "UPDATE photo_assets SET taken_at = ?, gps_lat = ?, gps_lng = ?, orientation = ?, \
         camera_make = ?, camera_model = ?, updated_at = ? WHERE dir_id = ? AND rel_path = ?",
    )
    .bind(meta.taken_at)
    .bind(meta.gps_lat)
    .bind(meta.gps_lng)
    .bind(meta.orientation as i64)
    .bind(&meta.camera_make)
    .bind(&meta.camera_model)
    .bind(now)
    .bind(payload.dir_id)
    .bind(&payload.rel_path)
    .execute(queue.db())
    .await?;
    Ok(())
}

/// Run one of the detection passes and persist its result.
pub async fn run_detection(
    queue: &TaskQueue,
    kind: TaskKind,
    payload: &FileTaskPayload,
) -> Result<()> {
    let (_dir, full) = resolve(queue, payload)?;
    if !full.exists() {
        anyhow::bail!("file missing: {}", full.display());
    }
    let Some(asset) = load_photo(queue, payload).await? else {
        anyhow::bail!("photo not indexed: {}", payload.rel_path);
    };

    let ml = queue.config().runtime.ml.clone();
    let min_side = asset
        .width
        .unwrap_or(0)
        .min(asset.height.unwrap_or(0)) as u32;
    if min_side > 0 && min_side < ml.min_image_size {
        return Ok(());
    }

    let detector = queue.detector().clone();
    let path = full.clone();
    let orientation = asset.orientation as u32;

    let outcome = tokio::task::spawn_blocking(move || -> Result<DetectionOutcome> {
        let img = image::open(&path).context("decode image")?;
        let img = crate::services::hash::apply_orientation(img, orientation);
        let mut outcome = DetectionOutcome::default();
        match kind {
            TaskKind::DetectObject => {
                if ml.object.enabled {
                    outcome.ran = true;
                    outcome.tags = crate::services::ml::sanitize(
                        detector.detect_objects(&img)?,
                        ml.object.threshold,
                        &ml.exclude_tags,
                        20,
                    );
                }
            }
            TaskKind::DetectScene => {
                if ml.scene.enabled {
                    outcome.ran = true;
                    outcome.tags = crate::services::ml::sanitize(
                        detector.detect_scenes(&img)?,
                        ml.scene.threshold,
                        &ml.exclude_tags,
                        10,
                    );
                }
            }
            TaskKind::DetectFace => {
                if ml.face.enabled {
                    outcome.ran = true;
                    outcome.faces = detector.detect_faces(&img)?;
                }
            }
            _ => {}
        }
        Ok(outcome)
    })
    .await??;

    let kind_str = match kind {
        TaskKind::DetectObject => "object",
        TaskKind::DetectScene => "scene",
        _ => "face",
    };

    // Replace unconditionally once the pass actually ran: guarding this on
    // `!tags.is_empty()` meant a new model that finds nothing left the previous
    // model's tags in place forever, so switching backends never took effect.
    if outcome.ran {
        let tags: Vec<(String, f64)> = outcome
            .tags
            .into_iter()
            .map(|d| (d.tag, d.confidence as f64))
            .collect();
        crate::services::photos::replace_tags(queue.db(), asset.id, kind_str, &tags).await?;
    }

    // Same reasoning as the tags above: a re-run with a better face model must
    // be able to *remove* faces it no longer finds, otherwise the old boxes
    // (and the person groups built from them) linger forever.
    if kind_str == "face" && outcome.ran {
        let db = queue.db().clone();
        let face_cfg = ml.face.clone();
        let faces = outcome.faces;
        let photo_id = asset.id;
        sqlx::query("DELETE FROM faces WHERE photo_id = ?")
            .bind(photo_id)
            .execute(&db)
            .await?;
        let now = crate::db::now();
        for face in faces {
            let face_id: i64 = sqlx::query_scalar(
                "INSERT INTO faces (photo_id, box_x, box_y, box_w, box_h, phash, created_at) \
                 VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
            )
            .bind(photo_id)
            .bind(face.x as f64)
            .bind(face.y as f64)
            .bind(face.w as f64)
            .bind(face.h as f64)
            .bind(&face.hash)
            .bind(now)
            .fetch_one(&db)
            .await?;
            if !face.hash.is_empty() {
                crate::services::ml::cluster::assign_group(&db, face_id, &face.hash, &face_cfg)
                    .await?;
            }
        }
        // The DELETE above can orphan groups: either all their faces are gone,
        // or only their representative face (stale `representative_face_id`
        // dangling). Sweep them so the people view stays free of empty shells.
        sqlx::query(
            "DELETE FROM person_groups WHERE id NOT IN (SELECT DISTINCT group_id FROM faces WHERE group_id IS NOT NULL) \
             OR representative_face_id NOT IN (SELECT id FROM faces)",
        )
        .execute(&db)
        .await?;
    }

    if let Some(updated) = crate::services::photos::get(queue.db(), asset.id).await? {
        let dir_name = queue
            .registry()
            .by_id(updated.dir_id)
            .map(|d| d.name)
            .unwrap_or_default();
        crate::services::search::index_photo(queue.db(), &updated, &dir_name).await?;
    }

    Ok(())
}

#[derive(Default)]
struct DetectionOutcome {
    tags: Vec<crate::services::ml::Detection>,
    faces: Vec<crate::services::ml::FaceBox>,
    /// True when the detector actually executed — distinguishes "ran and found
    /// nothing" (must clear stored results) from "skipped / disabled" (must
    /// leave them alone).
    ran: bool,
}

/// Lossless compression pass.
pub async fn run_compression(queue: &TaskQueue, payload: &FileTaskPayload) -> Result<()> {
    let (dir, full) = resolve(queue, payload)?;
    if !full.exists() {
        anyhow::bail!("file missing: {}", full.display());
    }
    let Some(asset) = load_photo(queue, payload).await? else {
        anyhow::bail!("photo not indexed: {}", payload.rel_path);
    };
    if asset.compressed != 0 {
        return Ok(());
    }

    let config = queue.config().clone();
    let result = crate::services::compress::compress(&full, &config).await?;
    if !result.applied {
        return Ok(());
    }

    let meta = std::fs::metadata(&full)?;
    let mtime = meta
        .modified()?
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(asset.mtime);
    let size = meta.len() as i64;
    let fp = crate::services::paths::fingerprint(mtime, size as u64);
    let pixel_hash = crate::services::hash::pixel_hash(&full, asset.orientation as u32);

    sqlx::query(
        "UPDATE photo_assets SET size = ?, mtime = ?, fingerprint = ?, pixel_hash = ?, \
         compressed = 1, updated_at = ? WHERE id = ?",
    )
    .bind(size)
    .bind(mtime)
    .bind(&fp)
    .bind(pixel_hash)
    .bind(crate::db::now())
    .bind(asset.id)
    .execute(queue.db())
    .await?;

    sqlx::query("UPDATE file_index SET size = ?, mtime = ?, fingerprint = ? WHERE dir_id = ? AND rel_path = ?")
        .bind(size)
        .bind(mtime)
        .bind(&fp)
        .bind(dir.id)
        .bind(&payload.rel_path)
        .execute(queue.db())
        .await?;

    // The bytes changed, so the old thumbnail name no longer matches.
    let _ = tokio::fs::remove_file(crate::services::thumbs::thumbnail_path(
        &full, &asset.fingerprint,
    ))
    .await;
    crate::services::thumbs::ensure_thumbnail(&full, &fp, asset.orientation as u32).await?;

    tracing::debug!(
        "compressed {} ({} -> {} bytes)",
        payload.rel_path,
        result.original_size,
        result.new_size
    );
    Ok(())
}
