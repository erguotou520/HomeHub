//! NAS file API: browse, upload, download, rename, copy, move, delete.
//!
//! Addresses are `<dir-name>/<relative/path>`. Deletes are soft (recycle bin).

use axum::{
    body::Body,
    extract::{Multipart, Path, Query, State},
    http::{header, StatusCode},
    response::{IntoResponse, Response},
    Json, Router,
    routing::{delete, get, patch, post, put},
};
use serde::Deserialize;
use tokio_util::io::ReaderStream;

use crate::models::AppError;
use crate::services::files::{SortKey, UploadOutcome};
use crate::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        // Directory browsing
        .route("/api/dirs", get(list_dirs))
        .route("/api/files/:dir", get(list_root))
        .route("/api/files/:dir/*path", get(list_or_download))
        // Mutations
        .route("/api/files/:dir/*path", post(upload_path))
        .route("/api/files/:dir", post(upload_root))
        .route("/api/files/:dir/*path", patch(patch_file))
        .route("/api/files/:dir/*path", delete(delete_path))
        .route("/api/mkdir/:dir", post(mkdir_root))
        .route("/api/mkdir/:dir/*path", post(mkdir))
        // Plain text documents
        .route("/api/documents/:dir/*path", get(read_document))
        .route("/api/documents/:dir/*path", put(write_document))
        // Media
        .route("/api/media/:dir/*path", get(media))
        .route("/api/media/lyrics/:dir/*path", get(lyrics))
        .route("/api/media/info/:dir/*path", get(media_info))
}

// ──────────────────────────────── query types ──────────────────────────────

#[derive(Deserialize, Default)]
pub struct ListQuery {
    pub sort: Option<String>,
    pub desc: Option<bool>,
}

#[derive(Deserialize)]
pub struct MkdirBody {
    pub name: String,
}

#[derive(Deserialize)]
pub struct RenameBody {
    pub name: String,
}

#[derive(Deserialize)]
pub struct CopyMoveBody {
    /// Target directory name.
    pub to_dir: String,
    /// Target relative path (including the new file name).
    pub to_path: String,
}

#[derive(Deserialize)]
pub struct WriteDocumentBody {
    pub content: String,
}

// ───────────────────────────────── handlers ────────────────────────────────

/// Directories available to this client (all registered dirs, regardless of marks).
pub async fn list_dirs(State(state): State<AppState>) -> Result<Json<serde_json::Value>, AppError> {
    let dirs: Vec<serde_json::Value> = state
        .registry
        .enabled()
        .into_iter()
        .map(|d| {
            serde_json::json!({
                "id": d.id,
                "name": d.name,
                "marks": d.marks_vec(),
                "is_dir": true,
            })
        })
        .collect();
    Ok(Json(serde_json::json!({ "entries": dirs })))
}

pub async fn list_root(
    State(state): State<AppState>,
    Path(dir): Path<String>,
    Query(q): Query<ListQuery>,
) -> Result<Json<serde_json::Value>, AppError> {
    list_dir_contents(&state, &dir, "", &q).await
}

pub async fn list_or_download(
    State(state): State<AppState>,
    Path((dir, path)): Path<(String, String)>,
    Query(q): Query<ListQuery>,
) -> Result<Response, AppError> {
    let record = state
        .registry
        .by_name(&dir)
        .ok_or_else(|| AppError::NotFound(format!("directory '{}' not found", dir)))?;
    let full = crate::services::paths::safe_join(std::path::Path::new(&record.path), &path)
        .ok_or_else(|| AppError::BadRequest("invalid path".into()))?;

    if full.is_dir() {
        let json = list_dir_contents(&state, &dir, &path, &q).await?;
        return Ok(json.into_response());
    }
    Ok(stream_file(&full).await?.into_response())
}

async fn list_dir_contents(
    state: &AppState,
    dir: &str,
    rel: &str,
    q: &ListQuery,
) -> Result<Json<serde_json::Value>, AppError> {
    let record = state
        .registry
        .by_name(dir)
        .ok_or_else(|| AppError::NotFound(format!("directory '{}' not found", dir)))?;
    let entries = crate::services::files::list_directory(
        &record,
        rel,
        SortKey::parse(q.sort.as_deref()),
        q.desc.unwrap_or(false),
    )
    .await?;
    Ok(Json(serde_json::json!({
        "entries": entries,
        "total": entries.len(),
        "dir": dir,
        "path": rel,
    })))
}

