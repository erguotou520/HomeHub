//! Management API (`/api/admin/*`, protected by the admin JWT) plus the
//! login route and the system information endpoint.

use axum::{
    extract::{Path, Query, State},
    routing::{delete, get, post, put},
    Json, Router,
};
use serde::{Deserialize, Serialize};

use crate::config::{DirConfig, RuntimeSettings};
use crate::middleware::admin_auth::{create_token, Claims};
use crate::models::task::TaskKind;
use crate::services::tasks::TaskQueue;
use crate::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/api/admin/login", post(login))
        .route("/api/admin/me", get(me))
        .route("/api/admin/fs", get(browse_fs))
        .route("/api/admin/dirs", get(list_dirs))
        .route("/api/admin/dirs", post(create_dir))
        .route("/api/admin/dirs/:id", put(update_dir))
        .route("/api/admin/dirs/:id", delete(delete_dir))
        .route("/api/admin/dirs/:id/enabled", post(set_dir_enabled))
        .route("/api/admin/peers", get(list_peers))
        .route("/api/admin/peers", post(create_peer))
        .route("/api/admin/peers/:id", put(update_peer))
        .route("/api/admin/peers/:id/logs", get(peer_logs))
        .route("/api/admin/traffic", get(traffic))
        .route("/api/admin/tasks", get(task_status))
        .route("/api/admin/tasks/rescan", post(rescan))
        .route("/api/admin/tasks/retry", post(retry_failed))
        .route("/api/admin/tasks/failed", get(failed_tasks))
        .route("/api/admin/tasks/failed", delete(clear_failed))
        .route("/api/admin/settings", get(get_settings))
        .route("/api/admin/settings", put(update_settings))
        .route("/api/admin/stats", get(stats))
        .route("/api/admin/system", get(system_info))
        .route("/api/admin/alerts", get(list_alerts))
        .route("/api/admin/alerts/resolve", post(resolve_alerts))
        .route("/api/admin/alerts/test", post(test_alert))
        .route("/api/admin/duplicates", get(duplicates))
        .route("/api/admin/duplicates/trash", post(trash_duplicates))
        // SQLite snapshots (PRD §6)
        .route("/api/admin/backups", get(list_backups))
        .route("/api/admin/backups/run", post(run_backup))
        // People (face clusters)
        .route("/api/admin/people/:id/merge", post(merge_people))
        .route("/api/admin/people/:id/unassign", post(unassign_face))
        .route("/api/admin/people/recluster", post(recluster_people))
}

// ─────────────────────────────────── auth ──────────────────────────────────

#[derive(Deserialize)]
pub struct LoginRequest {
    pub password: String,
}

#[derive(Serialize)]
pub struct LoginResponse {
    pub token: String,
    pub expires_in: i64,
}

pub async fn login(
    State(state): State<AppState>,
    Json(body): Json<LoginRequest>,
) -> Result<Json<LoginResponse>, crate::models::AppError> {
    let config = state.config.get();
    if !config.admin.verify(&body.password) {
        return Err(crate::models::AppError::Unauthorized(
            "invalid password".into(),
        ));
    }
    let ttl = 24 * 7;
    let token = create_token(&state.jwt_secret, ttl)
        .map_err(|e| crate::models::AppError::Internal(e.to_string()))?;
    Ok(Json(LoginResponse {
        token,
        expires_in: ttl * 3600,
    }))
}

pub async fn me(claims: Claims) -> Json<serde_json::Value> {
    Json(serde_json::json!({ "sub": claims.sub, "role": claims.role }))
}

// ───────────────────────────── filesystem browsing ─────────────────────────

#[derive(Deserialize)]
pub struct FsQuery {
    /// Directory to list; empty means the server home directory.
    path: Option<String>,
}

#[derive(Serialize)]
pub struct FsEntry {
    pub name: String,
    pub path: String,
}

