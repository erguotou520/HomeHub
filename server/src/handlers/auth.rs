use axum::{
    extract::State,
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use serde::{Deserialize, Serialize};

use crate::middleware::auth::Claims;
use crate::services::auth_service;
use crate::AppState;

#[derive(Deserialize)]
pub struct LoginRequest {
    pub username: String,
    pub password: String,
}

#[derive(Serialize)]
pub struct LoginResponse {
    pub token: String,
    pub user: UserInfo,
}

#[derive(Serialize)]
pub struct UserInfo {
    pub id: i32,
    pub username: String,
    pub role: String,
}

#[derive(Deserialize)]
pub struct RegisterRequest {
    pub username: String,
    pub password: String,
    pub role: Option<String>,
}

pub async fn login(
    State(state): State<AppState>,
    Json(body): Json<LoginRequest>,
) -> impl IntoResponse {
    match auth_service::login(&state.pool, &body.username, &body.password, &state.jwt_secret).await
    {
        Ok((token, user)) => (
            StatusCode::OK,
            Json(serde_json::json!({
                "token": token,
                "user": {
                    "id": user.id,
                    "username": user.username,
                    "role": user.role,
                }
            })),
        )
            .into_response(),
        Err(e) => (
            StatusCode::UNAUTHORIZED,
            Json(serde_json::json!({ "error": e.to_string() })),
        )
            .into_response(),
    }
}

pub async fn register(
    State(state): State<AppState>,
    claims: Claims,
    Json(body): Json<RegisterRequest>,
) -> impl IntoResponse {
    if !claims.is_admin() {
        return (
            StatusCode::FORBIDDEN,
            Json(serde_json::json!({ "error": "Admin access required" })),
        )
            .into_response();
    }

    let role = body.role.as_deref().unwrap_or("user");
    match auth_service::register(&state.pool, &body.username, &body.password, role).await {
        Ok(user) => (
            StatusCode::CREATED,
            Json(serde_json::json!({
                "id": user.id,
                "username": user.username,
                "role": user.role,
            })),
        )
            .into_response(),
        Err(e) => (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": e.to_string() })),
        )
            .into_response(),
    }
}

pub async fn me(claims: Claims) -> impl IntoResponse {
    Json(serde_json::json!({
        "id": claims.sub,
        "username": claims.username,
        "role": claims.role,
    }))
}

