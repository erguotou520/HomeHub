//! NVR / monitoring API.
//!
//! 管理面 `/api/admin/nvr/*` 要管理员 JWT（增删改摄像头、改设置、清理、删片段、
//! 管理手机端访问令牌）；手机端用的**只读**接口挂在数据面 `/api/nvr/*` 上，要
//! `runtime.security.device-token` —— 见 `device_routes()`。

use std::sync::Arc;

use axum::{
    extract::{Path, Query, State},
    routing::{delete, get, post, put},
    Json, Router,
};
use serde::Deserialize;
use sqlx::Row;

use crate::middleware::admin_auth::Claims;
use crate::models::AppError;
use crate::services::nvr::{self, NvrState};
use crate::AppState;

/// Build the NVR router.
///
/// Takes `AppState` because the data plane needs it as middleware state
/// (`from_fn_with_state`) — the shared token is read from the live config on
/// every request, so a change in the browser applies to the next fragment
/// without a restart.
pub fn routes(state: AppState) -> Router<AppState> {
    Router::new()
        // Camera CRUD
        .route("/api/admin/nvr/cameras", get(list_cameras))
        .route("/api/admin/nvr/cameras", post(create_camera))
        .route("/api/admin/nvr/cameras/:id", put(update_camera))
        .route("/api/admin/nvr/cameras/:id", delete(delete_camera))
        .route("/api/admin/nvr/cameras/:id/restart", post(restart_camera))
        // Timeline / segments
        .route("/api/admin/nvr/segments", get(list_segments))
        .route("/api/admin/nvr/segments/:cam/timeline", get(timeline))
        .route("/api/admin/nvr/segments/:id", delete(delete_segment))
        // NVR settings + maintenance
        .route("/api/admin/nvr/settings", get(nvr_settings))
        .route("/api/admin/nvr/settings", put(update_nvr_settings))
        .route("/api/admin/nvr/cleanup", post(run_cleanup))
        // 手机端访问令牌（数据面的共享凭据）
        .route("/api/admin/nvr/device-token", get(get_device_token))
        .route("/api/admin/nvr/device-token", put(set_device_token))
        .route(
            "/api/admin/nvr/device-token/generate",
            post(generate_device_token),
        )
        // ── data plane (手机端只看不管理) ──────────────────────────────
        .merge(device_routes(state))
}

/// 数据面：手机端（和后台监控页的播放器）用的**只读**接口。
///
/// 写接口（增删改摄像头、改设置、清理、删片段）仍然留在 `/api/admin/nvr/*`
/// 上受管理员密码保护。
///
/// **凭据**：需要 `runtime.security.device-token`（后台「监控 → 手机访问令牌」）。
/// 空串 = 不校验，保持升级前的行为；一旦设置，`X-Device-Token` 或管理员 JWT
/// 二者之一必须正确 —— 见 `middleware::device_auth`。
fn device_routes(state: AppState) -> Router<AppState> {
    Router::new()
        .route("/api/nvr/cameras", get(list_cameras_device))
        .route("/api/nvr/segments", get(list_segments_device))
        .route("/api/nvr/settings", get(nvr_settings_device))
        // PTZ. The phone deliberately has no camera credentials (the camera
        // list is credential-masked) and usually isn't even on the camera's
        // network, so pan/tilt/zoom has to be executed here, by the box that
        // can actually reach the device over ONVIF.
        .route("/api/nvr/cameras/:cam/ptz", post(ptz_device))
        // Playback (Range-capable; moov is at the end, so clients MUST use
        // HTTP Range — Media3 and <video> do by default).
        .route("/api/nvr/segments/:id", get(playback))
        // Live preview (RTSP→HLS, on-demand). Two plain param routes cover the
        // playlist (`/api/nvr/live/{cam}`) and media segments
        // (`/api/nvr/live/{cam}/{file}`); the handler splits the path itself.
        // `?stream=main|sub` 选哪一路，缺省取摄像头的 `preview_stream`。
        .route("/api/nvr/live/:cam", get(live_stream))
        .route("/api/nvr/live/:cam/:file", get(live_stream))
        .route_layer(axum::middleware::from_fn_with_state(
            state,
            crate::middleware::device_auth::require_device_token,
        ))
}

// ───────────────────── 手机端访问令牌（management plane） ──────────────────