/// List subdirectories of `path` for the admin directory picker.
/// Only directories are returned; dotfiles/dotdirs are skipped so the
/// picker stays readable (the path input remains free-form for edge cases).
pub async fn browse_fs(
    _claims: Claims,
    Query(q): Query<FsQuery>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    use std::path::PathBuf;

    let path: PathBuf = match q.path.as_deref() {
        None | Some("") => std::env::var("HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|_| PathBuf::from("/")),
        Some(p) => PathBuf::from(p),
    };
    if !path.is_dir() {
        return Err(crate::models::AppError::BadRequest(format!(
            "'{}' is not a directory",
            path.display()
        )));
    }

    let mut rd = tokio::fs::read_dir(&path)
        .await
        .map_err(|e| crate::models::AppError::BadRequest(format!("cannot read {}: {e}", path.display())))?;
    let mut entries: Vec<FsEntry> = Vec::new();
    while let Some(e) = rd.next_entry().await.map_err(|e| crate::models::AppError::Internal(e.to_string()))? {
        let name = e.file_name().to_string_lossy().to_string();
        if name.starts_with('.') {
            continue;
        }
        if !e.file_type().await.map(|t| t.is_dir()).unwrap_or(false) {
            continue;
        }
        entries.push(FsEntry {
            name,
            path: e.path().to_string_lossy().to_string(),
        });
    }
    entries.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));

    let parent = path
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .map(|p| p.to_string_lossy().to_string());
    Ok(Json(serde_json::json!({
        "path": path.to_string_lossy(),
        "parent": parent,
        "entries": entries,
    })))
}

// ─────────────────────────────── directory registry ────────────────────────

pub async fn list_dirs(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let stats = state.registry.stats().await?;
    Ok(Json(serde_json::json!({ "dirs": stats })))
}

pub async fn create_dir(
    State(state): State<AppState>,
    _claims: Claims,
    Json(body): Json<DirConfig>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    if body.name.trim().is_empty() || body.path.trim().is_empty() {
        return Err(crate::models::AppError::BadRequest(
            "name and path are required".into(),
        ));
    }
    let dir = state.registry.create(&body).await?;
    persist_dirs(&state).await?;
    // A disabled directory is not part of the library, so scanning it would
    // only produce `file missing` failures for a path the user opted out of.
    if dir.is_enabled() {
        state
            .queue
            .enqueue_scan(dir.id, true, crate::services::tasks::PRIORITY_HIGH)
            .await?;
    }
    Ok(Json(serde_json::json!({ "dir": dir })))
}

pub async fn update_dir(
    State(state): State<AppState>,
    _claims: Claims,
    Path(id): Path<i64>,
    Json(body): Json<DirConfig>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let dir = state.registry.update(id, &body).await?;
    persist_dirs(&state).await?;
    if dir.is_enabled() {
        state
            .queue
            .enqueue_scan(dir.id, true, crate::services::tasks::PRIORITY_HIGH)
            .await?;
    }
    Ok(Json(serde_json::json!({ "dir": dir })))
}

pub async fn delete_dir(
    State(state): State<AppState>,
    _claims: Claims,
    Path(id): Path<i64>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    crate::services::search::remove_dir(&state.db, id).await?;
    state.registry.delete(id).await?;
    persist_dirs(&state).await?;
    Ok(Json(serde_json::json!({ "success": true })))
}

#[derive(Deserialize)]
pub struct EnabledBody {
    pub enabled: bool,
}

pub async fn set_dir_enabled(
    State(state): State<AppState>,
    _claims: Claims,
    Path(id): Path<i64>,
    Json(body): Json<EnabledBody>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    state.registry.set_enabled(id, body.enabled).await?;
    persist_dirs(&state).await?;
    // Enabling a directory puts it back in the library, so index it again.
    // Disabling needs no scan: `TaskQueue::claim` drops the queued work of any
    // directory that is no longer enabled.
    if body.enabled {
        state
            .queue
            .enqueue_scan(id, true, crate::services::tasks::PRIORITY_HIGH)
            .await?;
    }
    Ok(Json(serde_json::json!({ "success": true })))
}

/// Write the registry back to `config.yaml` so changes survive a restart.
async fn persist_dirs(state: &AppState) -> Result<(), crate::models::AppError> {
    let dirs: Vec<DirConfig> = state
        .registry
        .all()
        .into_iter()
        .map(|d| DirConfig {
            name: d.name.clone(),
            path: d.path.clone(),
            marks: d.marks_vec().iter().map(|m| m.as_str().to_string()).collect(),
            ignore: d.ignore_list(),
            enabled: d.is_enabled(),
        })
        .collect();
    state.config.update_dirs(dirs)?;
    Ok(())
}

// ──────────────────────────────── WireGuard peers ──────────────────────────

