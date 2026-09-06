//! Recycle bin API: browse, restore, purge.

use axum::{
    extract::{Path, State},
    Json, Router,
    routing::{delete, get, post},
};

use crate::models::AppError;
use crate::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/api/trash", get(list))
        .route("/api/trash/stats", get(stats))
        .route("/api/trash/:id/restore", post(restore))
        .route("/api/trash/:id", delete(purge))
        .route("/api/trash/empty", delete(purge_all))
}

pub async fn list(State(state): State<AppState>) -> Result<Json<serde_json::Value>, AppError> {
    let entries = crate::services::trash::list(&state.db).await?;
    Ok(Json(serde_json::json!({ "entries": entries })))
}

pub async fn stats(State(state): State<AppState>) -> Result<Json<serde_json::Value>, AppError> {
    let (count, bytes) = crate::services::trash::usage(&state.db).await?;
    let retention = state.config.get().runtime.trash.retention_days;
    Ok(Json(serde_json::json!({
        "count": count,
        "bytes": bytes,
        "retention_days": retention,
    })))
}

pub async fn restore(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> Result<Json<serde_json::Value>, AppError> {
    crate::services::trash::restore(&state.db, &state.registry, id).await?;
    Ok(Json(serde_json::json!({ "success": true })))
}

pub async fn purge(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> Result<Json<serde_json::Value>, AppError> {
    crate::services::trash::purge(&state.db, id).await?;
    Ok(Json(serde_json::json!({ "success": true })))
}

pub async fn purge_all(State(state): State<AppState>) -> Result<Json<serde_json::Value>, AppError> {
    let n = crate::services::trash::purge_all(&state.db).await?;
    Ok(Json(serde_json::json!({ "purged": n })))
}
