//! NVR: per-camera RTSP recorders writing 60s MP4 segments, a maintenance
//! loop that probes/renames/registers completed segments, and a periodic
//! cleanup loop (retain_days + optional max_gb). See `docs/design/nvr.md`.

pub mod cleanup;
pub mod live;
pub mod maintainer;
pub mod motion;
pub mod onvif;
pub mod recorder;

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use tokio::sync::{Mutex, RwLock};

use crate::config::ConfigStore;
use crate::db::Db;

/// In-memory recorder bookkeeping, one entry per camera.
pub struct RecorderState {
    pub status: String, // "recording" | "down" | "disabled"
    pub restart_count: u32,
    pub last_error: Option<String>,
    /// Child process handle; `take()`n on restart/stop.
    pub child: Option<tokio::process::Child>,
}

impl Default for RecorderState {
    fn default() -> Self {
        Self {
            status: "disabled".into(),
            restart_count: 0,
            last_error: None,
            child: None,
        }
    }
}

/// Everything the background loops + API handlers need, shared as one `Arc`.
pub struct NvrState {
    pub db: Db,
    pub config: Arc<ConfigStore>,
    /// Absolute root for recordings (`{record_dir}`), e.g. `…/data/recordings`.
    pub record_root: PathBuf,
    /// Flat cache dir ffmpeg writes into: `record_root/.cache`.
    pub cache_dir: PathBuf,
    pub recorders: RwLock<HashMap<i64, RecorderState>>,
    /// Resolved ffmpeg/ffprobe paths (None = tools missing, NVR degrades).
    pub ffmpeg: Option<String>,
    pub ffprobe: Option<String>,
    /// On-demand RTSP→HLS live-preview hub (idle sessions reaped).
    pub live: std::sync::Arc<live::LiveHub>,
    /// Consecutive ffprobe failures per cache file, so a segment that can
    /// never be probed is dropped instead of retried on every maintain round.
    pub probe_failures: Mutex<std::collections::HashMap<String, u32>>,
    /// Resolved ONVIF media-profile tokens, keyed by camera name.
    pub onvif_tokens: onvif::TokenCache,
}

impl NvrState {
    pub fn new(db: Db, config: Arc<ConfigStore>, record_root: PathBuf) -> Self {
        let cache_dir = record_root.join(".cache");
        let video = config.get().runtime.video.clone();
        // Live-preview hub: stream files go under `{record_root}/live` so
        // they ride the same volume as recordings.
        let live = live::spawn_hub(record_root.as_path());
        let state = Self {
            ffmpeg: crate::services::probe::resolve(&video.ffmpeg_path, "ffmpeg"),
            ffprobe: crate::services::probe::resolve(&video.ffprobe_path, "ffprobe"),
            db,
            config,
            record_root,
            cache_dir,
            recorders: RwLock::new(HashMap::new()),
            live,
            probe_failures: Mutex::new(std::collections::HashMap::new()),
            onvif_tokens: onvif::TokenCache::default(),
        };
        std::fs::create_dir_all(&state.cache_dir).ok();
        state
    }

    /// `path` is a `nvr_segments.path` value relative to the data dir; map it
    /// onto the record root (paths start with `recordings/...`).
    pub fn segment_abs_path(&self, rel: &str) -> PathBuf {
        let p = Path::new(rel);
        match p.strip_prefix("recordings") {
            Ok(rest) => self.record_root.join(rest),
            Err(_) => self.record_root.join(rel),
        }
    }
}