/// Stream a file with HTTP Range support.
async fn stream_file(full: &std::path::Path) -> Result<Response, AppError> {
    use tokio::io::AsyncSeekExt;

    if !full.is_file() {
        return Err(AppError::NotFound("file not found".into()));
    }
    let file_size = tokio::fs::metadata(full)
        .await
        .map(|m| m.len())
        .unwrap_or(0);
    let content_type = mime_guess::from_path(full)
        .first_or_octet_stream()
        .to_string();

    let mut file = tokio::fs::File::open(full)
        .await
        .map_err(|e| AppError::Internal(e.to_string()))?;
    let _ = file.seek(std::io::SeekFrom::Start(0)).await;
    drop(file);

    let file = tokio::fs::File::open(full)
        .await
        .map_err(|e| AppError::Internal(e.to_string()))?;
    let stream = ReaderStream::new(file);
    let body = Body::from_stream(stream);

    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, content_type)
        .header(header::CONTENT_LENGTH, file_size)
        .header(header::ACCEPT_RANGES, "bytes")
        .header(header::CACHE_CONTROL, "private, max-age=86400")
        .body(body)
        .map_err(|e| AppError::Internal(e.to_string()))
}

/// Upload into the root of a registered directory.
pub async fn upload_root(
    State(state): State<AppState>,
    Query(params): Query<std::collections::HashMap<String, String>>,
    Path(dir): Path<String>,
    multipart: Multipart,
) -> Result<Json<serde_json::Value>, AppError> {
    upload_into(&state, &dir, "", params, multipart).await
}

/// Upload into `<dir>/<path>`.
pub async fn upload_path(
    State(state): State<AppState>,
    Query(params): Query<std::collections::HashMap<String, String>>,
    Path((dir, path)): Path<(String, String)>,
    multipart: Multipart,
) -> Result<Json<serde_json::Value>, AppError> {
    upload_into(&state, &dir, &path, params, multipart).await
}

async fn upload_into(
    state: &AppState,
    dir: &str,
    rel: &str,
    params: std::collections::HashMap<String, String>,
    mut multipart: Multipart,
) -> Result<Json<serde_json::Value>, AppError> {
    let record = state
        .registry
        .by_name(dir)
        .ok_or_else(|| AppError::NotFound(format!("directory '{}' not found", dir)))?;
    let config = state.config.get();
    let policy =
        crate::services::files::OnDuplicate::from_query(params.get("on-duplicate").or_else(|| params.get("on_duplicate")));
    let mut outcomes: Vec<UploadOutcome> = Vec::new();

    while let Some(field) = multipart
        .next_field()
        .await
        .map_err(|e| AppError::BadRequest(e.to_string()))?
    {
        let name = field
            .file_name()
            .unwrap_or("upload.bin")
            .to_string();
        let data = field
            .bytes()
            .await
            .map_err(|e| AppError::BadRequest(e.to_string()))?;
        let outcome = crate::services::files::save_upload_bytes_with_policy(
            &state.db,
            &config,
            &state.registry,
            &state.queue,
            &record,
            rel,
            &name,
            &data,
            policy,
        )
        .await?;
        outcomes.push(outcome);
    }

    Ok(Json(serde_json::json!({
        "uploaded": outcomes,
        "count": outcomes.len(),
    })))
}

/// Rename (or copy / move) a path, depending on the body.
pub async fn patch_file(
    State(state): State<AppState>,
    Path((dir, path)): Path<(String, String)>,
    Json(body): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, AppError> {
    let record = state
        .registry
        .by_name(&dir)
        .ok_or_else(|| AppError::NotFound(format!("directory '{}' not found", dir)))?;

    let op = body
        .get("op")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string());
    if let Some(op) = op {
        let target: CopyMoveBody =
            serde_json::from_value(body).map_err(|e| AppError::BadRequest(e.to_string()))?;
        let to_dir = state
            .registry
            .by_name(&target.to_dir)
            .ok_or_else(|| AppError::NotFound(format!("directory '{}' not found", target.to_dir)))?;
        match op.as_str() {
            "copy" => {
                crate::services::files::copy(
                    &state.registry,
                    (&record, &path),
                    (&to_dir, &target.to_path),
                )
                .await?
            }
            "move" => {
                crate::services::files::move_path(
                    &state.registry,
                    (&record, &path),
                    (&to_dir, &target.to_path),
                )
                .await?
            }
            other => return Err(AppError::BadRequest(format!("unsupported op {}", other))),
        }
        state
            .queue
            .enqueue_scan(to_dir.id, false, crate::services::tasks::PRIORITY_HIGH)
            .await?;
        return Ok(Json(serde_json::json!({ "success": true, "op": op })));
    }

    let body: RenameBody =
        serde_json::from_value(body).map_err(|e| AppError::BadRequest(e.to_string()))?;
    let new_rel = crate::services::files::rename(&record, &path, &body.name).await?;
    let old_dir_rel = path.clone();
    let _ = old_dir_rel;

    // Keep the indexes in sync.
    if let Ok(Some(photo)) =
        crate::services::photos::get_path(&state.db, record.id, &path).await
    {
        sqlx::query("UPDATE photo_assets SET rel_path = ?, updated_at = ? WHERE id = ?")
            .bind(&new_rel)
            .bind(crate::db::now())
            .bind(photo.id)
            .execute(&state.db)
            .await?;
    }
    state
        .queue
        .enqueue_scan(record.id, false, crate::services::tasks::PRIORITY_HIGH)
        .await?;
    Ok(Json(serde_json::json!({ "success": true, "path": new_rel })))
}

