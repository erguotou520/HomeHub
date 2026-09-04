use axum::{
    async_trait,
    extract::FromRequestParts,
    http::{request::Parts, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use jsonwebtoken::{decode, DecodingKey, Validation};
use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::AppState;

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct Claims {
    pub sub: i32,       // user id
    pub username: String,
    pub role: String,
    pub exp: usize,
}

impl Claims {
    pub fn is_admin(&self) -> bool {
        self.role == "admin"
    }
}

/// Axum extractor: pulls JWT Claims from Authorization header or ?token query param.
#[async_trait]
impl FromRequestParts<AppState> for Claims {
    type Rejection = AuthError;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, Self::Rejection> {
        // Try Authorization header first
        let token_str = if let Some(auth_header) = parts
            .headers
            .get(axum::http::header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
        {
            auth_header
                .strip_prefix("Bearer ")
                .ok_or(AuthError::InvalidToken)?
                .to_string()
        } else {
            // Fall back to ?token query parameter (for <img src> and <video src>)
            let query = parts.uri.query().unwrap_or("");
            url_token_from_query(query).ok_or(AuthError::MissingToken)?
        };

        let token_data = decode::<Claims>(
            &token_str,
            &DecodingKey::from_secret(state.jwt_secret.as_bytes()),
            &Validation::default(),
        )
        .map_err(|_| AuthError::InvalidToken)?;

        Ok(token_data.claims)
    }
}

fn url_token_from_query(query: &str) -> Option<String> {
    for part in query.split('&') {
        if let Some(val) = part.strip_prefix("token=") {
            return Some(val.to_string());
        }
    }
    None
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

pub fn create_token(
    user_id: i32,
    username: &str,
    role: &str,
    secret: &str,
) -> Result<String, jsonwebtoken::errors::Error> {
    use jsonwebtoken::{encode, EncodingKey, Header};

    let expiration = chrono::Utc::now()
        .checked_add_signed(chrono::Duration::hours(24 * 7))
        .expect("valid timestamp")
        .timestamp() as usize;

    let claims = Claims {
        sub: user_id,
        username: username.to_string(),
        role: role.to_string(),
        exp: expiration,
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
    use jsonwebtoken::{decode, DecodingKey, Validation};

    const SECRET: &str = "test_secret_key";

    #[test]
    fn test_create_and_decode_token() {
        let token = create_token(42, "alice", "admin", SECRET).unwrap();
        assert!(!token.is_empty());

        let token_data = decode::<Claims>(
            &token,
            &DecodingKey::from_secret(SECRET.as_bytes()),
            &Validation::default(),
        )
        .unwrap();

        assert_eq!(token_data.claims.sub, 42);
        assert_eq!(token_data.claims.username, "alice");
        assert_eq!(token_data.claims.role, "admin");
    }

    #[test]
    fn test_wrong_secret_fails() {
        let token = create_token(1, "bob", "user", SECRET).unwrap();

        let result = decode::<Claims>(
            &token,
            &DecodingKey::from_secret(b"wrong_secret"),
            &Validation::default(),
        );

        assert!(result.is_err());
    }

    #[test]
    fn test_is_admin() {
        let admin = Claims {
            sub: 1,
            username: "admin".to_string(),
            role: "admin".to_string(),
            exp: 9999999999,
        };
        assert!(admin.is_admin());

        let user = Claims {
            sub: 2,
            username: "user".to_string(),
            role: "user".to_string(),
            exp: 9999999999,
        };
        assert!(!user.is_admin());
    }
}