/// Start the NVR background loops and return the shared state (mounted into
/// `AppState` for the API routes). No-op (`None`) when `nvr.enabled` is
/// false. Safe to call only once (spawns three long-running tasks).
pub async fn spawn(db: Db, config: Arc<ConfigStore>) -> Option<Arc<NvrState>> {
    let nvr_cfg = config.get().runtime.nvr.clone();
    if !nvr_cfg.enabled {
        tracing::info!("nvr disabled by config");
        return None;
    }
    let record_root = nvr_cfg.record_root(config.get().global.data_dir.as_str());
    let root_display = record_root.display().to_string();
    let state = Arc::new(NvrState::new(db, config.clone(), record_root));

    if state.ffmpeg.is_none() {
        tracing::error!("nvr enabled but ffmpeg not found on PATH; NVR idle");
        return Some(state);
    }
    if state.ffprobe.is_none() {
        tracing::error!("nvr enabled but ffprobe not found on PATH; NVR idle");
        return Some(state);
    }

    // Startup sweep: remove orphan cache files + orphan segments (>1 day old).
    if let Err(e) = cleanup::startup_sweep(&state).await {
        tracing::warn!("nvr startup sweep failed: {e}");
    }

    let maintain = state.clone();
    let maintain_cfg = nvr_cfg.maintain_interval_secs.max(2) as u64;
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(Duration::from_secs(maintain_cfg)).await;
            maintainer::round(&maintain).await;
        }
    });

    let clean = state.clone();
    let clean_cfg = (nvr_cfg.expire_interval_mins.max(5) * 60) as u64;
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(Duration::from_secs(clean_cfg)).await;
            cleanup::round(&clean).await;
        }
    });

    let mgr = state.clone();
    tokio::spawn(async move {
        recorder::manage_loop(mgr).await;
    });

    tracing::info!(
        "nvr started (root={}, segment={}s)",
        root_display,
        nvr_cfg.segment_secs
    );
    Some(state)
}

/// Camera rows from the DB.
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct Camera {
    pub id: i64,
    pub name: String,
    pub label: Option<String>,
    /// Main stream (`stream1` on the reference device).
    pub rtsp_url: String,
    /// Sub stream (`stream2`). `None` = derive from `rtsp_url`; see
    /// [`Camera::url_for_stream`].
    pub rtsp_sub_url: Option<String>,
    pub enabled: bool,
    pub record_stream: i64,
    pub record_audio: bool,
    /// Which stream the live-preview relay pulls: `"sub"` (default) or `"main"`.
    pub preview_stream: String,
    pub created_at: i64,
    pub updated_at: i64,
}

/// All columns of `cameras`, in the order [`Camera`] expects. Kept in one
/// place because four queries select it (list / get by id / get by name /
/// recorder) and a missing column only fails at runtime.
pub const CAMERA_COLUMNS: &str = "id, name, label, rtsp_url, rtsp_sub_url, enabled, \
     record_stream, record_audio, preview_stream, created_at, updated_at";

impl Camera {
    /// RTSP URL for a stream index: `1` = main, `2` = sub.
    ///
    /// The sub stream falls back to rewriting `/stream1` → `/stream2` when the
    /// camera has no explicit `rtsp_sub_url` (every pre-011 row), so behaviour
    /// is unchanged for cameras nobody has re-configured.
    pub fn url_for_stream(&self, stream: i64) -> String {
        if stream != 2 {
            return self.rtsp_url.clone();
        }
        if let Some(sub) = self
            .rtsp_sub_url
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
        {
            return sub.to_string();
        }
        derive_sub_stream(&self.rtsp_url)
    }

    /// Stream URL for the live preview relay, from `preview_stream`.
    pub fn url_for_preview(&self) -> String {
        self.url_for_stream(if self.preview_stream == "main" { 1 } else { 2 })
    }
}

/// `/stream1` → `/stream2` (Hikvision-style path). URLs without the marker are
/// returned as-is: the device can't switch streams, so pretending otherwise
/// would just produce a URL that 404s.
pub fn derive_sub_stream(main: &str) -> String {
    if main.contains("/stream2") {
        main.to_string()
    } else if main.contains("/stream1") {
        main.replace("/stream1", "/stream2")
    } else {
        main.to_string()
    }
}

/// Cameras with their live recorder status, for the admin UI.
pub struct CameraView {
    pub id: i64,
    pub name: String,
    pub label: Option<String>,
    /// Credentials stripped: `rtsp://***:***@host/stream1`.
    pub rtsp_url: String,
    /// Credentials stripped; `None` when the camera has no explicit sub stream.
    pub rtsp_sub_url: Option<String>,
    pub enabled: bool,
    pub record_stream: i64,
    pub record_audio: bool,
    pub preview_stream: String,
    pub status: String,
    pub restart_count: u32,
    pub last_error: Option<String>,
    pub last_segment_at: Option<i64>,
    pub today_segments: i64,
    pub total_size_bytes: i64,
    pub total_segments: i64,
}

