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
        .route("/api/photos/semantic", get(semantic))
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
    /// 分页：本页最多返回多少条目。**缺省即不分页**（一次返回全部分组），
    /// 管理端相册页仍走这条路；手机端每页都带 limit。
    pub limit: Option<i64>,
    /// 分页游标 —— 上一页最后一条的 `(taken_at, id)`，只返回比它更旧的条目。
    /// 两个值必须成对出现。
    pub before: Option<i64>,
    pub before_id: Option<i64>,
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
    /// Keyset 分页游标 —— 上一页最后一条的 `(taken_at, id)`，只返回比它更旧的
    /// 条目。两个值必须成对出现，且与 `offset` 互斥。缺省即 `limit`+`offset` 的
    /// 旧路子（管理端仍这么用）。
    pub before: Option<i64>,
    pub before_id: Option<i64>,
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
    /// 当前所在的层（该目录内的相对路径）。缺省 = 目录根。
    pub path: Option<String>,
    /// 本页最多返回多少张照片。**缺省即旧的整库一次性返回** —— 管理端相册页与
    /// 已发布的客户端仍走那条路，不能动。
    pub limit: Option<i64>,
    /// 照片的 keyset 游标，与 `list` 同形：上一页最后一张的 `(taken_at, id)`。
    pub before: Option<i64>,
    pub before_id: Option<i64>,
}

/// 分类列表分页。三个 `after_*` 是**一个**游标（`(photo_count, tag, kind)`）拆成
/// 三个参数，要么都给要么都不给 —— 半截游标会退化成"从头开始"，见 [cursor_of]。
#[derive(Deserialize, Default)]
pub struct TagsQuery {
    /// 本页最多返回多少条。缺省 = 一次返回全部（管理端仍走这条路）。
    pub limit: Option<i64>,
    pub after_count: Option<i64>,
    pub after_tag: Option<String>,
    pub after_kind: Option<String>,
}

/// 人物列表分页。游标 `(photo_count, id)` 拆成两个参数。
#[derive(Deserialize, Default)]
pub struct PeopleQuery {
    pub limit: Option<i64>,
    pub after_count: Option<i64>,
    pub after_id: Option<i64>,
}

/// 游标必须整份给出或整份缺席。
///
/// 半截游标不能当成「没有游标」处理：那会让客户端把第一页反复当下一页拉，
/// 看着永远成功、内容却永远停在开头。分类（3 个字段）和人物（2 个字段）共用它。
fn reject_half_cursor(parts: &[bool], what: &str) -> Result<(), crate::models::AppError> {
    if parts.iter().any(|p| *p) && !parts.iter().all(|p| *p) {
        return Err(crate::models::AppError::BadRequest(format!(
            "{what} must be sent together or not at all"
        )));
    }
    Ok(())
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
    use crate::models::AppError;

    // A half-given cursor would restart from the newest item and re-send page 1
    // forever, so refuse it rather than guess which half was meant.
    let before = match (q.before, q.before_id) {
        (Some(t), Some(id)) => Some((t, id)),
        (None, None) => None,
        _ => {
            return Err(AppError::BadRequest(
                "before and before_id must be sent together".into(),
            ))
        }
    };
    // Paging is opt-in: a cursor only makes sense with a page size. Silently
    // ignoring either would look like it worked while returning the whole
    // library.
    if before.is_some() && q.limit.is_none() {
        return Err(AppError::BadRequest(
            "before requires limit — add limit=<n> to page".into(),
        ));
    }

    let group = q.group.unwrap_or_else(|| "month".to_string());
    let page = crate::services::photos::timeline(
        &state.db,
        &state.registry,
        &crate::services::photos::TimelineParams {
            group,
            kind: q.kind,
            from: q.from,
            to: q.to,
            per_group: q.per_group.unwrap_or(500).max(1),
            page_limit: q.limit.map(|n| n.clamp(1, 1000)),
            before,
        },
    )
    .await?;
    Ok(Json(serde_json::json!({
        "groups": page.groups,
        "has_more": page.has_more,
        "next_before": page.next_before.map(|(t, _)| t),
        "next_before_id": page.next_before.map(|(_, id)| id),
    })))
}

