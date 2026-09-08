//! Data-plane photo API: browsing views, search, rotation, dedup.
//!
//! No authentication here — access control is the WireGuard network itself.
//! Every request is recorded by the audit middleware.

use axum::{
    extract::{Path, Query, State},
    response::IntoResponse,
    Json, Router,
    routing::{get, post},
};
use serde::Deserialize;

use crate::services::photos::PhotoQuery;
use crate::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/api/photos/timeline", get(timeline))
        .route("/api/photos/tree", get(tree))
        .route("/api/photos/tags", get(tags))
        .route("/api/photos/people", get(people))
        .route("/api/photos/geo", get(geo))
        .route("/api/photos/list", get(list))
        .route("/api/photos/:id", get(detail))
        .route("/api/photos/:id/raw", get(raw))
        .route("/api/photos/:id/rotate", post(rotate))
        .route("/api/photos/people/:id/rename", post(rename_person))
        .route("/api/search", get(search))
        .route("/api/duplicates", get(duplicates))
}

// ──────────────────────────────── query params ─────────────────────────────

#[derive(Deserialize, Default)]
pub struct TimelineQuery {
    pub group: Option<String>,
    /// "photo" | "video" — media type filter.
    pub kind: Option<String>,
    pub from: Option<i64>,
    pub to: Option<i64>,
    pub per_group: Option<i64>,
}

#[derive(Deserialize, Default)]
pub struct ListQuery {
    pub dir_id: Option<i64>,
    pub tag: Option<String>,
    pub person_id: Option<i64>,
    pub from: Option<i64>,
    pub to: Option<i64>,
    pub has_gps: Option<bool>,
    /// "photo" | "video" — media type filter.
    pub kind: Option<String>,
    /// Comma separated photo ids (map cluster drill-down).
    pub ids: Option<String>,
    pub limit: Option<i64>,
    pub offset: Option<i64>,
}

impl ListQuery {
    fn ids(&self) -> Vec<i64> {
        self.ids
            .as_deref()
            .unwrap_or("")
            .split(',')
            .filter_map(|s| s.trim().parse::<i64>().ok())
            .take(1000)
            .collect()
    }
}

#[derive(Deserialize, Default)]
pub struct GeoQuery {
    pub precision: Option<f64>,
}

#[derive(Deserialize, Default)]
pub struct TreeQuery {
    pub dir_id: Option<i64>,
}

#[derive(Deserialize, Default)]
pub struct SearchQueryParams {
    pub q: Option<String>,
    pub r#type: Option<String>,
    pub from: Option<i64>,
    pub to: Option<i64>,
    pub limit: Option<i64>,
    pub offset: Option<i64>,
}

#[derive(Deserialize)]
pub struct RotateBody {
    pub angle: i32,
}

#[derive(Deserialize)]
pub struct RenamePersonBody {
    pub name: String,
}

// ───────────────────────────────── handlers ────────────────────────────────

pub async fn timeline(
    State(state): State<AppState>,
    Query(q): Query<TimelineQuery>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let group = q.group.unwrap_or_else(|| "month".to_string());
    let groups = crate::services::photos::timeline(
        &state.db,
        &state.registry,
        &group,
        q.kind.as_deref(),
        q.from,
        q.to,
        q.per_group.unwrap_or(500),
    )
    .await?;
    Ok(Json(serde_json::json!({ "groups": groups })))
}

pub async fn tree(
    State(state): State<AppState>,
    Query(q): Query<TreeQuery>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let groups = crate::services::photos::tree(&state.db, &state.registry, q.dir_id).await?;
    Ok(Json(serde_json::json!({ "groups": groups })))
}

pub async fn tags(
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let tags = crate::services::photos::tag_summary(&state.db, &state.registry).await?;
    Ok(Json(serde_json::json!({ "tags": tags })))
}

pub async fn people(
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let people = crate::services::photos::people(&state.db, &state.registry).await?;
    Ok(Json(serde_json::json!({ "people": people })))
}

pub async fn geo(
    State(state): State<AppState>,
    Query(q): Query<GeoQuery>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let points =
        crate::services::photos::geo(&state.db, &state.registry, q.precision.unwrap_or(0.02))
            .await?;
    Ok(Json(serde_json::json!({ "points": points })))
}