pub async fn list_peers(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let peers = crate::services::audit::list_peers(&state.db).await?;
    Ok(Json(serde_json::json!({ "peers": peers })))
}

#[derive(Deserialize)]
pub struct PeerBody {
    pub name: String,
    pub tunnel_ip: String,
    pub public_key: Option<String>,
    pub enabled: Option<bool>,
}

pub async fn create_peer(
    State(state): State<AppState>,
    _claims: Claims,
    Json(body): Json<PeerBody>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let id = crate::services::audit::upsert_peer(
        &state.db,
        body.public_key.as_deref(),
        &body.name,
        &body.tunnel_ip,
        "manual",
    )
    .await?;
    if let Some(enabled) = body.enabled {
        crate::services::audit::set_peer_enabled(&state.db, id, enabled).await?;
    }
    Ok(Json(serde_json::json!({ "id": id })))
}

pub async fn update_peer(
    State(state): State<AppState>,
    _claims: Claims,
    Path(id): Path<i64>,
    Json(body): Json<PeerBody>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    crate::services::audit::set_peer_name(&state.db, id, &body.name).await?;
    if let Some(enabled) = body.enabled {
        crate::services::audit::set_peer_enabled(&state.db, id, enabled).await?;
    }
    Ok(Json(serde_json::json!({ "success": true })))
}

#[derive(Deserialize)]
pub struct LogQuery {
    pub limit: Option<i64>,
    pub offset: Option<i64>,
}

pub async fn peer_logs(
    State(state): State<AppState>,
    _claims: Claims,
    Path(id): Path<i64>,
    Query(q): Query<LogQuery>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let logs = crate::services::audit::logs(
        &state.db,
        Some(id),
        q.limit.unwrap_or(100),
        q.offset.unwrap_or(0),
    )
    .await?;
    Ok(Json(serde_json::json!({ "logs": logs })))
}

pub async fn traffic(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let traffic = crate::services::audit::traffic(&state.db).await?;
    Ok(Json(serde_json::json!({ "traffic": traffic })))
}

// ───────────────────────────────── task centre ─────────────────────────────

pub async fn task_status(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let queues = state.queue.queue_status().await?;
    let throughput = state.queue.throughput(300).await?;
    Ok(Json(serde_json::json!({
        "queues": queues,
        "running": state.queue.running_now(),
        "throughput_5min": throughput,
    })))
}

#[derive(Deserialize, Default)]
pub struct RescanBody {
    pub dir_id: Option<i64>,
    pub full: Option<bool>,
}

pub async fn rescan(
    State(state): State<AppState>,
    _claims: Claims,
    body: Option<Json<RescanBody>>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let body = body.map(|b| b.0).unwrap_or_default();
    let full = body.full.unwrap_or(true);
    let mut n = 0;
    match body.dir_id {
        Some(dir_id) => {
            // Scanning a disabled directory would queue work that the worker
            // pool now refuses to run (`TaskQueue::claim`), so reject it here
            // with a reason instead of silently queueing nothing.
            let dir = state.registry.by_id(dir_id).ok_or_else(|| {
                crate::models::AppError::BadRequest(format!("dir {dir_id} not registered"))
            })?;
            if !dir.is_enabled() {
                return Err(crate::models::AppError::BadRequest(format!(
                    "目录 {} 已停用，不参与扫描",
                    dir.name
                )));
            }
            state.queue.force_scan(dir_id, full).await?;
            n += 1;
        }
        None => {
            for dir in state.registry.enabled() {
                state.queue.force_scan(dir.id, full).await?;
                n += 1;
            }
        }
    }
    Ok(Json(serde_json::json!({ "queued": n })))
}

pub async fn retry_failed(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let n = state.queue.retry_failed().await?;
    Ok(Json(serde_json::json!({ "retried": n })))
}

pub async fn failed_tasks(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let tasks = state.queue.failed(100).await?;
    Ok(Json(serde_json::json!({ "tasks": tasks })))
}

pub async fn clear_failed(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let n = state.queue.clear_failed().await?;
    Ok(Json(serde_json::json!({ "cleared": n })))
}

// ────────────────────────────────── settings ───────────────────────────────