pub async fn tree(
    State(state): State<AppState>,
    Query(q): Query<TreeQuery>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    // 不带 `limit` = 整库一次性返回的旧行为（管理端相册页与已发布的客户端仍
    // 依赖它）。带上 `limit` 才是「按层浏览」，见 `browse_tree`。
    let Some(limit) = q.limit else {
        let groups = crate::services::photos::tree(&state.db, &state.registry, q.dir_id).await?;
        return Ok(Json(serde_json::json!({ "groups": groups })));
    };
    reject_half_cursor(&[q.before.is_some(), q.before_id.is_some()], "before / before_id")?;
    let before = match (q.before, q.before_id) {
        (Some(t), Some(id)) => Some((t, id)),
        _ => None,
    };
    let page = crate::services::photos::browse_tree(
        &state.db,
        &state.registry,
        &crate::services::photos::TreeParams {
            dir_id: q.dir_id,
            // 首尾的 `/` 会让前缀比较落空（`/a` 匹配不上 `a/…`），在这里归一。
            path: q.path.unwrap_or_default().trim_matches('/').to_string(),
            limit: limit.clamp(1, 1000),
            before,
        },
    )
    .await?;
    Ok(Json(serde_json::json!({
        "folders": page.folders,
        "photos": {
            "items": page.photos.items,
            "total": page.photos.total,
            "has_more": page.photos.has_more,
            "next_before": page.photos.next_before.map(|(t, _)| t),
            "next_before_id": page.photos.next_before.map(|(_, id)| id),
        },
    })))
}

pub async fn tags(
    State(state): State<AppState>,
    Query(q): Query<TagsQuery>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    reject_half_cursor(
        &[q.after_count.is_some(), q.after_tag.is_some(), q.after_kind.is_some()],
        "after_count / after_tag / after_kind",
    )?;
    let after = match (q.after_count, q.after_tag, q.after_kind) {
        (Some(count), Some(tag), Some(kind)) => Some((count, tag, kind)),
        _ => None,
    };
    let page = crate::services::photos::tag_summary(
        &state.db,
        &state.registry,
        &crate::services::photos::TagParams {
            limit: q.limit.map(|n| n.clamp(1, 1000)),
            after,
        },
    )
    .await?;
    Ok(Json(serde_json::json!({
        "tags": page.tags,
        "has_more": page.has_more,
        "next_count": page.next_after.as_ref().map(|(c, _, _)| *c),
        "next_tag": page.next_after.as_ref().map(|(_, t, _)| t),
        "next_kind": page.next_after.as_ref().map(|(_, _, k)| k),
    })))
}

pub async fn people(
    State(state): State<AppState>,
    Query(q): Query<PeopleQuery>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    reject_half_cursor(
        &[q.after_count.is_some(), q.after_id.is_some()],
        "after_count / after_id",
    )?;
    let after = match (q.after_count, q.after_id) {
        (Some(count), Some(id)) => Some((count, id)),
        _ => None,
    };
    let page = crate::services::photos::people(
        &state.db,
        &state.registry,
        &crate::services::photos::PersonParams {
            limit: q.limit.map(|n| n.clamp(1, 1000)),
            after,
        },
    )
    .await?;
    Ok(Json(serde_json::json!({
        "people": page.people,
        "has_more": page.has_more,
        "next_count": page.next_after.map(|(c, _)| c),
        "next_id": page.next_after.map(|(_, id)| id),
    })))
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
    reject_half_cursor(&[q.before.is_some(), q.before_id.is_some()], "before / before_id")?;
    let before = match (q.before, q.before_id) {
        (Some(t), Some(id)) => Some((t, id)),
        _ => None,
    };
    // 游标与 offset 是两条互斥的路：同时给了只能说明调用方在混用，与其猜不如拒。
    if before.is_some() && q.offset.unwrap_or(0) != 0 {
        return Err(crate::models::AppError::BadRequest(
            "before / before_id and offset are two different paginations — use one".into(),
        ));
    }
    let page = crate::services::photos::list(
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
            before,
        },
    )
    .await?;
    Ok(Json(serde_json::json!({
        "items": page.items,
        "total": page.total,
        "has_more": page.has_more,
        "next_before": page.next_before.map(|(t, _)| t),
        "next_before_id": page.next_before.map(|(_, id)| id),
    })))
}

