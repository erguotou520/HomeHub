//! Image editing endpoints for the file manager.
//!
//! `POST /api/images/:dir/*path/transform` applies rotate / flip / resize steps
//! to any image in a registered directory (not just indexed album photos).
//! `GET  /api/images/:dir/*path/exif` returns the metadata panel.

use axum::{
    extract::{Path, State},
    routing::{get, post},
    Json, Router,
};
use serde_json::{json, Value};

use crate::models::AppError;
use crate::AppState;

// NOTE: axum only allows a catch-all (`*path`) as the last segment, so the
// action has to come before the directory name.
pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/api/images/transform/:dir/*path", post(transform))
        .route("/api/images/restore/:dir/*path", post(restore))
        .route("/api/images/exif/:dir/*path", get(exif))
}

#[derive(serde::Deserialize)]
pub struct TransformBody {
    /// Ordered list of `{"op": ...}` steps, applied in order.
    pub ops: Vec<Value>,
}

/// Resolve `<dir>/<path>` to an on-disk location, rejecting escapes.
fn resolve(
    state: &AppState,
    dir: &str,
    path: &str,
) -> Result<(crate::models::dir::DirRecord, std::path::PathBuf), AppError> {
    let record = state
        .registry
        .by_name(dir)
        .ok_or_else(|| AppError::NotFound(format!("directory '{}' not found", dir)))?;
    let full = crate::services::paths::safe_join(std::path::Path::new(&record.path), path)
        .ok_or_else(|| AppError::BadRequest("invalid path".into()))?;
    if !full.starts_with(&record.path) {
        return Err(AppError::BadRequest("path escapes directory".into()));
    }
    Ok((record.clone(), full))
}

/// Invalidate the cached thumbnail and re-scan so indexes catch up.
async fn after_edit(state: &AppState, record: &crate::models::dir::DirRecord, full: &std::path::Path) {
    if let Ok(meta) = std::fs::metadata(full) {
        if let Ok(mtime) = meta.modified() {
            let secs = mtime
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0);
            let fp = crate::services::paths::fingerprint(secs as i64, meta.len());
            let _ = tokio::fs::remove_file(crate::services::thumbs::thumbnail_path(full, &fp)).await;
        }
    }
    let _ = state
        .queue
        .enqueue_scan(record.id, false, crate::services::tasks::PRIORITY_HIGH)
        .await;
}

pub async fn transform(
    State(state): State<AppState>,
    Path((dir, path)): Path<(String, String)>,
    Json(body): Json<TransformBody>,
) -> Result<Json<Value>, AppError> {
    let ops = crate::services::image_ops::parse_ops(&body.ops)
        .map_err(|e| AppError::BadRequest(e.to_string()))?;
    let (record, full) = resolve(&state, &dir, &path)?;

    let config = state.config.get();
    let result = crate::services::image_ops::transform(&full, &record, &path, &ops, &config)
        .await
        .map_err(|e| AppError::BadRequest(e.to_string()))?;

    after_edit(&state, &record, &full).await;
    Ok(Json(json!({
        "success": true,
        "result": result,
        "has_original": crate::services::image_ops::has_original(&record, &path, &config),
    })))
}

/// Roll the current pixels back to the archived original.
pub async fn restore(
    State(state): State<AppState>,
    Path((dir, path)): Path<(String, String)>,
) -> Result<Json<Value>, AppError> {
    let (record, full) = resolve(&state, &dir, &path)?;
    let config = state.config.get();
    let result = crate::services::image_ops::restore_original(&full, &record, &path, &config)
        .await
        .map_err(|e| AppError::BadRequest(e.to_string()))?;

    after_edit(&state, &record, &full).await;
    Ok(Json(json!({ "success": true, "result": result })))
}

/// Full metadata panel: EXIF, dimensions, orientation, GPS.
pub async fn exif(
    State(state): State<AppState>,
    Path((dir, path)): Path<(String, String)>,
) -> Result<Json<Value>, AppError> {
    let (record, full) = resolve(&state, &dir, &path)?;
    let config = state.config.get();
    let meta = crate::services::exif::read_metadata(&full)
        .map_err(|e| AppError::BadRequest(e.to_string()))?;
    let (w, h) = image::image_dimensions(&full).unwrap_or((0, 0));
    let size = std::fs::metadata(&full).map(|m| m.len()).unwrap_or(0);

    Ok(Json(json!({
        "path": path,
        "dir": dir,
        "name": full.file_name().map(|n| n.to_string_lossy().to_string()),
        "width": if w > 0 { Some(w as i64) } else { meta.width },
        "height": if h > 0 { Some(h as i64) } else { meta.height },
        "size": size,
        "orientation": meta.orientation,
        "taken_at": meta.taken_at,
        "gps_lat": meta.gps_lat,
        "gps_lng": meta.gps_lng,
        "camera_make": meta.camera_make,
        "camera_model": meta.camera_model,
        "has_original": crate::services::image_ops::has_original(&record, &path, &config),
    })))
}
