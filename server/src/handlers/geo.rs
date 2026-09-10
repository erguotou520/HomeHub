//! `GET /api/geo/reverse?lat=&lng=` — reverse-geocode a map cluster centre.
//!
//! Results are cached in `geo_places` keyed by coordinates rounded to 1e-4
//! degrees (~11 m), so re-tapping the same cluster — or any photo taken in
//! the same neighbourhood — never re-hits the external provider.

use axum::{
    extract::{Query, State},
    response::IntoResponse,
    routing::get,
    Json, Router,
};
use std::collections::HashMap;

use crate::models::AppError;
use crate::AppState;

pub fn routes() -> Router<AppState> {
    Router::new().route("/api/geo/reverse", get(reverse))
}

/// JSON body for both cache hits and provider lookups.
#[derive(serde::Serialize)]
struct ReverseResponse {
    /// Place label ("北京市 东城区 …"), or null when the provider failed.
    label: Option<String>,
    /// True when this answer came from the DB cache.
    cached: bool,
}

pub async fn reverse(
    State(state): State<AppState>,
    Query(q): Query<HashMap<String, String>>,
) -> Result<impl IntoResponse, AppError> {
    let lat: f64 = q
        .get("lat")
        .and_then(|v| v.parse().ok())
        .ok_or_else(|| AppError::BadRequest("missing or invalid `lat`".into()))?;
    let lng: f64 = q
        .get("lng")
        .and_then(|v| v.parse().ok())
        .ok_or_else(|| AppError::BadRequest("missing or invalid `lng`".into()))?;

    // ~11 m grid: fine for "which place is this", coarse enough to cache well.
    let lat_key = (lat * 10_000.0).round() as i64;
    let lng_key = (lng * 10_000.0).round() as i64;

    if let Some(label) = fetch_cached(&state, lat_key, lng_key).await {
        return Ok(Json(ReverseResponse {
            label: Some(label),
            cached: true,
        }));
    }

    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(6))
        .build()
        .map_err(|e| AppError::Internal(format!("http client: {e}")))?;

    match crate::services::geo::reverse_label(&client, lat, lng).await {
        Some(label) => {
            let _ = sqlx::query(
                "INSERT INTO geo_places (lat_key, lng_key, label) VALUES (?, ?, ?) \
                 ON CONFLICT(lat_key, lng_key) DO NOTHING",
            )
            .bind(lat_key)
            .bind(lng_key)
            .bind(&label)
            .execute(&state.db)
            .await;
            Ok(Json(ReverseResponse {
                label: Some(label),
                cached: false,
            }))
        }
        None => Ok(Json(ReverseResponse {
            label: None,
            cached: false,
        })),
    }
}

async fn fetch_cached(state: &AppState, lat_key: i64, lng_key: i64) -> Option<String> {
    let row: Option<(String,)> =
        sqlx::query_as::<_, (String,)>("SELECT label FROM geo_places WHERE lat_key = ? AND lng_key = ?")
            .bind(lat_key)
            .bind(lng_key)
            .fetch_optional(&state.db)
            .await
            .ok()?;
    row.map(|(label,)| label)
}
