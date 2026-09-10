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
///
/// The new geometry is also written straight into `photo_assets`. The scan
/// below is asynchronous and lands a few hundred milliseconds later, so a
/// client refetching right after an edit would otherwise still be served the
/// pre-edit width/height/size (visible as a stale info panel / thumbnail).
///
/// `mtime` + `fingerprint` are fast-pathed for the same reason and one more:
/// every media URL carries the fingerprint as a cache token, so returning the
/// edit response before the scan has run would hand the client the *old*
/// URL — and the client would happily render the pre-edit pixels again.
/// `file_hash` / `pixel_hash` still come from the scan.
async fn after_edit(
    state: &AppState,
    record: &crate::models::dir::DirRecord,
    full: &std::path::Path,
    rel_path: &str,
    geometry: (Option<u32>, Option<u32>, i64),
) {
    // Same `mtime:size` recipe the scanner uses (scan.rs), so the fast path
    // and the following scan agree on the value.
    let mut stamp: Option<(i64, String)> = None;
    if let Ok(meta) = std::fs::metadata(full) {
        if let Ok(mtime) = meta.modified() {
            let secs = mtime
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0) as i64;
            let fp = crate::services::paths::fingerprint(secs, meta.len());
            let _ = tokio::fs::remove_file(crate::services::thumbs::thumbnail_path(full, &fp)).await;
            stamp = Some((secs, fp));
        }
    }

    let (width, height, size) = geometry;
    let rel = crate::services::paths::normalize_rel(rel_path);
    let (mtime, fingerprint) = stamp.unwrap_or((0, String::new()));
    let _ = sqlx::query(
        "UPDATE photo_assets SET width = COALESCE(?, width), \
             height = COALESCE(?, height), size = ?, \
             mtime = CASE WHEN ? > 0 THEN ? ELSE mtime END, \
             fingerprint = CASE WHEN ? <> '' THEN ? ELSE fingerprint END, \
             updated_at = ? \
         WHERE dir_id = ? AND rel_path = ?",
    )
    .bind(width.map(|v| v as i64))
    .bind(height.map(|v| v as i64))
    .bind(size)
    .bind(mtime)
    .bind(mtime)
    .bind(&fingerprint)
    .bind(&fingerprint)
    .bind(crate::db::now())
    .bind(record.id)
    .bind(&rel)
    .execute(&state.db)
    .await;

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

    after_edit(&state, &record, &full, &path, (result.width, result.height, result.size)).await;
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

    after_edit(&state, &record, &full, &path, (result.width, result.height, result.size)).await;
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