/// Placeholder sent to the browser in place of a stored secret.
///
/// `GET /api/admin/settings` used to hand back the push credentials in
/// cleartext. They are masked now; `update_settings` recognises the mask (or an
/// empty field) and keeps whatever is stored, so saving the form can never wipe
/// a secret by accident.
const SECRET_MASK: &str = "••••••••";

fn mask_secret(value: &str) -> String {
    if value.is_empty() {
        String::new()
    } else {
        SECRET_MASK.to_string()
    }
}

fn is_masked(value: &str) -> bool {
    value.is_empty() || value == SECRET_MASK
}

pub async fn get_settings(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let mut settings = state.config.get().runtime.clone();
    settings.alerts.serverchan.send_key = mask_secret(&settings.alerts.serverchan.send_key);
    Ok(Json(serde_json::to_value(settings).unwrap_or_default()))
}

pub async fn update_settings(
    State(state): State<AppState>,
    _claims: Claims,
    Json(body): Json<RuntimeSettings>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    // Deep-merge the incoming settings over the stored ones instead of
    // replacing wholesale: a client that fetched a stale snapshot must not
    // silently roll back sections it did not edit (bitten once already).
    let stored_raw = crate::db::get_setting(&state.db, "runtime_settings")
        .await?
        .unwrap_or_else(|| "null".to_string());
    let mut merged =
        serde_json::from_str::<serde_json::Value>(&stored_raw).unwrap_or_else(|_| serde_json::json!({}));
    let incoming = serde_json::to_value(&body)?;
    merge_json(&mut merged, &incoming);
    let mut settings: RuntimeSettings = serde_json::from_value(merged).map_err(|e| {
        crate::models::AppError::BadRequest(format!("merged settings invalid: {e}"))
    })?;

    // Masked / blank secrets mean "unchanged" — put the stored value back.
    // Applied *after* the merge rather than before it, so it covers both a
    // client that sent the mask and one that sent nothing at all.
    let current = state.config.get().runtime.alerts.clone();
    if is_masked(&settings.alerts.serverchan.send_key) {
        settings.alerts.serverchan.send_key = current.serverchan.send_key;
    }

    let serialized = serde_json::to_string(&settings)?;
    crate::db::set_setting(&state.db, "runtime_settings", &serialized).await?;

    let mut next = (*state.config.get()).clone();
    next.runtime = settings;
    state.config.update(next)?;
    state.queue.reload_limits();

    Ok(Json(serde_json::json!({ "success": true })))
}