pub fn mask_rtsp_url(url: &str) -> String {
    let Some(scheme_end) = url.find("://") else {
        return url.to_string();
    };
    let scheme = &url[..scheme_end + 3];
    let rest = &url[scheme_end + 3..];
    let Some(at) = rest.rfind('@') else {
        return url.to_string();
    };
    let authority = &rest[at + 1..];
    format!("{scheme}***:***@{authority}")
}

/// List cameras with recorder status.
///
/// `mask` strips the credentials from the RTSP URLs. The **device** route
/// (`/api/nvr/cameras`, no auth) masks; the **admin** route
/// (`/api/admin/nvr/cameras`, password-protected) does not. That asymmetry is
/// load-bearing: the admin camera form round-trips whatever it is given, so
/// handing it `rtsp://***:***@host/stream1` and saving would overwrite the
/// stored credentials with the mask and silently break recording.
pub async fn list_cameras(state: &Arc<NvrState>, mask: bool) -> anyhow::Result<Vec<CameraView>> {
    let rows = sqlx::query_as::<_, Camera>(&format!(
        "SELECT {CAMERA_COLUMNS} FROM cameras ORDER BY id"
    ))
    .fetch_all(&state.db)
    .await?;

    let recorders = state.recorders.read().await;
    let now = chrono::Utc::now().timestamp();
    let tz: chrono_tz::Tz = (&state.config.get().runtime.nvr.timezone)
        .parse()
        .unwrap_or(chrono_tz::Tz::Asia__Shanghai);
    // Start of the local day (unix seconds) for the "today" counters.
    let today_start: i64 = chrono::DateTime::from_timestamp(now, 0)
        .and_then(|dt| {
            let local = dt.with_timezone(&tz);
            local
                .date_naive()
                .and_hms_opt(0, 0, 0)
                .and_then(|midnight| midnight.and_local_timezone(tz).earliest())
                .map(|d| d.timestamp())
        })
        .unwrap_or(now - 86400);

    let mut out = Vec::with_capacity(rows.len());
    for cam in rows {
        let (status, restarts, last_error) = match recorders.get(&cam.id) {
            Some(r) => (r.status.clone(), r.restart_count, r.last_error.clone()),
            None => ("disabled".into(), 0, None),
        };
        let stats: (Option<i64>, i64, i64) = sqlx::query_as(
            "SELECT MAX(end_time), COUNT(*), COALESCE(SUM(size_bytes),0) FROM nvr_segments WHERE camera_id = ?",
        )
        .bind(cam.id)
        .fetch_one(&state.db)
        .await?;
        let today: (i64,) = sqlx::query_as(
            "SELECT COUNT(*) FROM nvr_segments WHERE camera_id = ? AND start_time >= ?",
        )
        .bind(cam.id)
        .bind(today_start)
        .fetch_one(&state.db)
        .await?;
        let hide = |u: &str| if mask { mask_rtsp_url(u) } else { u.to_string() };
        out.push(CameraView {
            id: cam.id,
            name: cam.name.clone(),
            label: cam.label.clone(),
            rtsp_url: hide(&cam.rtsp_url),
            rtsp_sub_url: cam
                .rtsp_sub_url
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(hide),
            enabled: cam.enabled,
            record_stream: cam.record_stream,
            record_audio: cam.record_audio,
            preview_stream: cam.preview_stream.clone(),
            status,
            restart_count: restarts,
            last_error,
            last_segment_at: stats.0,
            today_segments: today.0,
            total_size_bytes: stats.2,
            total_segments: stats.1,
        });
    }
    Ok(out)
}

pub async fn get_camera(db: &Db, id: i64) -> anyhow::Result<Camera> {
    let cam = sqlx::query_as::<_, Camera>(&format!(
        "SELECT {CAMERA_COLUMNS} FROM cameras WHERE id = ?"
    ))
    .bind(id)
    .fetch_optional(db)
    .await?
    .ok_or_else(|| anyhow::anyhow!("camera {id} not found"))?;
    Ok(cam)
}

pub async fn get_camera_by_name(db: &Db, name: &str) -> anyhow::Result<Camera> {
    let cam = sqlx::query_as::<_, Camera>(&format!(
        "SELECT {CAMERA_COLUMNS} FROM cameras WHERE name = ?"
    ))
    .bind(name)
    .fetch_optional(db)
    .await?
    .ok_or_else(|| anyhow::anyhow!("camera {name} not found"))?;
    Ok(cam)
}

