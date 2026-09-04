use axum::{
    extract::{Path, State},
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use serde::Deserialize;

use crate::middleware::auth::Claims;
use crate::services::user_service;
use crate::AppState;

#[derive(Deserialize)]
pub struct UpdateUserRequest {
    pub role: Option<String>,
    pub password: Option<String>,
}

// ─── List users (admin only) ──────────────────────────────────────────────

pub async fn list_users(
    State(state): State<AppState>,
    claims: Claims,
) -> impl IntoResponse {
    if !claims.is_admin() {
        return (
            StatusCode::FORBIDDEN,
            Json(serde_json::json!({ "error": "Admin access required" })),
        )
            .into_response();
    }

    match user_service::list_users(&state.pool).await {
        Ok(users) => Json(users).into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({ "error": e.to_string() })),
        )
            .into_response(),
    }
}

// ─── Update user (admin only) ─────────────────────────────────────────────

pub async fn update_user(
    State(state): State<AppState>,
    claims: Claims,
    Path(user_id): Path<i32>,
    Json(body): Json<UpdateUserRequest>,
) -> impl IntoResponse {
    if !claims.is_admin() {
        return (
            StatusCode::FORBIDDEN,
            Json(serde_json::json!({ "error": "Admin access required" })),
        )
            .into_response();
    }

    match user_service::update_user(
        &state.pool,
        user_id,
        body.role.as_deref(),
        body.password.as_deref(),
    )
    .await
    {
        Ok(user) => Json(user).into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({ "error": e.to_string() })),
        )
            .into_response(),
    }
}

// ─── Delete user (admin only) ─────────────────────────────────────────────

pub async fn delete_user(
    State(state): State<AppState>,
    claims: Claims,
    Path(user_id): Path<i32>,
) -> impl IntoResponse {
    if !claims.is_admin() {
        return (
            StatusCode::FORBIDDEN,
            Json(serde_json::json!({ "error": "Admin access required" })),
        )
            .into_response();
    }

    if user_id == claims.sub {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "Cannot delete yourself" })),
        )
            .into_response();
    }

    match user_service::delete_user(&state.pool, user_id).await {
        Ok(_) => Json(serde_json::json!({ "success": true })).into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({ "error": e.to_string() })),
        )
            .into_response(),
    }
}