pub async fn list(
    State(state): State<AppState>,
    Query(q): Query<ListQuery>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let ids = q.ids();
    let (items, total) = crate::services::photos::list(
        &state.db,
        &state.registry,
        &PhotoQuery {
            dir_id: q.dir_id,
            tag: q.tag,
            person_id: q.person_id,
            from: q.from,
            to: q.to,
            has_gps: q.has_gps,
            kind: q.kind,
            ids,
            limit: q.limit.unwrap_or(200).clamp(1, 1000),
            offset: q.offset.unwrap_or(0).max(0),
        },
    )
    .await?;
    Ok(Json(serde_json::json!({ "items": items, "total": total })))
}

pub async fn detail(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let photo = crate::services::photos::get(&state.db, id)
        .await?
        .ok_or_else(|| crate::models::AppError::NotFound("photo not found".into()))?;
    let dir = state
        .registry
        .by_id(photo.dir_id)
        .ok_or_else(|| crate::models::AppError::NotFound("directory not found".into()))?;
    let tags = crate::services::photos::tags_of(&state.db, &[id])
        .await?
        .remove(&id)
        .unwrap_or_default();
    let faces: Vec<crate::models::photo::Face> =
        sqlx::query_as::<_, crate::models::photo::Face>(
            "SELECT id, photo_id, box_x, box_y, box_w, box_h, phash, group_id, created_at \
             FROM faces WHERE photo_id = ?",
        )
        .bind(id)
        .fetch_all(&state.db)
        .await?;

    Ok(Json(serde_json::json!({
        "id": photo.id,
        "dir_id": photo.dir_id,
        "dir_name": dir.name,
        "rel_path": photo.rel_path,
        "name": photo.rel_path.rsplit('/').next(),
        "taken_at": photo.taken_at.unwrap_or(photo.mtime),
        "width": photo.width,
        "height": photo.height,
        "size": photo.size,
        "orientation": photo.orientation,
        "gps_lat": photo.gps_lat,
        "gps_lng": photo.gps_lng,
        "camera_make": photo.camera_make,
        "camera_model": photo.camera_model,
        "compressed": photo.compressed,
        "media_kind": photo.media_kind,
        "duration_ms": photo.duration_ms,
        "video_codec": photo.video_codec,
        "url": crate::services::photos::full_url(&dir.name, &photo.rel_path),
        "thumb_url": crate::services::photos::thumb_url(&dir.name, &photo.rel_path),
        "tags": tags,
        "faces": faces,
    })))
}

/// `GET /api/photos/:id/raw` – stream the original file with Range support.
/// Videos play through this endpoint (ExoPlayer sends Range requests);
/// the path is resolved server-side from the asset id.
pub async fn raw(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    headers: axum::http::HeaderMap,
) -> Result<axum::response::Response, crate::models::AppError> {
    let photo = crate::services::photos::get(&state.db, id)
        .await?
        .ok_or_else(|| crate::models::AppError::NotFound("photo not found".into()))?;
    let (_dir, full) = state
        .registry
        .resolve_id(photo.dir_id, &photo.rel_path)
        .ok_or_else(|| crate::models::AppError::NotFound("directory not found".into()))?;
    if !full.exists() {
        return Err(crate::models::AppError::NotFound("file no longer exists".into()));
    }
    let range = headers
        .get(axum::http::header::RANGE)
        .and_then(|v| v.to_str().ok());
    Ok(crate::handlers::files::stream_file_range(&full, range).await?.into_response())
}

pub async fn rotate(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Json(body): Json<RotateBody>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {    let config = state.config.get();
    let item = crate::services::photos::rotate(&state.db, &state.registry, &config, id, body.angle)
        .await?;
    Ok(Json(serde_json::json!({ "photo": item })))
}

pub async fn rename_person(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Json(body): Json<RenamePersonBody>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    crate::services::ml::cluster::rename_group(&state.db, id, &body.name).await?;
    Ok(Json(serde_json::json!({ "success": true })))
}

pub async fn search(
    State(state): State<AppState>,
    Query(q): Query<SearchQueryParams>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let query = q.q.unwrap_or_default();
    if query.trim().is_empty() {
        return Ok(Json(serde_json::json!({ "hits": [] })));
    }
    let hits = crate::services::search::search(
        &state.db,
        &crate::services::search::SearchQuery {
            q: query,
            ftype: q.r#type,
            from: q.from,
            to: q.to,
            limit: q.limit.unwrap_or(100),
            offset: q.offset.unwrap_or(0),
        },
    )
    .await?;
    Ok(Json(serde_json::json!({ "hits": hits })))
}

pub async fn duplicates(
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let groups = crate::services::dedup::scan(&state.db, &state.registry).await?;
    Ok(Json(serde_json::json!({ "groups": groups })))
}