#[derive(serde::Deserialize)]
pub struct CameraCreate {
    pub name: String,
    pub label: Option<String>,
    pub rtsp_url: String,
    /// Optional explicit sub stream; omitted = derive from `rtsp_url`.
    #[serde(default)]
    pub rtsp_sub_url: Option<String>,
    #[serde(default = "default_true")]
    pub enabled: bool,
    #[serde(default = "default_stream")]
    pub record_stream: i64,
    #[serde(default)]
    pub record_audio: bool,
    #[serde(default)]
    pub preview_stream: Option<String>,
}

fn default_true() -> bool {
    true
}
fn default_stream() -> i64 {
    1
}

/// `None`/blank → `None` (fall back to derivation); otherwise trimmed.
fn norm_opt(s: Option<&str>) -> Option<String> {
    s.map(str::trim).filter(|v| !v.is_empty()).map(str::to_string)
}

/// Validate the stream-selection fields shared by create/update.
fn check_streams(rtsp_sub_url: Option<&str>, preview: Option<&str>) -> anyhow::Result<()> {
    if let Some(u) = rtsp_sub_url.map(str::trim).filter(|s| !s.is_empty()) {
        if !u.starts_with("rtsp://") {
            anyhow::bail!("rtsp_sub_url must start with rtsp://");
        }
    }
    if let Some(p) = preview {
        if p != "main" && p != "sub" {
            anyhow::bail!("preview_stream must be 'main' or 'sub'");
        }
    }
    Ok(())
}