/// Recursively merge `src` into `dst`: objects merge key-by-key, everything
/// else (scalars, arrays, null) is replaced by `src`.
fn merge_json(dst: &mut serde_json::Value, src: &serde_json::Value) {
    match (dst, src) {
        (serde_json::Value::Object(d), serde_json::Value::Object(s)) => {
            for (k, v) in s {
                match d.get_mut(k) {
                    Some(slot) if slot.is_object() && v.is_object() => merge_json(slot, v),
                    _ => {
                        d.insert(k.clone(), v.clone());
                    }
                }
            }
        }
        (dst, src) => *dst = src.clone(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::RuntimeSettings;

    /// Regression: a stored blob written before the `send_key` → `send-key`
    /// rename still carries the old spelling. The deep merge keeps both keys,
    /// so if the field also declared `alias = "send_key"` serde rejected the
    /// object as `duplicate field` and **every** save from the admin form
    /// answered 400. The stale key must be ignored instead, and the incoming
    /// kebab-case value must win.
    #[test]
    fn stale_snake_case_key_does_not_break_the_settings_merge() {
        let mut stored = serde_json::json!({
            "alerts": { "serverchan": { "enabled": false, "send_key": "" } }
        });
        let incoming = serde_json::json!({
            "alerts": { "serverchan": { "enabled": true, "send-key": "SCT123" } }
        });

        merge_json(&mut stored, &incoming);
        let settings: RuntimeSettings = serde_json::from_value(stored)
            .expect("legacy snake_case key must not poison the merged object");

        assert!(settings.alerts.serverchan.enabled);
        assert_eq!(settings.alerts.serverchan.send_key, "SCT123");
        // Re-serialising from the struct is what heals the stored blob and
        // config.yaml: the stale key is simply not part of the output.
        let rewritten = serde_json::to_value(&settings).unwrap();
        assert!(rewritten["alerts"]["serverchan"].get("send_key").is_none());
        assert_eq!(rewritten["alerts"]["serverchan"]["send-key"], "SCT123");
    }

    /// The merge must not drop sections the client did not send — the reason
    /// it exists in the first place.
    #[test]
    fn merge_keeps_untouched_sections() {
        let mut stored = serde_json::json!({
            "tasks": { "max-attempts": 3 },
            "alerts": { "disk-usage-percent": 85.0 }
        });
        let incoming = serde_json::json!({ "alerts": { "disk-usage-percent": 70.0 } });

        merge_json(&mut stored, &incoming);
        assert_eq!(stored["alerts"]["disk-usage-percent"], 70.0);
        assert_eq!(stored["tasks"]["max-attempts"], 3);
    }
}

// ─────────────────────────────────── stats ─────────────────────────────────

pub async fn stats(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let dirs = state.registry.stats().await?;
    let photo_count: (i64,) =
        sqlx::query_as("SELECT COUNT(*) FROM photo_assets WHERE status = 'ok'")
            .fetch_one(&state.db)
            .await
            .unwrap_or((0,));
    let tagged: (i64,) = sqlx::query_as(
        "SELECT COUNT(DISTINCT photo_id) FROM photo_tags",
    )
    .fetch_one(&state.db)
    .await
    .unwrap_or((0,));
    let videos: (i64,) =
        sqlx::query_as("SELECT COUNT(*) FROM photo_assets WHERE status = 'ok' AND media_kind = 'video'")
            .fetch_one(&state.db)
            .await
            .unwrap_or((0,));
    let faces: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM faces")
        .fetch_one(&state.db)
        .await
        .unwrap_or((0,));
    let people: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM person_groups")
        .fetch_one(&state.db)
        .await
        .unwrap_or((0,));
    let (trash_count, trash_bytes) = crate::services::trash::usage(&state.db).await?;
    let (originals_count, originals_bytes) =
        crate::services::originals::usage(&state.config.get()).await?;

    Ok(Json(serde_json::json!({
        "dirs": dirs,
        "photos": photo_count.0,
        "videos": videos.0,
        "tagged": tagged.0,
        "faces": faces.0,
        "people": people.0,
        "trash": { "count": trash_count, "bytes": trash_bytes },
        "originals": { "count": originals_count, "bytes": originals_bytes },
    })))
}

pub async fn system_info(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let config = state.config.get();
    let db_bytes = std::fs::metadata(config.db_path())
        .map(|m| m.len())
        .unwrap_or(0);
    let alerts = crate::services::alerts::list(&state.db, 50).await?;
    let disk = crate::services::alerts::disk_usage_percent(std::path::Path::new(&config.global.data_dir));
    let backups = crate::services::backup::status(&state.db, &config).await?;

    Ok(Json(serde_json::json!({
        "version": env!("CARGO_PKG_VERSION"),
        "uptime_secs": crate::db::now() - state.started_at,
        "db_bytes": db_bytes,
        "disk_usage_percent": disk,
        "started_at": state.started_at,
        "alerts": alerts,
        "ml_backend": state.queue.detector().name(),
        "data_dir": config.global.data_dir,
        "backup": {
            "enabled": backups.enabled,
            "interval_hours": backups.interval_hours,
            "keep": backups.keep,
            "dir": backups.dir,
            "count": backups.items.len(),
            "bytes": backups.total_bytes,
            "last_backup_at": backups.last_backup_at,
            "last_error": backups.last_error,
        },
    })))
}

// ─────────────────────────────────── alerts ────────────────────────────────

pub async fn list_alerts(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let alerts = crate::services::alerts::list(&state.db, 200).await?;
    Ok(Json(serde_json::json!({ "alerts": alerts })))
}

#[derive(Deserialize)]
pub struct ResolveBody {
    pub kind: Option<String>,
}

pub async fn resolve_alerts(
    State(state): State<AppState>,
    _claims: Claims,
    body: Option<Json<ResolveBody>>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let kind = body.and_then(|b| b.0.kind).unwrap_or_else(|| "%".to_string());
    let res = sqlx::query(
        "UPDATE alerts SET resolved_at = ? WHERE resolved_at IS NULL AND kind LIKE ?",
    )
    .bind(crate::db::now())
    .bind(kind)
    .execute(&state.db)
    .await?;
    Ok(Json(serde_json::json!({ "resolved": res.rows_affected() })))
}

pub async fn test_alert(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let config = state.config.get();
    let raised = crate::services::alerts::raise(
        &state.db,
        &config,
        crate::models::alert::AlertLevel::Info,
        "test",
        "这是一条来自 HomeHub 的测试告警",
    )
    .await?;
    if !raised {
        return Err(crate::models::AppError::BadRequest(
            "alerting disabled or duplicate suppressed".into(),
        ));
    }
    Ok(Json(serde_json::json!({ "sent": true })))
}

// ────────────────────────────────── backups ────────────────────────────────

/// Snapshot list + configuration for the "系统信息" page.
pub async fn list_backups(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let status = crate::services::backup::status(&state.db, &state.config.get()).await?;
    Ok(Json(serde_json::to_value(status).unwrap_or_default()))
}

/// Run a snapshot right now (equivalent to enqueuing `backup`, but synchronous
/// so the UI can report success/failure immediately).
pub async fn run_backup(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let path = crate::services::backup::run(&state.db, &state.config.get()).await?;
    Ok(Json(serde_json::json!({
        "success": true,
        "name": path.file_name().map(|n| n.to_string_lossy().to_string()),
    })))
}

// ───────────────────────────── people management ───────────────────────────

#[derive(Deserialize)]
pub struct MergePeopleBody {
    /// Group that will be dissolved into `:id`.
    pub source_id: i64,
}

pub async fn merge_people(
    State(state): State<AppState>,
    _claims: Claims,
    Path(id): Path<i64>,
    Json(body): Json<MergePeopleBody>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let moved = crate::services::ml::cluster::merge_groups(
        &state.db,
        body.source_id,
        id,
        &state.config.get().runtime.ml.face,
    )
    .await?;
    Ok(Json(serde_json::json!({ "moved": moved })))
}

#[derive(Deserialize)]
pub struct UnassignFaceBody {
    pub face_id: i64,
}

pub async fn unassign_face(
    State(state): State<AppState>,
    _claims: Claims,
    Path(_id): Path<i64>,
    Json(body): Json<UnassignFaceBody>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    crate::services::ml::cluster::unassign_face(&state.db, body.face_id).await?;
    Ok(Json(serde_json::json!({ "success": true })))
}

/// Recompute every cluster from scratch (after a model or threshold change).
pub async fn recluster_people(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let cfg = state.config.get().runtime.ml.face.clone();
    let n = crate::services::ml::cluster::recluster_all(&state.db, &cfg).await?;
    Ok(Json(serde_json::json!({ "faces": n })))
}

// ───────────────────────────────── duplicates ──────────────────────────────

pub async fn duplicates(
    State(state): State<AppState>,
    _claims: Claims,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let groups = crate::services::dedup::scan(&state.db, &state.registry).await?;
    Ok(Json(serde_json::json!({ "groups": groups })))
}

#[derive(Deserialize)]
pub struct DuplicateBody {
    pub fingerprint: String,
}

pub async fn trash_duplicates(
    State(state): State<AppState>,
    _claims: Claims,
    Json(body): Json<DuplicateBody>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let config = state.config.get();
    let moved = crate::services::dedup::move_group_to_trash(
        &state.db,
        &config,
        &state.registry,
        &body.fingerprint,
    )
    .await?;
    Ok(Json(serde_json::json!({ "trashed": moved })))
}

/// Enqueue a one-off maintenance task (used by the admin UI buttons).
pub async fn run_maintenance(
    State(state): State<AppState>,
    _claims: Claims,
    Json(body): Json<MaintenanceBody>,
) -> Result<Json<serde_json::Value>, crate::models::AppError> {
    let kind = TaskKind::parse(&body.kind)
        .ok_or_else(|| crate::models::AppError::BadRequest("unknown task kind".into()))?;
    let queue: &TaskQueue = &state.queue;
    queue
        .enqueue_kind(kind, "{}", crate::services::tasks::PRIORITY_HIGH)
        .await?;
    Ok(Json(serde_json::json!({ "queued": body.kind })))
}

#[derive(Deserialize)]
pub struct MaintenanceBody {
    pub kind: String,
}

/// Extra routes that need a body (kept separate to keep `routes()` tidy).
pub fn maintenance_routes() -> Router<AppState> {
    Router::new().route("/api/admin/tasks/run", post(run_maintenance))
}
