//! Shared-token gate for the read-only data plane (`/api/nvr/*`).
//!
//! The phone has no user system — WireGuard used to be the only boundary, so
//! `/api/nvr/*` answered anyone who could reach the port (the LAN included, and
//! `GET /api/nvr/cameras` hands out the camera list). The admin can now set a
//! token in the web UI and the app sends it back verbatim.
//!
//! Two credentials are accepted, and both travel in headers — never in the URL,
//! which would leak them into proxy logs, browser history and `Referer`:
//!
//! * `X-Device-Token` – the shared token, sent by the app and by both players.
//! * `Authorization: Bearer <admin JWT>` – what the web monitor page uses for
//!   the same routes (hls.js can set request headers). Accepting it keeps one
//!   credential per caller instead of shipping the device token into the
//!   browser.
//!
//! An **empty** configured token means "no check", which is the pre-token
//! behaviour: an upgrade must never lock a phone out of its own server.

use axum::{
    body::Body,
    extract::State,
    http::{header, Request, StatusCode},
    middleware::Next,
    response::{IntoResponse, Response},
    Json,
};
use serde_json::json;

use crate::config::constant_time_eq;
use crate::middleware::admin_auth::Claims;
use crate::AppState;

/// Header the shared token is presented in.
pub const DEVICE_TOKEN_HEADER: &str = "x-device-token";

pub async fn require_device_token(
    State(state): State<AppState>,
    req: Request<Body>,
    next: Next,
) -> Response {
    // Read per request rather than caching: the admin can change the token from
    // the browser and the very next fragment request must already honour it.
    let configured = state.config.get().runtime.security.device_token.clone();
    if configured.is_empty() {
        return next.run(req).await;
    }

    let presented = req
        .headers()
        .get(DEVICE_TOKEN_HEADER)
        .and_then(|v| v.to_str().ok());
    if presented.is_some_and(|t| constant_time_eq(t.as_bytes(), configured.as_bytes())) {
        return next.run(req).await;
    }

    if has_valid_admin_jwt(&req, &state) {
        return next.run(req).await;
    }

    (
        StatusCode::UNAUTHORIZED,
        Json(json!({
            "error": "invalid or missing device token",
            "hint": "在手机上填写与后台「监控 → 手机访问令牌」一致的令牌",
        })),
    )
        .into_response()
}

/// `Authorization: Bearer <admin JWT>` — same check `/api/admin/*` does.
fn has_valid_admin_jwt(req: &Request<Body>, state: &AppState) -> bool {
    let Some(raw) = req
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
    else {
        return false;
    };
    let Some(token) = raw.strip_prefix("Bearer ") else {
        return false;
    };
    jsonwebtoken::decode::<Claims>(
        token,
        &jsonwebtoken::DecodingKey::from_secret(state.jwt_secret.as_bytes()),
        &jsonwebtoken::Validation::default(),
    )
    .is_ok()
}