/// `GET /api/admin/nvr/device-token` — 只回报「配没配」和掩码，不回明文。
///
/// 明文只在生成的那一刻出现一次：后台把令牌交给手机之后就再也不需要读回来，
/// 而把它留在响应里等于让任何一条管理端日志都能拿到数据面的钥匙。
pub async fn get_device_token(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, AppError> {
    let token = state.config.get().runtime.security.device_token.clone();
    Ok(Json(serde_json::json!({
        "configured": !token.is_empty(),
        "length": token.chars().count(),
        "masked": if token.is_empty() { String::new() } else { DEVICE_TOKEN_MASK.to_string() },
    })))
}

/// 与 `admin::SECRET_MASK` 同义：表单原样回传掩码 = 「没改」。
const DEVICE_TOKEN_MASK: &str = "••••••••";

#[derive(Deserialize)]
pub struct DeviceTokenBody {
    /// 新令牌。缺省 / 掩码 = 保持不变；空串 = 关闭校验。
    #[serde(default)]
    pub token: Option<String>,
}

/// `PUT /api/admin/nvr/device-token` — 设置或清除手机端访问令牌。
pub async fn set_device_token(
    State(state): State<AppState>,
    _claims: Claims,
    Json(body): Json<DeviceTokenBody>,
) -> Result<Json<serde_json::Value>, AppError> {
    let current = state.config.get().runtime.security.device_token.clone();
    let incoming = body.token.unwrap_or_else(|| DEVICE_TOKEN_MASK.to_string());
    let next = if incoming == DEVICE_TOKEN_MASK {
        current
    } else {
        incoming.trim().to_string()
    };
    store_device_token(&state, next.clone()).await?;
    Ok(Json(serde_json::json!({
        "success": true,
        "configured": !next.is_empty(),
    })))
}

/// `POST /api/admin/nvr/device-token/generate` — 生成一个随机令牌。
///
/// 明文**只在这个响应里出现一次**，后台要当场抄给手机；之后 `GET` 只能看到掩码。
pub async fn generate_device_token(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, AppError> {
    use rand::Rng;
    let token: String = rand::thread_rng()
        .sample_iter(&rand::distributions::Alphanumeric)
        .take(32)
        .map(char::from)
        .collect();
    store_device_token(&state, token.clone()).await?;
    Ok(Json(serde_json::json!({ "token": token })))
}

/// Persist a token. Same two-step as `/api/admin/settings`: SQLite is the
/// record, then the in-memory config so the next request already sees it.
///
/// `config.yaml` deliberately does **not** get a copy — `security` is
/// admin-owned, and a stale copy in the file would let a restart undo it.
async fn store_device_token(state: &AppState, token: String) -> Result<(), AppError> {
    if !token.is_empty() && token.chars().count() < 6 {
        return Err(AppError::BadRequest(
            "令牌太短了（至少 6 位）；留空表示关闭校验".into(),
        ));
    }
    if token.chars().count() > 128 {
        return Err(AppError::BadRequest("令牌太长了（最多 128 位）".into()));
    }

    let mut next = (*state.config.get()).clone();
    next.runtime.security.device_token = token;
    let serialized = serde_json::to_string(&next.runtime)?;
    crate::db::set_setting(&state.db, "runtime_settings", &serialized).await?;
    state
        .config
        .update(next)
        .map_err(|e| AppError::Internal(format!("save config: {e}")))?;
    Ok(())
}

/// Require the NVR subsystem to be up; 503 otherwise.
fn nvr(state: &AppState) -> Result<&Arc<NvrState>, AppError> {
    state
        .nvr
        .as_ref()
        .ok_or_else(|| AppError::Internal("nvr disabled".into()))
}

// ──────────────────────────── data plane (手机端) ──────────────────────────
//
// `/api/nvr/*` 上的只读接口只校验共享令牌（`middleware::device_auth`）。这些
// handler 只是把同一个实现按「设备」身份再挂一遍；`Claims` 本身在只读路径上没有
// 被读过（都是 `_claims`），所以这里传一个合成凭据即可，不需要动原有实现。

fn device_claims() -> Claims {
    Claims {
        sub: "device".into(),
        role: "device".into(),
        exp: 0,
    }
}

pub async fn list_cameras_device(
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, AppError> {
    // `mask = true`: this route needs no credentials, so it must not hand out
    // the camera's own password inside `rtsp_url`.
    list_cameras_with(state, device_claims(), true).await
}

pub async fn list_segments_device(
    State(state): State<AppState>,
    Query(q): Query<SegmentsQuery>,
) -> Result<Json<serde_json::Value>, AppError> {
    list_segments(State(state), device_claims(), Query(q)).await
}

pub async fn nvr_settings_device(
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, AppError> {
    nvr_settings(State(state), device_claims()).await
}

/// PTZ request body. `action` is `move` | `stop`.
#[derive(Deserialize)]
pub struct PtzBody {
    pub action: String,
    /// Pan: negative = left, positive = right.
    #[serde(default)]
    pub x: f64,
    /// Tilt: negative = up, positive = down (ONVIF convention).
    #[serde(default)]
    pub y: f64,
    /// Zoom: negative = wide, positive = tele.
    #[serde(default)]
    pub z: f64,
}

/// Run a PTZ command on the camera itself.
///
/// The phone cannot do this directly: it has no camera credentials (the
/// camera listing is credential-masked) and is rarely on the camera's
/// network. The server owns both, so it executes the ONVIF call and returns
/// the camera's own error text on failure.
pub async fn ptz_device(
    State(state): State<AppState>,
    Path(cam_name): Path<String>,
    Json(body): Json<PtzBody>,
) -> Result<Json<serde_json::Value>, AppError> {
    let nvr = nvr(&state)?;
    let cam = nvr::get_camera_by_name(&state.db, &cam_name)
        .await
        .map_err(|_| AppError::NotFound("camera not found".into()))?;
    let ep = nvr::onvif::endpoint_from_rtsp(&cam.rtsp_url)
        .map_err(|e| AppError::BadRequest(e.to_string()))?;
    if ep.user.is_empty() {
        return Err(AppError::BadRequest(
            "camera has no credentials in rtsp_url; PTZ needs them".into(),
        ));
    }

    // Resolve (and cache) the media profile token the PTZ actions need.
    let token = match nvr.onvif_tokens.get(&cam.name) {
        Some(t) => t,
        None => {
            let t = nvr::onvif::profile_token(&ep)
                .await
                .map_err(|e| AppError::BadRequest(e.to_string()))?;
            nvr.onvif_tokens.put(&cam.name, &t);
            t
        }
    };

    let result = match body.action.as_str() {
        "move" => nvr::onvif::continuous_move(&ep, &token, body.x, body.y, body.z).await,
        "stop" => nvr::onvif::stop(&ep, &token).await,
        other => {
            return Err(AppError::BadRequest(format!(
                "unknown ptz action '{other}' (expected move|stop)"
            )))
        }
    };
    match result {
        Ok(()) => Ok(Json(serde_json::json!({ "ok": true }))),
        Err(e) => {
            // A stale token is the one failure worth retrying once.
            if e.to_string().contains("token") || e.to_string().contains("NotAuthorized") {
                nvr.onvif_tokens.put(&cam.name, "");
                if let Ok(fresh) = nvr::onvif::profile_token(&ep).await {
                    nvr.onvif_tokens.put(&cam.name, &fresh);
                }
            }
            Err(AppError::BadRequest(e.to_string()))
        }
    }
}

// ─────────────────────────────────── cameras ───────────────────────────────

/// Admin camera list (password-protected).
///
/// **Unmasked**: the form round-trips these URLs back on save, so handing it
/// `rtsp://***:***@host/stream1` would persist the mask and silently break the
/// recorder. The device route below is the one that masks.
pub async fn list_cameras(
    State(state): State<AppState>,
    claims: Claims,
) -> Result<Json<serde_json::Value>, AppError> {
    list_cameras_with(state, claims, false).await
}

/// Shared implementation. `mask` is a plain argument, so it cannot sit on the
/// axum handler itself — axum can only inject extractors.
async fn list_cameras_with(
    state: AppState,
    _claims: Claims,
    mask: bool,
) -> Result<Json<serde_json::Value>, AppError> {
    let nvr = nvr(&state)?;
    let cams = nvr::list_cameras(nvr, mask).await?;
    let out: Vec<serde_json::Value> = cams
        .iter()
        .map(|c| {
            serde_json::json!({
                "id": c.id,
                "name": c.name,
                "label": c.label,
                "rtsp_url": c.rtsp_url,
                "rtsp_sub_url": c.rtsp_sub_url,
                "enabled": c.enabled,
                "record_stream": c.record_stream,
                "record_audio": c.record_audio,
                "preview_stream": c.preview_stream,
                "status": c.status,
                "restart_count": c.restart_count,
                "last_error": c.last_error,
                "last_segment_at": c.last_segment_at,
                "today_segments": c.today_segments,
                "total_size_bytes": c.total_size_bytes,
                "total_segments": c.total_segments,
            })
        })
        .collect();
    Ok(Json(serde_json::json!({ "cameras": out })))
}

pub async fn create_camera(
    State(state): State<AppState>,
    _claims: Claims,
    Json(body): Json<nvr::CameraCreate>,
) -> Result<Json<serde_json::Value>, AppError> {
    let _ = nvr(&state)?;
    let cam = nvr::create_camera(&state.db, body).await
        .map_err(|e| AppError::BadRequest(e.to_string()))?;
    Ok(Json(serde_json::json!({
        "id": cam.id,
        "name": cam.name,
        "created": true,
    })))
}

pub async fn update_camera(
    State(state): State<AppState>,
    _claims: Claims,
    Path(id): Path<i64>,
    Json(body): Json<nvr::CameraUpdate>,
) -> Result<Json<serde_json::Value>, AppError> {
    let _ = nvr(&state)?;
    let cam = nvr::update_camera(&state.db, id, body)
        .await
        .map_err(|e| AppError::BadRequest(e.to_string()))?;
    Ok(Json(serde_json::json!({
        "id": cam.id,
        "name": cam.name,
        "updated": true,
    })))
}

pub async fn delete_camera(
    State(state): State<AppState>,
    _claims: Claims,
    Path(id): Path<i64>,
) -> Result<Json<serde_json::Value>, AppError> {
    let nvr = nvr(&state)?;
    // Stop the recorder first so ffmpeg doesn't keep holding the RTSP pull.
    nvr::recorder::stop_camera(nvr, id).await;
    nvr::delete_camera(&state.db, id)
        .await
        .map_err(|e| AppError::BadRequest(e.to_string()))?;
    Ok(Json(serde_json::json!({ "deleted": true })))
}

pub async fn restart_camera(
    State(state): State<AppState>,
    _claims: Claims,
    Path(id): Path<i64>,
) -> Result<Json<serde_json::Value>, AppError> {
    let nvr = nvr(&state)?;
    nvr::recorder::restart_camera(nvr, id).await;
    Ok(Json(serde_json::json!({ "restarted": true })))
}

// ─────────────────────────────────── segments ──────────────────────────────

#[derive(Deserialize)]
struct SegmentsQuery {
    /// Camera name; required unless `camera_id` is set.
    camera: Option<String>,
    camera_id: Option<i64>,
    /// Unix seconds — only segments with start_time >= `from`.
    from: Option<i64>,
    /// Unix seconds — only segments with end_time <= `to`.
    to: Option<i64>,
    /// Page size (1..=500, default 100).
    #[serde(default = "default_limit")]
    limit: i64,
    /// Cursor: return segments older than this start_time (newest-first).
    before: Option<i64>,
    /// Direction: `newest` (default) or `oldest`.
    direction: Option<String>,
    /// 1 = only still segments, 2 = only active (motion) segments;
    /// absent = all.
    motion: Option<i64>,
}

fn default_limit() -> i64 {
    100
}

pub async fn list_segments(
    State(state): State<AppState>,
    _claims: Claims,
    Query(q): Query<SegmentsQuery>,
) -> Result<Json<serde_json::Value>, AppError> {
    let _ = nvr(&state)?;
    let limit = q.limit.clamp(1, 500);
    let cam_id: i64 = if let Some(id) = q.camera_id {
        id
    } else {
        let name = q
            .camera
            .as_deref()
            .ok_or_else(|| AppError::BadRequest("camera or camera_id required".into()))?;
        let cam = nvr::get_camera_by_name(&state.db, name)
            .await
            .map_err(|_| AppError::NotFound("camera not found".into()))?;
        cam.id
    };

    let order = if q.direction.as_deref() == Some("oldest") {
        "ASC"
    } else {
        "DESC"
    };
    // Fixed predicate set (from/to/before default to neutral sentinels) so
    // binding stays static — no dynamic SQL.
    let from = q.from.unwrap_or(i64::MIN / 2);
    let to = q.to.unwrap_or(i64::MAX / 2);
    let before = q.before.unwrap_or(i64::MAX / 2);
    let order = if q.direction.as_deref() == Some("oldest") {
        "ASC"
    } else {
        "DESC"
    };
    let sql = format!(
        "SELECT id, camera_id, path, start_time, end_time, duration, size_bytes, video_codec, width, height, motion, motion_score \
         FROM nvr_segments \
         WHERE camera_id = ? AND start_time >= ? AND end_time <= ? AND start_time < ? AND (? = 0 OR motion = ?) \
         ORDER BY start_time {order} LIMIT ?"
    );
    let motion_filter = q.motion.unwrap_or(0);
    let rows = sqlx::query(&sql)
    .bind(cam_id)
    .bind(from)
    .bind(to)
    .bind(before)
    .bind(motion_filter)
    .bind(motion_filter)
    .bind(limit)
    .fetch_all(&state.db)
    .await
    .map_err(|e| AppError::Internal(e.to_string()))?;

    let mut segments = Vec::with_capacity(rows.len());
    for row in &rows {
        segments.push(serde_json::json!({
            "id": row.try_get::<i64, _>("id").unwrap_or(0),
            "camera_id": row.try_get::<i64, _>("camera_id").unwrap_or(0),
            "path": row.try_get::<String, _>("path").unwrap_or_default(),
            "start_time": row.try_get::<i64, _>("start_time").unwrap_or(0),
            "end_time": row.try_get::<i64, _>("end_time").unwrap_or(0),
            "duration": row.try_get::<f64, _>("duration").unwrap_or(0.0),
            "size_bytes": row.try_get::<i64, _>("size_bytes").unwrap_or(0),
            "video_codec": row.try_get::<Option<String>, _>("video_codec").unwrap_or(None),
            "width": row.try_get::<Option<i64>, _>("width").unwrap_or(None),
            "height": row.try_get::<Option<i64>, _>("height").unwrap_or(None),
            "motion": row.try_get::<i64, _>("motion").unwrap_or(0),
            "motion_score": row.try_get::<f64, _>("motion_score").unwrap_or(0.0),
        }));
    }
    let next_before = (order == "DESC").then(|| {
        segments
            .last()
            .and_then(|s| s["start_time"].as_i64())
    });
    Ok(Json(serde_json::json!({
        "segments": segments,
        "next_before": next_before,
        "limit": limit,
    })))
}

/// Timeline for the admin UI's hour ruler: per-hour coverage for one camera
/// over a time window. Returns `[{hour_start, hour_end, segments, bytes}]`.
pub async fn timeline(
    State(state): State<AppState>,
    _claims: Claims,
    Path(cam): Path<String>,
    Query(q): Query<TimelineQuery>,
) -> Result<Json<serde_json::Value>, AppError> {
    let _ = nvr(&state)?;
    let cam_row = nvr::get_camera_by_name(&state.db, &cam)
        .await
        .map_err(|_| AppError::NotFound("camera not found".into()))?;

    let now = crate::db::now();
    let from = q.from.unwrap_or(now - 7 * 86400).max(now - 30 * 86400);
    let to = q.to.unwrap_or(now);
    if to <= from {
        return Err(AppError::BadRequest("to must be after from".into()));
    }

    let rows = sqlx::query_as::<_, (i64, i64, i64)>(
        "SELECT start_time, end_time, size_bytes FROM nvr_segments \
         WHERE camera_id = ? AND end_time > ? AND start_time < ?",
    )
    .bind(cam_row.id)
    .bind(from)
    .bind(to)
    .fetch_all(&state.db)
    .await
    .map_err(|e| AppError::Internal(e.to_string()))?;

    let hours = ((to - from) / 3600).max(0) as usize;
    let mut buckets: Vec<(i64, i64, i64)> = (0..hours)
        .map(|i| {
            let h = from - (from % 3600) + i as i64 * 3600;
            (h, 0i64, 0i64)
        })
        .collect();
    for (st, et, sz) in rows {
        let h0 = st - (st % 3600);
        let h1 = et - (et % 3600);
        for b in buckets.iter_mut() {
            if b.0 >= h0 && b.0 <= h1 {
                b.1 += 1;
                b.2 += sz;
            }
        }
    }
    let out: Vec<serde_json::Value> = buckets
        .into_iter()
        .map(|(h, segs, bytes)| {
            serde_json::json!({
                "hour_start": h,
                "hour_end": h + 3600,
                "segments": segs,
                "bytes": bytes,
            })
        })
        .collect();
    Ok(Json(serde_json::json!({ "hours": out })))
}

#[derive(Deserialize)]
struct TimelineQuery {
    from: Option<i64>,
    to: Option<i64>,
}

pub async fn delete_segment(
    State(state): State<AppState>,
    _claims: Claims,
    Path(id): Path<i64>,
) -> Result<Json<serde_json::Value>, AppError> {
    let nvr = nvr(&state)?;
    // File first, row second (same rule as the cleanup loop).
    let rel: Option<String> =
        sqlx::query_scalar("SELECT path FROM nvr_segments WHERE id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await
            .map_err(|e| AppError::Internal(e.to_string()))?;
    if let Some(rel) = rel {
        let _ = std::fs::remove_file(nvr.segment_abs_path(&rel));
    }
    let res = sqlx::query("DELETE FROM nvr_segments WHERE id = ?")
        .bind(id)
        .execute(&state.db)
        .await
        .map_err(|e| AppError::Internal(e.to_string()))?;
    if res.rows_affected() == 0 {
        return Err(AppError::NotFound("segment not found".into()));
    }
    Ok(Json(serde_json::json!({ "deleted": true })))
}

// ─────────────────────────────────── playback ──────────────────────────────

/// 回放片段（Range 直出）。
///
/// 只挂在数据面 `/api/nvr/segments/:id` 上，凭据由 `device_routes()` 的令牌
/// 中间件校验 —— 两个播放器（Media3 与浏览器的 `<video>`）都把令牌放在请求头
/// 里，`?token=` 那种会漏进代理日志的写法不再需要。
pub async fn playback(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    headers: axum::http::HeaderMap,
) -> Result<axum::response::Response, AppError> {
    use tokio::io::{AsyncReadExt, AsyncSeekExt};

    let nvr = nvr(&state)?;
    let row: Option<(String, i64)> =
        sqlx::query_as("SELECT path, size_bytes FROM nvr_segments WHERE id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await
            .map_err(|e| AppError::Internal(e.to_string()))?;
    let Some((rel, db_size)) = row else {
        return Err(AppError::NotFound("segment not found".into()));
    };
    let full = nvr.segment_abs_path(&rel);
    if !full.is_file() {
        // Orphan row (file deleted out-of-band); prune and 404.
        let _ = sqlx::query("DELETE FROM nvr_segments WHERE id = ?")
            .bind(id)
            .execute(&state.db)
            .await;
        return Err(AppError::NotFound("segment file missing".into()));
    }
    let size = std::fs::metadata(&full).map(|m| m.len()).unwrap_or(db_size as u64);

    let mut file = tokio::fs::File::open(&full)
        .await
        .map_err(|e| AppError::Internal(e.to_string()))?;

    let range_header = headers
        .get(axum::http::header::RANGE)
        .and_then(|v| v.to_str().ok());
    let parsed = range_header
        .and_then(|h| parse_byte_range(h, size))
        .unwrap_or(Some((0, size.saturating_sub(1))));

    let (status, start, end) = match parsed {
        Some((s, e)) => (
            axum::http::StatusCode::PARTIAL_CONTENT,
            s,
            e.min(size - 1),
        ),
        None => (
            axum::http::StatusCode::RANGE_NOT_SATISFIABLE,
            0,
            0,
        ),
    };
    let length = end - start + 1;

    let mut buf = Vec::with_capacity(64 * 1024);
    file.seek(std::io::SeekFrom::Start(start))
        .await
        .map_err(|e| AppError::Internal(e.to_string()))?;
    file.take(length)
        .read_to_end(&mut buf)
        .await
        .map_err(|e| AppError::Internal(e.to_string()))?;

    use axum::http::{header, StatusCode, Response};
    let mut resp = Response::builder()
        .status(if status == StatusCode::RANGE_NOT_SATISFIABLE {
            status
        } else {
            status
        })
        .header(header::CONTENT_TYPE, "video/mp4")
        .header(header::ACCEPT_RANGES, "bytes")
        .header(header::CACHE_CONTROL, "private, max-age=300");
    if status == StatusCode::PARTIAL_CONTENT {
        resp = resp.header(
            header::CONTENT_RANGE,
            format!("bytes {start}-{end}/{size}"),
        );
        resp = resp.header(header::CONTENT_LENGTH, buf.len().to_string());
    }
    resp.body(axum::body::Body::from(buf))
        .map_err(|e| AppError::Internal(e.to_string()))
}

/// Minimal single-range parser (same semantics as the files handler's).
fn parse_byte_range(header: &str, size: u64) -> Option<Option<(u64, u64)>> {
    let rest = header.trim().strip_prefix("bytes=")?;
    if rest.contains(',') {
        return None;
    }
    let mut parts = rest.split('-');
    let a = parts.next().unwrap_or("").trim();
    let b = parts.next().unwrap_or("").trim();
    match (a.is_empty(), b.is_empty()) {
        (false, false) => {
            let start: u64 = a.parse().ok()?;
            let end: u64 = b.parse().ok()?;
            if start > end || start >= size {
                Some(None)
            } else {
                Some(Some((start, end.min(size - 1))))
            }
        }
        (true, false) => {
            // suffix: last N bytes
            let n: u64 = b.parse().ok()?;
            if n == 0 {
                Some(None)
            } else {
                let start = size.saturating_sub(n);
                Some(Some((start, size - 1)))
            }
        }
        (false, true) => {
            let start: u64 = a.parse().ok()?;
            if start >= size {
                Some(None)
            } else {
                Some(Some((start, size - 1)))
            }
        }
        (true, true) => None,
    }
}

// ─────────────────────────────────── settings ──────────────────────────────

pub async fn nvr_settings(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, AppError> {
    let cfg = state.config.get();
    let c = &cfg.runtime.nvr;
    let total: i64 = sqlx::query_scalar(
        "SELECT COALESCE(SUM(size_bytes),0) FROM nvr_segments",
    )
    .fetch_one(&state.db)
    .await
    .unwrap_or(0);
    Ok(Json(serde_json::json!({
        "enabled": c.enabled,
        "record_dir": c.record_dir,
        "timezone": c.timezone,
        "segment_secs": c.segment_secs,
        "expire_interval_mins": c.expire_interval_mins,
        "retain_days": c.retain_days,
        "max_gb": c.max_gb,
        "maintain_interval_secs": c.maintain_interval_secs,
        "video": {
            "source": c.video.source,
            "scale": c.video.scale,
            "fps": c.video.fps,
            "crf": c.video.crf,
            "maxrate": c.video.maxrate,
            "bufsize": c.video.bufsize,
            "encoder": c.video.encoder,
            "preset": c.video.preset,
            "audio": c.video.audio,
        },
        "total_size_bytes": total,
        "ffmpeg_available": state.nvr.as_ref().map(|s| s.ffmpeg.is_some()).unwrap_or(false),
    })))
}

#[derive(Deserialize)]
struct SettingsUpdate {
    retain_days: Option<i64>,
    max_gb: Option<i64>,
    segment_secs: Option<i64>,
    timezone: Option<String>,
    #[serde(default = "default_true")]
    enabled: bool,
}

fn default_true() -> bool {
    true
}

pub async fn update_nvr_settings(
    State(state): State<AppState>,
    _claims: Claims,
    Json(body): Json<SettingsUpdate>,
) -> Result<Json<serde_json::Value>, AppError> {
    if let Some(d) = body.retain_days {
        if !(1..=365).contains(&d) {
            return Err(AppError::BadRequest("retain_days must be 1..=365".into()));
        }
    }
    if let Some(g) = body.max_gb {
        if g < 0 || g > 100000 {
            return Err(AppError::BadRequest("max_gb must be 0..=100000 (0 = off)".into()));
        }
    }
    let mut cfg = state.config.get().as_ref().clone();
    let nvr = &mut cfg.runtime.nvr;
    if let Some(d) = body.retain_days {
        nvr.retain_days = d as u32;
    }
    if let Some(g) = body.max_gb {
        nvr.max_gb = g as u64;
    }
    if let Some(s) = body.segment_secs {
        if !(10..=600).contains(&s) {
            return Err(AppError::BadRequest("segment_secs must be 10..=600".into()));
        }
        nvr.segment_secs = s as u32;
    }
    if let Some(t) = &body.timezone {
        if t.parse::<chrono_tz::Tz>().is_err() {
            return Err(AppError::BadRequest("invalid timezone".into()));
        }
        nvr.timezone = t.clone();
    }
    if !body.enabled {
        nvr.enabled = false;
    }
    state
        .config
        .update(cfg)
        .map_err(|e| AppError::Internal(e.to_string()))?;
    Ok(Json(serde_json::json!({ "updated": true })))
}

pub async fn run_cleanup(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, AppError> {
    let nvr = nvr(&state)?;
    let before: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM nvr_segments")
            .fetch_one(&state.db)
            .await
            .unwrap_or(0);
    nvr::cleanup::round(nvr).await;
    let after: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM nvr_segments")
            .fetch_one(&state.db)
            .await
            .unwrap_or(0);
    Ok(Json(serde_json::json!({
        "removed": before.saturating_sub(after),
        "remaining": after,
    })))
}

// ─────────────────────────────── live preview (HLS) ────────────────────────

// ─────────────────────────────── live preview (HLS) ────────────────────────

/// Serve live preview media. One wildcard route handles both shapes:
///   `/api/nvr/live/{cam}`            → the m3u8 playlist (spawns ffmpeg
///                                       on demand, waits for first content)
///   `/api/nvr/live/{cam}/{file}`     → a `segNNNNNN.m4s` fragment
/// The playlist references fragments by bare name, so hls.js resolves them
/// to the second shape relative to the playlist URL.
/// 同 `playback`：只挂在数据面上，凭据由路由层的令牌中间件校验，不取 `Claims`。
pub async fn live_stream(
    State(state): State<AppState>,
    req: axum::extract::Request,
) -> Result<axum::response::Response, AppError> {
    let path = req.uri().path();
    let tail = path
        .strip_prefix("/api/nvr/live/")
        .filter(|t| !t.is_empty())
        .ok_or_else(|| AppError::NotFound("bad live path".into()))?;
    // hls.js resolves relative segment URIs against the playlist URL, so the
    // playlist is requested with a trailing slash (`/live/{cam}/`); accept
    // both forms.
    let tail = tail.trim_end_matches('/');
    if tail.is_empty() {
        return Err(AppError::NotFound("bad live path".into()));
    }
    let (cam_name, file) = tail
        .split_once('/')
        .map(|(c, f)| (c.to_string(), Some(f.to_string())))
        .unwrap_or_else(|| (tail.to_string(), None));

    let nvr = nvr(&state)?;
    let cam = nvr::get_camera_by_name(&state.db, &cam_name)
        .await
        .map_err(|_| AppError::NotFound("camera not found".into()))?;

    match file {
        None => {
            // Playlist: ensure the RTSP→HLS session exists, wait for the
            // first real content, then serve it.
            //
            // `?stream=main|sub` picks the source (default: the camera's
            // configured `preview_stream`, i.e. sub — a phone preview wants
            // the cheap stream). Switching restarts the relay.
            let stream = req
                .uri()
                .query()
                .and_then(|q| {
                    q.split('&')
                        .find_map(|kv| kv.strip_prefix("stream="))
                        .map(str::to_string)
                })
                .filter(|s| s == "main" || s == "sub")
                .unwrap_or_else(|| cam.preview_stream.clone());
            let state_arc = std::sync::Arc::clone(&state.nvr.as_ref().unwrap());
            let Some(list_path) = nvr.live.ensure(&state_arc, &cam, &stream).await else {
                return Ok(axum::response::Response::builder()
                    .status(axum::http::StatusCode::SERVICE_UNAVAILABLE)
                    .header(axum::http::header::CONTENT_TYPE, "text/plain; charset=utf-8")
                    .body(axum::body::Body::from("live preview unavailable (ffmpeg missing?)"))
                    .map_err(|e| AppError::Internal(e.to_string()))?);
            };
            let content = tokio::time::timeout(
                std::time::Duration::from_secs(8),
                read_stable_playlist(&list_path),
            )
            .await
            .map_err(|_| AppError::Internal("live stream slow to start".into()))?
            .ok_or_else(|| AppError::Internal("live playlist not ready".into()))?;
            nvr.live.touch(cam.id).await;
            // ffmpeg writes *relative* media URIs (`seg000000.m4s`) and a
            // relative init segment inside `#EXT-X-MAP:URI="init.mp4"`. hls.js
            // resolves both against the playlist URL, so `.../live/main`
            // (no trailing slash) would resolve them to `.../live/…` and drop
            // the camera — FragLoadError on the init segment, 404. Rewrite both
            // to absolute paths so the playlist is client-agnostic.
            let base = format!("/api/nvr/live/{cam_name}/");
            let content: String = content
                .lines()
                .map(|l| {
                    let t = l.trim();
                    // Bare media segment URI line (no tag).
                    if !t.is_empty() && !t.starts_with('#') && !t.starts_with('/') {
                        return format!("{base}{t}");
                    }
                    // #EXT-X-MAP:URI="init.mp4" — rewrite the init segment too.
                    if let Some(start) = l.find("URI=\"") {
                        let after = start + "URI=\"".len();
                        if let Some(rel_end) = l[after..].find('"') {
                            let rel = &l[after..after + rel_end];
                            if !rel.is_empty() && !rel.starts_with('/') {
                                return format!(
                                    "{}{}{}",
                                    &l[..after],
                                    format!("{base}{rel}"),
                                    &l[after + rel_end..]
                                );
                            }
                        }
                    }
                    l.to_string()
                })
                .collect::<Vec<_>>()
                .join("\n");
            Ok(axum::response::Response::builder()
                .header(axum::http::header::CONTENT_TYPE, "application/vnd.apple.mpegurl")
                .header(axum::http::header::CACHE_CONTROL, "no-cache")
                .body(axum::body::Body::from(content))
                .map_err(|e| AppError::Internal(e.to_string()))?)
        }
        Some(file) => {
            // Fragment: must be a bare name with an extension (no traversal).
            if !file.contains('.') || file.contains("..") {
                return Err(AppError::NotFound("no segment".into()));
            }
            let full = nvr.live.stream_dir(cam.id).join(&file);
            if !full.is_file() {
                return Err(AppError::NotFound("segment not found (stream starting?)".into()));
            }
            nvr.live.touch(cam.id).await;
            let bytes = tokio::fs::read(&full)
                .await
                .map_err(|e| AppError::Internal(e.to_string()))?;
            let ct = if file.ends_with(".m4s") {
                "video/mp4"
            } else {
                "application/octet-stream"
            };
            Ok(axum::response::Response::builder()
                .header(axum::http::header::CONTENT_TYPE, ct)
                .header(axum::http::header::CACHE_CONTROL, "no-store")
                .header(axum::http::header::CONTENT_LENGTH, bytes.len().to_string())
                .body(axum::body::Body::from(bytes))
                .map_err(|e| AppError::Internal(e.to_string()))?)
        }
    }
}

async fn read_stable_playlist(path: &std::path::Path) -> Option<String> {
    // ffmpeg rewrites the m3u8 *in place* on every segment, so a bare read
    // can land mid-rewrite — hls.js rejects that as ManifestParsingError.
    // Require a structurally complete playlist, then confirm a second read
    // 15 ms later is byte-identical (a mid-rewrite window is a few ms).
    let mut tries = 0u32;
    loop {
        if let Ok(a) = tokio::fs::read_to_string(path).await {
            if playlist_complete(&a) {
                tokio::time::sleep(std::time::Duration::from_millis(15)).await;
                if let Ok(b) = tokio::fs::read_to_string(path).await {
                    if b == a {
                        return Some(a);
                    }
                }
            }
        }
        tries += 1;
        if tries > 80 {
            return None;
        }
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    }
}

/// Structural sanity for an ffmpeg hls playlist: starts with `#EXTM3U`, has
/// at least one segment, and ends on a segment URI line (a trailing
/// `#EXTINF` without its URI means we read mid-rewrite).
fn playlist_complete(s: &str) -> bool {
    let t = s.trim();
    if !t.starts_with("#EXTM3U") || !t.contains("#EXTINF") {
        return false;
    }
    match t.lines().rev().find_map(|l| Some(l.trim()).filter(|l| !l.is_empty())) {
        Some(last) if !last.starts_with('#') => !last.contains(' '),
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn range_parsing() {
        assert_eq!(parse_byte_range("bytes=0-99", 1000), Some(Some((0, 99))));
        assert_eq!(
            parse_byte_range("bytes=900-", 1000),
            Some(Some((900, 999)))
        );
        assert_eq!(
            parse_byte_range("bytes=-100", 1000),
            Some(Some((900, 999)))
        );
        assert_eq!(parse_byte_range("bytes=2000-", 1000), Some(None));
        assert_eq!(parse_byte_range("nonsense", 1000), None);
    }

    #[test]
    fn playlist_completeness() {
        let ok = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXTINF:1.96,\n/api/nvr/live/main/seg000001.m4s\n";
        assert!(playlist_complete(ok));
        // mid-rewrite: trailing #EXTINF without its URI line
        assert!(!playlist_complete(
            "#EXTM3U\n#EXTINF:1.96,\n"
        ));
        assert!(!playlist_complete(""));
        assert!(!playlist_complete("garbage html\n"));
        // segment URI with a space in the name is not a clean end
        assert!(!playlist_complete("#EXTM3U\n#EXTINF:1,\nfoo bar.m4s\n"));
    }
}
