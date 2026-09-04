use axum::{
    body::Body,
    extract::{Path, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use serde::Deserialize;
use tokio::io::AsyncSeekExt;
use tokio_util::io::ReaderStream;

use crate::middleware::auth::Claims;
use crate::services::{media_service, thumbnail_service};
use crate::AppState;

#[derive(Deserialize)]
pub struct MediaPath {
    #[serde(rename = "module")]
    pub module: String,
    pub path: String,
}

// ─── Stream media with HTTP Range support ──────────────────────────────────

pub async fn stream_media(
    State(state): State<AppState>,
    headers: HeaderMap,
    claims: Claims,
    Path((module, path)): Path<(String, String)>,
) -> Response {
    let _ = claims;
    let full_path = match state.config.resolve_path(&module, &path) {
        Some(p) => p,
        None => {
            return (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({ "error": "File not found" })),
            )
                .into_response();
        }
    };

    if !full_path.exists() || !full_path.is_file() {
        return (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({ "error": "File not found" })),
        )
            .into_response();
    }

    let file_size = match tokio::fs::metadata(&full_path).await {
        Ok(m) => m.len(),
        Err(e) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({ "error": e.to_string() })),
            )
                .into_response();
        }
    };

    let content_type = mime_guess::from_path(&full_path)
        .first_or_octet_stream()
        .to_string();

    // Parse Range header
    if let Some(range_header) = headers.get(header::RANGE) {
        if let Ok(range_str) = range_header.to_str() {
            if let Some(range) = parse_range(range_str, file_size) {
                let (start, end) = range;
                let length = end - start + 1;

                let mut file = match tokio::fs::File::open(&full_path).await {
                    Ok(f) => f,
                    Err(e) => {
                        return (
                            StatusCode::INTERNAL_SERVER_ERROR,
                            Json(serde_json::json!({ "error": e.to_string() })),
                        )
                            .into_response();
                    }
                };

                if let Err(e) = file
                    .seek(std::io::SeekFrom::Start(start))
                    .await
                {
                    return (
                        StatusCode::INTERNAL_SERVER_ERROR,
                        Json(serde_json::json!({ "error": e.to_string() })),
                    )
                        .into_response();
                }

                let limited = tokio::io::AsyncReadExt::take(file, length);
                let stream = ReaderStream::new(limited);
                let body = Body::from_stream(stream);

                return Response::builder()
                    .status(StatusCode::PARTIAL_CONTENT)
                    .header(header::CONTENT_TYPE, content_type)
                    .header(header::CONTENT_LENGTH, length.to_string())
                    .header(
                        header::CONTENT_RANGE,
                        format!("bytes {}-{}/{}", start, end, file_size),
                    )
                    .header(header::ACCEPT_RANGES, "bytes")
                    .body(body)
                    .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response());
            }
        }
    }

    // Full file response
    let file = match tokio::fs::File::open(&full_path).await {
        Ok(f) => f,
        Err(e) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({ "error": e.to_string() })),
            )
                .into_response();
        }
    };

    let stream = ReaderStream::new(file);
    let body = Body::from_stream(stream);

    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, content_type)
        .header(header::CONTENT_LENGTH, file_size.to_string())
        .header(header::ACCEPT_RANGES, "bytes")
        .body(body)
        .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response())
}

fn parse_range(range_str: &str, file_size: u64) -> Option<(u64, u64)> {
    let prefix = "bytes=";
    if !range_str.starts_with(prefix) {
        return None;
    }
    let range_part = &range_str[prefix.len()..];
    let mut parts = range_part.splitn(2, '-');
    let start_str = parts.next()?;
    let end_str = parts.next().unwrap_or("");

    let start: u64 = if start_str.is_empty() {
        let suffix: u64 = end_str.parse().ok()?;
        file_size.saturating_sub(suffix)
    } else {
        start_str.parse().ok()?
    };

    let end: u64 = if end_str.is_empty() {
        file_size - 1
    } else {
        end_str.parse::<u64>().ok()?.min(file_size - 1)
    };

    if start > end {
        return None;
    }

    Some((start, end))
}

// ─── Thumbnail ───────────────────────────────────────────────────────────────

pub async fn get_thumbnail(
    State(state): State<AppState>,
    claims: Claims,
    Path((module, path)): Path<(String, String)>,
) -> Response {
    let _ = claims;
    let full_path = match state.config.resolve_path(&module, &path) {
        Some(p) => p,
        None => {
            return StatusCode::NOT_FOUND.into_response();
        }
    };

    match thumbnail_service::get_or_create_thumbnail(full_path.to_str().unwrap_or_default()).await {
        Ok(thumb_path) => match tokio::fs::read(&thumb_path).await {
            Ok(data) => Response::builder()
                .status(StatusCode::OK)
                .header(header::CONTENT_TYPE, "image/jpeg")
                .body(Body::from(data))
                .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response()),
            Err(_) => StatusCode::NOT_FOUND.into_response(),
        },
        Err(_) => StatusCode::NOT_FOUND.into_response(),
    }
}

// ─── Lyrics ──────────────────────────────────────────────────────────────────

pub async fn get_lyrics(
    State(state): State<AppState>,
    claims: Claims,
    Path((module, path)): Path<(String, String)>,
) -> Response {
    let _ = claims;
    let full_path = match state.config.resolve_path(&module, &path) {
        Some(p) => p,
        None => {
            return (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({ "error": "File not found" })),
            )
                .into_response();
        }
    };

    match media_service::get_lyrics(full_path.to_str().unwrap_or_default()).await {
        Ok(lyrics) => Json(serde_json::json!({ "lyrics": lyrics })).into_response(),
        Err(e) => (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({ "error": e.to_string() })),
        )
            .into_response(),
    }
}

// ─── Media info ──────────────────────────────────────────────────────────────

pub async fn get_media_info(
    State(state): State<AppState>,
    claims: Claims,
    Path((module, path)): Path<(String, String)>,
) -> Response {
    let _ = claims;
    let full_path = match state.config.resolve_path(&module, &path) {
        Some(p) => p,
        None => {
            return (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({ "error": "File not found" })),
            )
                .into_response();
        }
    };

    match media_service::get_media_info(full_path.to_str().unwrap_or_default()).await {
        Ok(info) => Json(info).into_response(),
        Err(e) => (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({ "error": e.to_string() })),
        )
            .into_response(),
    }
}