pub async fn create_camera(db: &Db, req: CameraCreate) -> anyhow::Result<Camera> {
    let name = req.name.trim();
    let re = regex_lite::Regex::new(r"^[A-Za-z0-9_-]{1,64}$").unwrap();
    if !re.is_match(name) {
        anyhow::bail!("name must match [A-Za-z0-9_-]{{1,64}}");
    }
    if !req.rtsp_url.starts_with("rtsp://") {
        anyhow::bail!("rtsp_url must start with rtsp://");
    }
    if !(1..=2).contains(&req.record_stream) {
        anyhow::bail!("record_stream must be 1 (main) or 2 (sub)");
    }
    check_streams(req.rtsp_sub_url.as_deref(), req.preview_stream.as_deref())?;
    let now = crate::db::now();
    sqlx::query(
        "INSERT INTO cameras (name, label, rtsp_url, rtsp_sub_url, enabled, record_stream, record_audio, preview_stream, created_at, updated_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(name)
    .bind(req.label.as_deref().map(|s| s.trim()).filter(|s| !s.is_empty()))
    .bind(&req.rtsp_url)
    .bind(norm_opt(req.rtsp_sub_url.as_deref()))
    .bind(req.enabled)
    .bind(req.record_stream)
    .bind(req.record_audio)
    .bind(req.preview_stream.as_deref().unwrap_or("sub"))
    .bind(now)
    .bind(now)
    .execute(db)
    .await?;
    get_camera_by_name(db, name).await
}

#[derive(serde::Deserialize)]
pub struct CameraUpdate {
    pub label: Option<String>,
    pub rtsp_url: Option<String>,
    /// Explicit sub stream. An empty string clears it (back to derivation) —
    /// `Option<Option<_>>` would need a custom deserializer, so "empty means
    /// clear" is the convention here and matches the admin form's behaviour.
    pub rtsp_sub_url: Option<String>,
    /// `Some("")` clears it back to the derived value.
    pub preview_stream: Option<String>,
    pub enabled: Option<bool>,
    pub record_stream: Option<i64>,
    pub record_audio: Option<bool>,
}

pub async fn update_camera(db: &Db, id: i64, req: CameraUpdate) -> anyhow::Result<Camera> {
    let cam = get_camera(db, id).await?;
    if let Some(v) = req.record_stream {
        if !(1..=2).contains(&v) {
            anyhow::bail!("record_stream must be 1 (main) or 2 (sub)");
        }
    }
    if let Some(u) = &req.rtsp_url {
        if !u.starts_with("rtsp://") {
            anyhow::bail!("rtsp_url must start with rtsp://");
        }
    }
    check_streams(
        req.rtsp_sub_url.as_deref().filter(|s| !s.is_empty()),
        req.preview_stream.as_deref().filter(|s| !s.is_empty()),
    )?;
    let sub = match req.rtsp_sub_url.as_deref() {
        // field absent → keep what's stored
        None => cam.rtsp_sub_url.clone(),
        // present but blank → clear (derive from rtsp_url again)
        Some("") => None,
        Some(v) => Some(v.trim().to_string()),
    };
    let preview = req
        .preview_stream
        .as_deref()
        .filter(|s| !s.is_empty())
        .unwrap_or(&cam.preview_stream)
        .to_string();
    sqlx::query(
        "UPDATE cameras SET label = ?, rtsp_url = ?, rtsp_sub_url = ?, enabled = ?, record_stream = ?, record_audio = ?, preview_stream = ?, updated_at = ? WHERE id = ?",
    )
    .bind(req.label.as_deref().map(|s| s.trim()).filter(|s| !s.is_empty()).or(cam.label.as_deref()))
    .bind(req.rtsp_url.as_deref().unwrap_or(&cam.rtsp_url))
    .bind(sub)
    .bind(req.enabled.unwrap_or(cam.enabled))
    .bind(req.record_stream.unwrap_or(cam.record_stream))
    .bind(req.record_audio.unwrap_or(cam.record_audio))
    .bind(preview)
    .bind(crate::db::now())
    .bind(id)
    .execute(db)
    .await?;
    get_camera(db, id).await
}

pub async fn delete_camera(db: &Db, id: i64) -> anyhow::Result<()> {
    // Segments are CASCADE-deleted; files are kept on disk (admin can prune
    // manually or bump cleanup) to avoid data loss on accidental deletes.
    let res = sqlx::query("DELETE FROM cameras WHERE id = ?")
        .bind(id)
        .execute(db)
        .await?;
    if res.rows_affected() == 0 {
        anyhow::bail!("camera {id} not found");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mask_url_with_credentials() {
        assert_eq!(
            mask_rtsp_url("rtsp://admin:P46WeYWdcc@192.168.8.144:554/stream1"),
            "rtsp://***:***@192.168.8.144:554/stream1"
        );
    }

    #[test]
    fn mask_url_without_credentials() {
        assert_eq!(
            mask_rtsp_url("rtsp://192.168.8.144:554/stream1"),
            "rtsp://192.168.8.144:554/stream1"
        );
    }

    fn cam(main: &str, sub: Option<&str>, preview: &str) -> Camera {
        Camera {
            id: 1,
            name: "main".into(),
            label: None,
            rtsp_url: main.into(),
            rtsp_sub_url: sub.map(str::to_string),
            enabled: true,
            record_stream: 1,
            record_audio: false,
            preview_stream: preview.into(),
            created_at: 0,
            updated_at: 0,
        }
    }

    #[test]
    fn sub_stream_derived_when_not_configured() {
        // Pre-011 rows: no explicit sub URL, so `/stream1` → `/stream2`.
        let c = cam("rtsp://u:p@h:554/stream1", None, "sub");
        assert_eq!(c.url_for_stream(2), "rtsp://u:p@h:554/stream2");
        assert_eq!(c.url_for_stream(1), "rtsp://u:p@h:554/stream1");
    }

    #[test]
    fn explicit_sub_stream_wins() {
        let c = cam(
            "rtsp://u:p@h:554/stream1",
            Some("rtsp://u:p@h:554/stream2?x=1"),
            "sub",
        );
        assert_eq!(c.url_for_stream(2), "rtsp://u:p@h:554/stream2?x=1");
    }

    #[test]
    fn blank_sub_url_falls_back_to_derivation() {
        // The admin form sends "" to clear the field.
        let c = cam("rtsp://u:p@h:554/stream1", Some("   "), "sub");
        assert_eq!(c.url_for_stream(2), "rtsp://u:p@h:554/stream2");
    }

    #[test]
    fn url_without_stream_marker_is_passed_through() {
        // A device that can't switch streams must not get a made-up URL.
        let c = cam("rtsp://u:p@h/live", None, "sub");
        assert_eq!(c.url_for_stream(2), "rtsp://u:p@h/live");
    }

    #[test]
    fn preview_stream_picks_source() {
        let sub = cam("rtsp://u:p@h/stream1", None, "sub");
        let main = cam("rtsp://u:p@h/stream1", None, "main");
        assert_eq!(sub.url_for_preview(), "rtsp://u:p@h/stream2");
        assert_eq!(main.url_for_preview(), "rtsp://u:p@h/stream1");
    }
}
