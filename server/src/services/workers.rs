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
    crate::services::thumbs::ensure_thumbnail(&full, &fp, asset.orientation as u32).await?;
    let _ = dir;
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

    if !outcome.tags.is_empty() {
        let tags: Vec<(String, f64)> = outcome
            .tags
            .into_iter()
            .map(|d| (d.tag, d.confidence as f64))
            .collect();
        crate::services::photos::replace_tags(queue.db(), asset.id, kind_str, &tags).await?;
    }

    if !outcome.faces.is_empty() {
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
            let _res = sqlx::query(
                "INSERT INTO faces (photo_id, box_x, box_y, box_w, box_h, phash, created_at) \
                 VALUES (?, ?, ?, ?, ?, ?, ?)",
            )
            .bind(photo_id)
            .bind(face.x as f64)
            .bind(face.y as f64)
            .bind(face.w as f64)
            .bind(face.h as f64)
            .bind(&face.hash)
            .bind(now)
            .execute(&db)
            .await?;
            let face_id: (i64,) = sqlx::query_as("SELECT last_insert_rowid()")
                .fetch_one(&db)
                .await?;
            if !face.hash.is_empty() {
                crate::services::ml::cluster::assign_group(&db, face_id.0, &face.hash, &face_cfg)
                    .await?;
            }
        }
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