#[derive(Deserialize)]
pub struct SemanticQuery {
    /// Natural-language query, e.g. "海边骑自行车".
    pub q: Option<String>,
    pub dir_id: Option<i64>,
    /// EXIF year filter (combined with the CLIP ranking, pre-filter).
    pub year: Option<i64>,
    pub limit: Option<i64>,
}

/// Natural-language photo search: text → Chinese-CLIP embedding → cosine scan
/// over the in-memory store. `dir_id`/`year` narrow the candidate set first
/// (EXIF/directory filters), similarity orders the survivors.
pub async fn semantic(
    State(state): State<AppState>,
    Query(q): Query<SemanticQuery>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let query = q.q.unwrap_or_default();
    if query.trim().is_empty() {
        return Ok(Json(serde_json::json!({ "items": [], "total": 0, "scores": {} })));
    }
    #[cfg(not(feature = "onnx"))]
    {
        return Err(crate::models::AppError::BadRequest(
            "semantic search requires a build with the `onnx` feature".into(),
        ));
    }
    #[cfg(feature = "onnx")]
    {
        use std::collections::HashMap;

        let Some(clip) = state.queue.clip.clone() else {
            return Err(crate::models::AppError::BadRequest(
                "semantic search disabled: clip model not loaded (enable ml.clip in settings)".into(),
            ));
        };

        // 1. Text embedding (CPU-bound → blocking pool).
        let text = query.clone();
        let qvec = tokio::task::spawn_blocking(move || clip.embed_text(&text))
            .await
            .map_err(|e| crate::models::AppError::Internal(e.to_string()))?
            .map_err(|e| crate::models::AppError::Internal(format!("text encode failed: {e}")))?;

        // 2. Candidate filter (EXIF year / directory) before the vector scan.
        let mut allowed: Option<HashMap<i64, ()>> = None;
        if q.dir_id.is_some() || q.year.is_some() {
            let rows: Vec<(i64,)> = match (q.dir_id, q.year) {
                (Some(dir), Some(year)) => {
                    let (from, to) = year_bounds(year)?;
                    sqlx::query_as(
                        "SELECT id FROM photo_assets WHERE status='ok' AND dir_id=? AND taken_at>=? AND taken_at<?",
                    )
                    .bind(dir)
                    .bind(from)
                    .bind(to)
                    .fetch_all(&state.db)
                    .await?
                }
                (Some(dir), None) => {
                    sqlx::query_as("SELECT id FROM photo_assets WHERE status='ok' AND dir_id=?")
                        .bind(dir)
                        .fetch_all(&state.db)
                        .await?
                }
                (None, Some(year)) => {
                    let (from, to) = year_bounds(year)?;
                    sqlx::query_as(
                        "SELECT id FROM photo_assets WHERE status='ok' AND taken_at>=? AND taken_at<?",
                    )
                    .bind(from)
                    .bind(to)
                    .fetch_all(&state.db)
                    .await?
                }
                (None, None) => unreachable!(),
            };
            let map: HashMap<i64, ()> = rows.into_iter().map(|(id,)| (id, ())).collect();
            if map.is_empty() {
                return Ok(Json(serde_json::json!({ "items": [], "total": 0, "scores": {} })));
            }
            allowed = Some(map);
        }

        // 3. Cosine scan over the in-memory mirror (SQLite stays cold).
        let limit = q.limit.unwrap_or(60).clamp(1, 200) as usize;
        let hits = state
            .queue
            .embeddings
            .search(&qvec, limit, allowed.as_ref())
            .map_err(crate::models::AppError::BadRequest)?;

        // Adaptive relevance cut: cosine similarity is not calibrated
        // "confidence" — a batch of loosely-related images clusters around
        // ~0.45-0.55 and floods the result. Keep only scores up to the first
        // steep drop from the best (gap > 4%): everything after the cliff is
        // weak relevance. No absolute floor: true positives (dragonfly 0.557,
        // einstein 0.56-0.59) sit right next to noise on a small library, so
        // a hard cutoff would cut real matches — only the cliff is reliable.
        let hits = {
            let mut kept = Vec::with_capacity(hits.len());
            let mut best = None;
            for (id, s) in &hits {
                if let Some(b) = best {
                    if *s < b - 0.04 {
                        break;
                    }
                } else {
                    best = Some(*s);
                }
                kept.push((*id, *s));
            }
            kept
        };

        if hits.is_empty() {
            return Ok(Json(serde_json::json!({ "items": [], "total": 0, "scores": {} })));
        }

        // 4. Materialise items in similarity order.
        let ids: Vec<i64> = hits.iter().map(|(id, _)| *id).collect();
        let score_of: HashMap<i64, f32> = hits.iter().copied().collect();
        let page = crate::services::photos::list(
            &state.db,
            &state.registry,
            &PhotoQuery {
                dir_id: None,
                tag: None,
                person_id: None,
                from: None,
                to: None,
                has_gps: None,
                kind: None,
                ids: ids.clone(),
                limit: ids.len() as i64,
                offset: 0,
                before: None,
            },
        )
        .await?;
        let mut items = page.items;
        items.sort_by(|a, b| {
            let sa = score_of.get(&a.id).copied().unwrap_or(-1.0);
            let sb = score_of.get(&b.id).copied().unwrap_or(-1.0);
            sb.partial_cmp(&sa).unwrap_or(std::cmp::Ordering::Equal)
        });

        let scores: serde_json::Map<String, serde_json::Value> = items
            .iter()
            .filter_map(|it| {
                score_of
                    .get(&it.id)
                    .map(|s| (it.id.to_string(), serde_json::json!(format!("{:.3}", s))))
            })
            .collect();

        Ok(Json(serde_json::json!({
            "items": items,
            "total": items.len(),
            "scores": scores,
        })))
    }
}