/// Soft delete: the path lands in the recycle bin.
pub async fn delete_path(
    State(state): State<AppState>,
    Path((dir, path)): Path<(String, String)>,
) -> Result<Json<serde_json::Value>, AppError> {
    let record = state
        .registry
        .by_name(&dir)
        .ok_or_else(|| AppError::NotFound(format!("directory '{}' not found", dir)))?;
    let config = state.config.get();
    let full = crate::services::paths::safe_join(std::path::Path::new(&record.path), &path)
        .ok_or_else(|| AppError::BadRequest("invalid path".into()))?;
    if !full.exists() {
        return Err(AppError::NotFound("path not found".into()));
    }
    let is_dir = full.is_dir();
    let entry =
        crate::services::trash::move_to_trash(&state.db, &config, &record, &path, is_dir).await?;

    // Everything below the deleted path is gone from the index too.
    if is_dir {
        let prefix = format!("{}/", path.trim_end_matches('/'));
        sqlx::query("DELETE FROM file_index WHERE dir_id = ? AND (rel_path = ? OR rel_path LIKE ?)")
            .bind(record.id)
            .bind(&path)
            .bind(format!("{}%", prefix))
            .execute(&state.db)
            .await?;
        let photos: Vec<(i64, String)> = sqlx::query_as(
            "SELECT id, rel_path FROM photo_assets WHERE dir_id = ? AND (rel_path = ? OR rel_path LIKE ?)",
        )
        .bind(record.id)
        .bind(&path)
        .bind(format!("{}%", prefix))
        .fetch_all(&state.db)
        .await?;
        for (id, rel) in photos {
            crate::services::search::remove(&state.db, "photo", &id.to_string()).await?;
            crate::services::search::remove(&state.db, "file", &format!("{}:{}", record.id, rel))
                .await?;
            crate::services::photos::delete_photo(&state.db, id).await?;
        }
    } else {
        if let Ok(Some(photo)) =
            crate::services::photos::get_path(&state.db, record.id, &path).await
        {
            crate::services::search::remove(&state.db, "photo", &photo.id.to_string()).await?;
            crate::services::photos::delete_photo(&state.db, photo.id).await?;
        }
        sqlx::query("DELETE FROM file_index WHERE dir_id = ? AND rel_path = ?")
            .bind(record.id)
            .bind(&path)
            .execute(&state.db)
            .await?;
    }

    Ok(Json(serde_json::json!({ "success": true, "trash_id": entry.id })))
}

pub async fn mkdir_root(
    State(state): State<AppState>,
    Path(dir): Path<String>,
    Json(body): Json<MkdirBody>,
) -> Result<Json<serde_json::Value>, AppError> {
    mkdir_in(&state, &dir, "", &body).await
}

pub async fn mkdir(
    State(state): State<AppState>,
    Path((dir, path)): Path<(String, String)>,
    Json(body): Json<MkdirBody>,
) -> Result<Json<serde_json::Value>, AppError> {
    mkdir_in(&state, &dir, &path, &body).await
}

async fn mkdir_in(
    state: &AppState,
    dir: &str,
    rel: &str,
    body: &MkdirBody,
) -> Result<Json<serde_json::Value>, AppError> {
    let record = state
        .registry
        .by_name(dir)
        .ok_or_else(|| AppError::NotFound(format!("directory '{}' not found", dir)))?;
    crate::services::files::create_dir(&record, rel, &body.name).await?;
    Ok(Json(serde_json::json!({ "success": true, "name": body.name })))
}

pub async fn read_document(
    State(state): State<AppState>,
    Path((dir, path)): Path<(String, String)>,
) -> Result<Json<serde_json::Value>, AppError> {
    let record = state
        .registry
        .by_name(&dir)
        .ok_or_else(|| AppError::NotFound("directory not found".into()))?;
    let full = crate::services::paths::safe_join(std::path::Path::new(&record.path), &path)
        .ok_or_else(|| AppError::BadRequest("invalid path".into()))?;
    // Cap the preview at 1 MiB.
    let content = tokio::fs::read(full)
        .await
        .map_err(|e| AppError::NotFound(e.to_string()))?;
    let text = String::from_utf8_lossy(&content[..content.len().min(1024 * 1024)]).to_string();
    Ok(Json(serde_json::json!({ "content": text })))
}

