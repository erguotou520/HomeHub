use axum::{
    extract::{Path, State},
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use serde::Deserialize;

use crate::middleware::auth::Claims;
use crate::services::share_service::{self, ShareInfo};
use crate::AppState;

#[derive(Deserialize)]
pub struct CreateShareRequest {
    pub file_path: String,
    pub app_type: String,
    pub expires_in_hours: Option<i64>,
    pub burn_after_read: Option<bool>,
    pub max_views: Option<i32>,
}

// ─── Create share ─────────────────────────────────────────────────────────

pub async fn create_share(
    State(state): State<AppState>,
    claims: Claims,
    Json(body): Json<CreateShareRequest>,
) -> impl IntoResponse {
    match share_service::create_share(
        &state.pool,
        claims.sub,
        &body.file_path,
        &body.app_type,
        body.expires_in_hours,
        body.burn_after_read.unwrap_or(false),
        body.max_views,
    )
    .await
    {
        Ok(share) => {
            let mut info = ShareInfo::from(share);
            info.url = Some(format!("{}/s/{}", state.web_url, info.token));
            (StatusCode::CREATED, Json(info)).into_response()
        }
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({ "error": e.to_string() })),
        )
            .into_response(),
    }
}

// ─── List shares ───────────────────────────────────────────────────────────

pub async fn list_shares(
    State(state): State<AppState>,
    claims: Claims,
) -> impl IntoResponse {
    match share_service::list_user_shares(&state.pool, claims.sub).await {
        Ok(mut shares) => {
            for share in &mut shares {
                share.url = Some(format!("{}/s/{}", state.web_url, share.token));
            }
            Json(shares).into_response()
        }
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({ "error": e.to_string() })),
        )
            .into_response(),
    }
}

// ─── Delete share ──────────────────────────────────────────────────────────

pub async fn delete_share(
    State(state): State<AppState>,
    claims: Claims,
    Path(share_id): Path<i32>,
) -> impl IntoResponse {
    match share_service::delete_share(&state.pool, share_id, claims.sub).await {
        Ok(_) => Json(serde_json::json!({ "success": true })).into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({ "error": e.to_string() })),
        )
            .into_response(),
    }
}

// ─── Access share (public, no auth) ────────────────────────────────────────

pub async fn access_share(
    State(state): State<AppState>,
    Path(token): Path<String>,
) -> impl IntoResponse {
    match share_service::access_share(&state.pool, &token).await {
        Ok(file_info) => Json(file_info).into_response(),
        Err(e) => (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({ "error": e.to_string() })),
        )
            .into_response(),
    }
}
