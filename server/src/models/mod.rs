//! Core domain models.

pub mod alert;
pub mod dir;
pub mod peer;
pub mod photo;
pub mod task;
pub mod trash;

#[allow(unused_imports)]
pub use alert::AlertLevel;
#[allow(unused_imports)]
pub use dir::{DirMark, DirRecord};
#[allow(unused_imports)]
pub use peer::DiscoveredPeer;
#[allow(unused_imports)]
pub use photo::{DuplicateGroup, Face, GeoPoint, PersonGroup, PersonGroupSummary, PhotoAsset, PhotoItem, PhotoTag, TagSummary, TimelineGroup, TreeGroup};
#[allow(unused_imports)]
pub use task::{FileTaskPayload, QueueStatus, ScanDirPayload, Task, TaskKind, TaskStatus};
#[allow(unused_imports)]
pub use trash::{AuditLog, PeerTraffic, TrashEntry, WgPeer};

use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde_json::json;

/// Application level error that maps cleanly onto an HTTP response.
#[derive(Debug, thiserror::Error)]
pub enum AppError {
    #[error("not found: {0}")]
    NotFound(String),
    #[error("bad request: {0}")]
    BadRequest(String),
    #[error("unauthorized: {0}")]
    Unauthorized(String),
    #[allow(dead_code)]
    #[error("conflict: {0}")]
    Conflict(String),
    #[error("internal error: {0}")]
    Internal(String),
}

impl From<anyhow::Error> for AppError {
    fn from(e: anyhow::Error) -> Self {
        AppError::Internal(e.to_string())
    }
}

impl From<sqlx::Error> for AppError {
    fn from(e: sqlx::Error) -> Self {
        AppError::Internal(e.to_string())
    }
}

impl From<serde_json::Error> for AppError {
    fn from(e: serde_json::Error) -> Self {
        AppError::BadRequest(e.to_string())
    }
}

impl From<std::io::Error> for AppError {
    fn from(e: std::io::Error) -> Self {
        AppError::Internal(e.to_string())
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let status = match &self {
            AppError::NotFound(_) => StatusCode::NOT_FOUND,
            AppError::BadRequest(_) => StatusCode::BAD_REQUEST,
            AppError::Unauthorized(_) => StatusCode::UNAUTHORIZED,
            AppError::Conflict(_) => StatusCode::CONFLICT,
            AppError::Internal(_) => StatusCode::INTERNAL_SERVER_ERROR,
        };
        if status.is_server_error() {
            tracing::error!("request failed: {}", self);
        }
        (status, Json(json!({ "error": self.to_string() }))).into_response()
    }
}

#[allow(dead_code)]
pub type ApiResult<T> = Result<T, AppError>;
