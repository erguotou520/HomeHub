//! Resumable chunked upload.
//!
//! The mobile client splits a file into chunks and can resume an interrupted
//! transfer: `GET /api/upload/offset` reports how many bytes the server
//! already has for `<name>.part`, `POST /api/upload/chunk` appends the next
//! slice and `POST /api/upload/complete` turns the assembled file into the
//! final asset (dedup check + indexing + processing pipeline).

use axum::{
    body::Bytes,
    extract::{Query, State},
    routing::{get, post},
    Json, Router,
};
use serde::Deserialize;
use tokio::io::{AsyncSeekExt, AsyncWriteExt};

use crate::models::AppError;
use crate::services::files::UploadOutcome;
use crate::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/api/upload/offset", get(offset))
        .route("/api/upload/chunk", post(chunk))
        .route("/api/upload/complete", post(complete))
}

#[derive(Deserialize)]
pub struct PartQuery {
    pub dir: String,
    #[serde(default)]
    pub path: String,
    pub name: String,
}

#[derive(Deserialize)]
pub struct ChunkQuery {
    pub dir: String,
    #[serde(default)]
    pub path: String,
    pub name: String,
    pub offset: u64,
    pub total: Option<u64>,
}

#[derive(Deserialize)]
pub struct CompleteBody {
    pub dir: String,
    #[serde(default)]
    pub path: String,
    pub name: String,
    pub total: Option<u64>,
    /// "skip" discards the upload when a duplicate already exists.
    #[serde(default, alias = "on_duplicate")]
    pub on_duplicate: Option<String>,
}

fn sanitize(name: &str) -> String {
    name.replace(['/', '\\'], "_").replace("..", "_")
}

fn part_path(state: &AppState, dir: &str, path: &str, name: &str) -> Result<std::path::PathBuf, AppError> {
    let record = state
        .registry
        .by_name(dir)
        .ok_or_else(|| AppError::NotFound(format!("directory '{}' not found", dir)))?;
    let target_dir =
        crate::services::paths::safe_join(std::path::Path::new(&record.path), path)
            .ok_or_else(|| AppError::BadRequest("invalid path".into()))?;
    Ok(target_dir.join(format!("{}.homehub-part", sanitize(name))))
}

/// How many bytes of this upload have already been received.
pub async fn offset(
    State(state): State<AppState>,
    Query(q): Query<PartQuery>,
) -> Result<Json<serde_json::Value>, AppError> {
    let part = part_path(&state, &q.dir, &q.path, &q.name)?;
    let size = tokio::fs::metadata(&part).await.map(|m| m.len()).unwrap_or(0);
    Ok(Json(serde_json::json!({ "offset": size })))
}

/// Append one chunk (raw request body) to the `.part` file.
pub async fn chunk(
    State(state): State<AppState>,
    Query(q): Query<ChunkQuery>,
    body: Bytes,
) -> Result<Json<serde_json::Value>, AppError> {
    let part = part_path(&state, &q.dir, &q.path, &q.name)?;
    if let Some(parent) = part.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }

    let current = tokio::fs::metadata(&part).await.map(|m| m.len()).unwrap_or(0);
    if q.offset > current {
        return Err(AppError::BadRequest(format!(
            "gap in upload: server has {} bytes, client sent offset {}",
            current, q.offset
        )));
    }
    // A re-delivered chunk is truncated away and rewritten.
    let mut file = tokio::fs::OpenOptions::new()
        .create(true)
        .write(true)
        .open(&part)
        .await?;
    if q.offset < current {
        file.set_len(q.offset).await?;
    }
    file.seek(std::io::SeekFrom::End(0)).await?;
    file.write_all(&body).await?;
    file.flush().await?;
    drop(file);

    let written = tokio::fs::metadata(&part).await.map(|m| m.len()).unwrap_or(0);
    if let Some(total) = q.total {
        if written > total {
            tokio::fs::remove_file(&part).await.ok();
            return Err(AppError::BadRequest("upload larger than declared size".into()));
        }
    }
    Ok(Json(serde_json::json!({ "offset": written, "received": body.len() })))
}

/// Assemble the upload, run the dedup check and enqueue the pipeline.
pub async fn complete(
    State(state): State<AppState>,
    Json(body): Json<CompleteBody>,
) -> Result<Json<serde_json::Value>, AppError> {
    let record = state
        .registry
        .by_name(&body.dir)
        .ok_or_else(|| AppError::NotFound(format!("directory '{}' not found", body.dir)))?;
    let part = part_path(&state, &body.dir, &body.path, &body.name)?;
    if !part.exists() {
        return Err(AppError::NotFound("no upload in progress".into()));
    }

    let size = tokio::fs::metadata(&part).await.map(|m| m.len()).unwrap_or(0);
    if let Some(total) = body.total {
        if size != total {
            return Err(AppError::BadRequest(format!(
                "incomplete upload: {} of {} bytes",
                size, total
            )));
        }
    }

    // Read the assembled bytes so we can hash the *source* file for dedup.
    let data = tokio::fs::read(&part).await?;
    let config = state.config.get();
    let policy = crate::services::files::OnDuplicate::from_query(body.on_duplicate.as_ref());
    let outcome: UploadOutcome = crate::services::files::save_upload_bytes_with_policy(
        &state.db,
        &config,
        &state.registry,
        &state.queue,
        &record,
        &body.path,
        &sanitize(&body.name),
        &data,
        policy,
    )
    .await?;
    tokio::fs::remove_file(&part).await.ok();

    Ok(Json(serde_json::json!({ "uploaded": [outcome], "count": 1 })))
}
