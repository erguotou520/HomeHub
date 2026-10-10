//! Admin authentication: single admin password -> JWT.

use axum::{
    async_trait,
    extract::FromRequestParts,
    http::{request::Parts, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use chrono::Utc;
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::AppState;

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct Claims {
    pub sub: String,
    pub role: String,
    pub exp: usize,
}

impl Claims {
    #[allow(dead_code)]
    pub fn is_admin(&self) -> bool {
        self.role == "admin"
    }
}

/// Axum extractor for `/api/admin/*` routes.
#[async_trait]
impl FromRequestParts<AppState> for Claims {
    type Rejection = AuthError;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, Self::Rejection> {
        // Header only. A `?token=` fallback used to be accepted for the NVR
        // media routes (`<video>` cannot send headers), but those routes are
        // now on the data plane and no longer authenticate at all, so the
        // fallback had no remaining caller — and URLs leak into proxy logs,
        // browser history and `Referer` headers anyway.
        let token = parts
            .headers
            .get(axum::http::header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .and_then(|h| h.strip_prefix("Bearer "))
            .map(|s| s.to_string())
            .ok_or(AuthError::MissingToken)?;

        decode::<Claims>(
            &token,
            &DecodingKey::from_secret(state.jwt_secret.as_bytes()),
            &Validation::default(),
        )
        .map(|d| d.claims)
        .map_err(|_| AuthError::InvalidToken)
    }
}

#[derive(Debug)]
pub enum AuthError {
    MissingToken,
    InvalidToken,
}

impl IntoResponse for AuthError {
    fn into_response(self) -> Response {
        let (status, msg) = match self {
            AuthError::MissingToken => (StatusCode::UNAUTHORIZED, "Missing authorization token"),
            AuthError::InvalidToken => (StatusCode::UNAUTHORIZED, "Invalid or expired token"),
        };
        (status, Json(json!({ "error": msg }))).into_response()
    }
}

/// Issue a token valid for `ttl_hours`.
pub fn create_token(secret: &str, ttl_hours: i64) -> Result<String, jsonwebtoken::errors::Error> {
    let exp = Utc::now()
        .checked_add_signed(chrono::Duration::hours(ttl_hours))
        .expect("valid timestamp")
        .timestamp() as usize;
    let claims = Claims {
        sub: "admin".into(),
        role: "admin".into(),
        exp,
    };
    encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(secret.as_bytes()),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const SECRET: &str = "unit-test-secret";

    #[test]
    fn token_roundtrip() {
        let token = create_token(SECRET, 12).unwrap();
        let decoded = decode::<Claims>(
            &token,
            &DecodingKey::from_secret(SECRET.as_bytes()),
            &Validation::default(),
        )
        .unwrap();
        assert!(decoded.claims.is_admin());
        assert_eq!(decoded.claims.sub, "admin");
    }

    #[test]
    fn wrong_secret_is_rejected() {
        let token = create_token(SECRET, 12).unwrap();
        assert!(decode::<Claims>(
            &token,
            &DecodingKey::from_secret(b"other"),
            &Validation::default()
        )
        .is_err());
    }
}