pub async fn write_document(
    State(state): State<AppState>,
    Path((dir, path)): Path<(String, String)>,
    Json(body): Json<WriteDocumentBody>,
) -> Result<Json<serde_json::Value>, AppError> {
    let record = state
        .registry
        .by_name(&dir)
        .ok_or_else(|| AppError::NotFound("directory not found".into()))?;
    let full = crate::services::paths::safe_join(std::path::Path::new(&record.path), &path)
        .ok_or_else(|| AppError::BadRequest("invalid path".into()))?;
    if let Some(parent) = full.parent() {
        tokio::fs::create_dir_all(parent).await.ok();
    }
    tokio::fs::write(&full, body.content.as_bytes())
        .await
        .map_err(|e| AppError::Internal(e.to_string()))?;
    Ok(Json(serde_json::json!({ "success": true })))
}

// ─────────────────────────────────── media ─────────────────────────────────

/// `/api/media/:dir/*path` – original file, `?size=thumb` for the 256px variant.
pub async fn media(
    State(state): State<AppState>,
    Query(q): Query<std::collections::HashMap<String, String>>,
    Path((dir, path)): Path<(String, String)>,
) -> Result<Response, AppError> {
    let record = state
        .registry
        .by_name(&dir)
        .ok_or_else(|| AppError::NotFound(format!("directory '{}' not found", dir)))?;
    let full = crate::services::paths::safe_join(std::path::Path::new(&record.path), &path)
        .ok_or_else(|| AppError::BadRequest("invalid path".into()))?;
    if !full.exists() {
        // The index can lag behind the filesystem by one scan cycle.
        return Err(AppError::NotFound("file no longer exists".into()));
    }

    let wants_thumb = q.get("size").map(|s| s == "thumb").unwrap_or(false);
    if wants_thumb {
        // Videos have no thumbnail pipeline in v1: fall back to a poster image
        // sitting next to the file, otherwise serve the original.
        if !crate::services::paths::is_image(
            &crate::services::paths::ext_of(&path),
        ) {
            if let Some(poster) = crate::services::thumbs::video_poster(&full).await {
                return Ok(stream_file(&poster).await?.into_response());
            }
            return Ok(stream_file(&full).await?.into_response());
        }
        let photo = crate::services::photos::get_path(&state.db, record.id, &path).await?;
        let (fp, orientation) = match &photo {
            Some(p) => (
                crate::services::paths::fingerprint(p.mtime, p.size as u64),
                p.orientation as u32,
            ),
            None => {
                let meta = std::fs::metadata(&full).ok();
                let mtime = meta
                    .as_ref()
                    .and_then(|m| m.modified().ok())
                    .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                    .map(|d| d.as_secs() as i64)
                    .unwrap_or(0);
                let size = meta.map(|m| m.len()).unwrap_or(0);
                (crate::services::paths::fingerprint(mtime, size), 1)
            }
        };
        let thumb = crate::services::thumbs::ensure_thumbnail(&full, &fp, orientation)
            .await
            .map_err(|e| AppError::Internal(e.to_string()))?;
        return Ok(stream_file(&thumb).await?.into_response());
    }

    Ok(stream_file(&full).await?.into_response())
}

pub async fn lyrics(
    State(state): State<AppState>,
    Path((dir, path)): Path<(String, String)>,
) -> Result<Json<serde_json::Value>, AppError> {
    let record = state
        .registry
        .by_name(&dir)
        .ok_or_else(|| AppError::NotFound("directory not found".into()))?;
    let full = crate::services::paths::safe_join(std::path::Path::new(&record.path), &path)
        .ok_or_else(|| AppError::BadRequest("invalid path".into()))?;
    let lyrics = crate::services::media_service::get_lyrics(&full.to_string_lossy())
        .await
        .map_err(|e| AppError::NotFound(e.to_string()))?;
    Ok(Json(serde_json::json!({ "lyrics": lyrics })))
}

pub async fn media_info(
    State(state): State<AppState>,
    Path((dir, path)): Path<(String, String)>,
) -> Result<Json<serde_json::Value>, AppError> {
    let record = state
        .registry
        .by_name(&dir)
        .ok_or_else(|| AppError::NotFound("directory not found".into()))?;
    let full = crate::services::paths::safe_join(std::path::Path::new(&record.path), &path)
        .ok_or_else(|| AppError::BadRequest("invalid path".into()))?;
    let info = crate::services::media_service::get_media_info(&full.to_string_lossy())
        .await
        .map_err(|e| AppError::NotFound(e.to_string()))?;
    Ok(Json(serde_json::to_value(info).unwrap_or_default()))
}