/// Local-time year boundaries as unix seconds for the EXIF `year` filter.
#[cfg(feature = "onnx")]
fn year_bounds(year: i64) -> Result<(i64, i64), crate::models::AppError> {
    use chrono::TimeZone;
    let start = chrono::Local
        .with_ymd_and_hms(year as i32, 1, 1, 0, 0, 0)
        .single()
        .ok_or_else(|| crate::models::AppError::BadRequest(format!("invalid year: {year}")))?;
    let end = chrono::Local
        .with_ymd_and_hms(year as i32 + 1, 1, 1, 0, 0, 0)
        .single()
        .ok_or_else(|| crate::models::AppError::BadRequest(format!("invalid year: {year}")))?;
    Ok((start.timestamp(), end.timestamp()))
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
        "url": crate::services::photos::full_url(&dir.name, &photo.rel_path, &photo.fingerprint),
        "thumb_url": crate::services::photos::thumb_url(&dir.name, &photo.rel_path, &photo.fingerprint),
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

#[cfg(test)]
mod tests {
    use super::*;

    /// 半截游标必须被拒，而不是被当成「没有游标」。
    ///
    /// 当成没有游标的话，客户端会把第一页反复当下一页拉：请求全部成功、界面看着
    /// 正常，内容却永远停在前 60 条 —— 正是最难被发现的那种坏法。
    #[test]
    fn half_a_cursor_is_rejected() {
        assert!(reject_half_cursor(&[false, false, false], "c").is_ok());
        assert!(reject_half_cursor(&[true, true, true], "c").is_ok());
        assert!(reject_half_cursor(&[true, false, false], "c").is_err());
        assert!(reject_half_cursor(&[false, true, false], "c").is_err());
        assert!(reject_half_cursor(&[false, false, true], "c").is_err());
        assert!(reject_half_cursor(&[false, true, true], "c").is_err());
        assert!(reject_half_cursor(&[true, false, true], "c").is_err());
        assert!(reject_half_cursor(&[true, true, false], "c").is_err());
        // 二元组（人物、list 的 before）也走同一套规则。
        assert!(reject_half_cursor(&[true, false], "c").is_err());
        assert!(reject_half_cursor(&[false, false], "c").is_ok());
    }
}
