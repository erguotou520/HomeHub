//! HomeHub configuration.
//!
//! `config.yaml` is the source of truth for the directory registry and for
//! bootstrap values of everything else. Runtime-tunable values (task resource
//! control, ML parameters, retention, alerts, ...) live in the `settings` table
//! and can be changed through `PUT /api/admin/settings`.

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use crate::models::dir::DirMark;

// ─────────────────────────────── top level ─────────────────────────────────

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Config {
    #[serde(default)]
    pub global: GlobalConfig,
    #[serde(default)]
    pub admin: AdminConfig,
    #[serde(default)]
    pub dirs: Vec<DirConfig>,
    #[serde(default)]
    pub wireguard: WireGuardConfig,
    #[serde(default)]
    pub runtime: RuntimeSettings,
    /// Legacy `apps:` section (home-nas format). Migrated to `dirs` on load.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub apps: Option<LegacyApps>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct GlobalConfig {
    #[serde(rename = "web-url", default = "default_web_url")]
    pub web_url: String,
    #[serde(rename = "jwt-secret", default = "default_jwt_secret")]
    pub jwt_secret: String,
    #[serde(default = "default_bind")]
    pub bind: String,
    #[serde(rename = "data-dir", default = "default_data_dir")]
    pub data_dir: String,
    /// SQLite file. Defaults to `<data-dir>/homehub.db`.
    #[serde(rename = "db-path", default)]
    pub db_path: Option<String>,
    /// Where `.originals/` copies are written. Defaults to `<data-dir>/originals`.
    #[serde(rename = "originals-dir", default)]
    pub originals_dir: Option<String>,
}

fn default_web_url() -> String {
    "http://localhost:8485".into()
}
fn default_jwt_secret() -> String {
    "change-me-in-production".into()
}
fn default_bind() -> String {
    "0.0.0.0:8485".into()
}
fn default_data_dir() -> String {
    "./data".into()
}

impl Default for GlobalConfig {
    fn default() -> Self {
        Self {
            web_url: default_web_url(),
            jwt_secret: default_jwt_secret(),
            bind: default_bind(),
            data_dir: default_data_dir(),
            db_path: None,
            originals_dir: None,
        }
    }
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
pub struct AdminConfig {
    /// Plain-text admin password (self-hosted, single admin).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub password: Option<String>,
    /// SHA-256 hex of the admin password; takes precedence over `password`.
    #[serde(rename = "password-hash", default, skip_serializing_if = "Option::is_none")]
    pub password_hash: Option<String>,
}

impl AdminConfig {
    pub fn verify(&self, candidate: &str) -> bool {
        use sha2::{Digest, Sha256};
        let candidate_hash = {
            let mut h = Sha256::new();
            h.update(candidate.as_bytes());
            hex::encode(h.finalize())
        };
        match &self.password_hash {
            Some(h) => constant_time_eq(h.to_lowercase().as_bytes(), candidate_hash.as_bytes()),
            None => match &self.password {
                Some(p) => constant_time_eq(p.as_bytes(), candidate.as_bytes()),
                None => false,
            },
        }
    }
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut acc = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        acc |= x ^ y;
    }
    acc == 0
}

// ───────────────────────────── directory registry ──────────────────────────

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct DirConfig {
    pub name: String,
    pub path: String,
    #[serde(default)]
    pub marks: Vec<String>,
    #[serde(default)]
    pub ignore: Vec<String>,
    #[serde(default = "default_true")]
    pub enabled: bool,
}

fn default_true() -> bool {
    true
}

impl DirConfig {
    pub fn marks_set(&self) -> Vec<DirMark> {
        let mut out: Vec<DirMark> = self
            .marks
            .iter()
            .filter_map(|m| DirMark::parse(m))
            .collect();
        if out.is_empty() {
            out.push(DirMark::None);
        }
        out
    }
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
pub struct LegacyApps {
    #[serde(default)]
    pub album: Vec<String>,
    #[serde(default)]
    pub documents: Vec<String>,
    #[serde(default)]
    pub videos: Vec<String>,
    #[serde(default)]
    pub music: Vec<String>,
}

// ──────────────────────────────── WireGuard ────────────────────────────────

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct WireGuardConfig {
    #[serde(default = "default_wg_iface")]
    pub interface: String,
    /// Parse `wg show <iface> dump` to auto-register peers when available.
    #[serde(rename = "auto-sync", default)]
    pub auto_sync: bool,
    #[serde(rename = "sync-interval-secs", default = "default_wg_sync")]
    pub sync_interval_secs: i64,
    /// Peers declared by hand when the server is not co-located with the WG gateway.
    #[serde(default)]
    pub peers: Vec<WgPeerConfig>,
}

fn default_wg_iface() -> String {
    "wg0".into()
}
fn default_wg_sync() -> i64 {
    300
}

impl Default for WireGuardConfig {
    fn default() -> Self {
        Self {
            interface: default_wg_iface(),
            auto_sync: false,
            sync_interval_secs: default_wg_sync(),
            peers: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct WgPeerConfig {
    pub name: String,
    #[serde(rename = "tunnel-ip", default)]
    pub tunnel_ip: String,
    #[serde(rename = "public-key", default)]
    pub public_key: Option<String>,
}

// ───────────────────────── runtime tunable settings ────────────────────────

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct RuntimeSettings {
    #[serde(default)]
    pub tasks: TaskConfig,
    #[serde(default)]
    pub ml: MlConfig,
    #[serde(default)]
    pub compression: CompressionConfig,
    #[serde(default)]
    pub video: VideoConfig,
    #[serde(default)]
    pub originals: OriginalsConfig,
    #[serde(default)]
    pub trash: TrashConfig,
    #[serde(default)]
    pub audit: AuditConfig,
    #[serde(default)]
    pub backup: BackupConfig,
    #[serde(default)]
    pub alerts: AlertConfig,
}

impl Default for RuntimeSettings {
    fn default() -> Self {
        Self {
            tasks: TaskConfig::default(),
            ml: MlConfig::default(),
            compression: CompressionConfig::default(),
            video: VideoConfig::default(),
            originals: OriginalsConfig::default(),
            trash: TrashConfig::default(),
            audit: AuditConfig::default(),
            backup: BackupConfig::default(),
            alerts: AlertConfig::default(),
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct TaskConfig {
    #[serde(default)]
    pub concurrency: TaskConcurrency,
    /// Max files processed per second across all workers (0 = unlimited).
    #[serde(rename = "rate-limit-per-sec", default = "default_rate_limit")]
    pub rate_limit_per_sec: u32,
    #[serde(rename = "file-timeout-secs", default = "default_file_timeout")]
    pub file_timeout_secs: u64,
    #[serde(rename = "max-attempts", default = "default_max_attempts")]
    pub max_attempts: u32,
    #[serde(rename = "max-workers", default = "default_max_workers")]
    pub max_workers: usize,
    #[serde(rename = "work-window", default)]
    pub work_window: WorkWindow,
    /// Full rescan cadence: `off` | `daily` | `weekly`.
    #[serde(rename = "full-rescan", default = "default_full_rescan")]
    pub full_rescan: String,
    #[serde(rename = "full-rescan-hour", default = "default_rescan_hour")]
    pub full_rescan_hour: u32,
}

fn default_rate_limit() -> u32 {
    20
}
fn default_file_timeout() -> u64 {
    120
}
fn default_max_attempts() -> u32 {
    3
}
fn default_max_workers() -> usize {
    6
}
fn default_full_rescan() -> String {
    "weekly".into()
}
fn default_rescan_hour() -> u32 {
    3
}

impl Default for TaskConfig {
    fn default() -> Self {
        Self {
            concurrency: TaskConcurrency::default(),
            rate_limit_per_sec: default_rate_limit(),
            file_timeout_secs: default_file_timeout(),
            max_attempts: default_max_attempts(),
            max_workers: default_max_workers(),
            work_window: WorkWindow::default(),
            full_rescan: default_full_rescan(),
            full_rescan_hour: default_rescan_hour(),
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct TaskConcurrency {
    /// CPU bound tasks (object / scene / face detection).
    #[serde(default = "default_cpu_concurrency")]
    pub cpu: usize,
    /// IO bound tasks (thumbnail, compression, hashing).
    #[serde(default = "default_io_concurrency")]
    pub io: usize,
}

fn default_cpu_concurrency() -> usize {
    1
}
fn default_io_concurrency() -> usize {
    2
}

impl Default for TaskConcurrency {
    fn default() -> Self {
        Self {
            cpu: default_cpu_concurrency(),
            io: default_io_concurrency(),
        }
    }
}

/// Heavy tasks only run at full speed inside this local-time window.
/// Outside of it only high priority work (freshly uploaded files) is processed.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct WorkWindow {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default = "default_window_start")]
    pub start: String,
    #[serde(default = "default_window_end")]
    pub end: String,
}

fn default_window_start() -> String {
    "02:00".into()
}
fn default_window_end() -> String {
    "08:00".into()
}

impl Default for WorkWindow {
    fn default() -> Self {
        Self {
            enabled: true,
            start: default_window_start(),
            end: default_window_end(),
        }
    }
}

impl WorkWindow {
    /// Returns true when the given local hour is inside the window.
    pub fn contains_hour(&self, hour: u32) -> bool {
        if !self.enabled {
            return true;
        }
        match (parse_hhmm(&self.start), parse_hhmm(&self.end)) {
            (Some(s), Some(e)) if s <= e => hour >= s && hour < e,
            (Some(s), Some(e)) => hour >= s || hour < e,
            _ => true,
        }
    }
}

fn parse_hhmm(v: &str) -> Option<u32> {
    let mut it = v.split(':');
    let h: u32 = it.next()?.trim().parse().ok()?;
    let _m: u32 = it.next()?.trim().parse().ok()?;
    if h > 23 {
        return None;
    }
    Some(h)
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct MlConfig {
    /// `stub` (no model, deterministic no-op) or `onnx` (requires the `onnx` feature).
    #[serde(default = "default_ml_backend")]
    pub backend: String,
    #[serde(default = "default_true")]
    pub enabled: bool,
    #[serde(default)]
    pub object: MlModelConfig,
    #[serde(default)]
    pub scene: MlSceneConfig,
    #[serde(default)]
    pub face: MlFaceConfig,
    /// Chinese-CLIP for natural-language photo search.
    #[serde(default)]
    pub clip: MlClipConfig,
    /// Skip detection for images smaller than this (px, short edge).
    #[serde(rename = "min-image-size", default = "default_min_image_size")]
    pub min_image_size: u32,
    #[serde(rename = "exclude-tags", default)]
    pub exclude_tags: Vec<String>,
    #[serde(rename = "onnx-threads", default = "default_onnx_threads")]
    pub onnx_threads: usize,
}

/// Chinese-CLIP (RN50) encoders for semantic search: images get a 1024-d
/// embedding at index time; queries embed text with the same space.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct MlClipConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(rename = "image-model", default = "default_clip_image_model")]
    pub image_model: String,
    #[serde(rename = "text-model", default = "default_clip_text_model")]
    pub text_model: String,
    /// BERT WordPiece vocabulary shared by both encoders.
    #[serde(default = "default_clip_vocab")]
    pub vocab: String,
}

impl Default for MlClipConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            image_model: default_clip_image_model(),
            text_model: default_clip_text_model(),
            vocab: default_clip_vocab(),
        }
    }
}

fn default_clip_image_model() -> String {
    "models/clip-rn50-image.onnx".into()
}
fn default_clip_text_model() -> String {
    "models/clip-rn50-text.onnx".into()
}
fn default_clip_vocab() -> String {
    "models/clip-vocab.txt".into()
}

fn default_ml_backend() -> String {
    "onnx".into()
}
fn default_object_model() -> String {
    "models/yolov8n.onnx".into()
}
fn default_scene_model() -> String {
    "models/scene-classification.onnx".into()
}
fn default_face_model() -> String {
    "models/yolov8n-face.onnx".into()
}
fn default_min_image_size() -> u32 {
    160
}
fn default_onnx_threads() -> usize {
    2
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct MlModelConfig {
    #[serde(default = "default_true")]
    pub enabled: bool,
    /// Path to the ONNX file. Relative paths are resolved against DATA_DIR
    /// (default `./data`).
    #[serde(default = "default_object_model")]
    pub model: String,
    #[serde(default = "default_object_threshold")]
    pub threshold: f32,
    /// Optional explicit label list; defaults to the YOLOv8 COCO set.
    #[serde(default)]
    pub labels: String,
}

fn default_object_threshold() -> f32 {
    0.35
}

impl Default for MlModelConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            model: default_object_model(),
            threshold: default_object_threshold(),
            labels: String::new(),
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct MlSceneConfig {
    #[serde(default = "default_true")]
    pub enabled: bool,
    /// Path to the ONNX file. Relative paths are resolved against DATA_DIR.
    #[serde(default = "default_scene_model")]
    pub model: String,
    #[serde(default)]
    pub labels: String,
    #[serde(default = "default_scene_threshold")]
    pub threshold: f32,
}

fn default_scene_threshold() -> f32 {
    0.30
}

impl Default for MlSceneConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            model: default_scene_model(),
            labels: String::new(),
            threshold: default_scene_threshold(),
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct MlFaceConfig {
    #[serde(default = "default_true")]
    pub enabled: bool,
    /// Path to the ONNX file. Relative paths are resolved against DATA_DIR.
    #[serde(default = "default_face_model")]
    pub model: String,
    #[serde(default = "default_face_threshold")]
    pub threshold: f32,
    /// Max hamming distance (of a 256-bit hash) for two faces to be the same person.
    #[serde(rename = "cluster-threshold", default = "default_cluster_threshold")]
    pub cluster_threshold: u32,
    /// Recognition model (SFace/MobileFaceNet-style, 112x112 -> embedding);
    /// empty string disables embedding clustering (phash fallback).
    #[serde(rename = "recognizer", default)]
    pub recognizer: String,
    /// Loose gate: cosine similarity against ANY group member must reach this.
    /// Calibrated on SFace embeddings (same-person min ≈ 0.41, cross-person
    /// max ≈ 0.33 on our test set) — do NOT copy numbers from other models
    /// (InsightFace 512-d embeddings score much higher).
    #[serde(rename = "match-threshold", default = "default_match_threshold")]
    pub match_threshold: f32,
    /// Strict gate: similarity against one of the group's dispersed
    /// prototypes must ALSO reach this (blocks single-linkage drift).
    #[serde(rename = "prototype-threshold", default = "default_prototype_threshold")]
    pub prototype_threshold: f32,
    /// Max dispersed prototype faces kept per group.
    #[serde(rename = "prototype-count", default = "default_prototype_count")]
    pub prototype_count: usize,
    /// Min cosine distance between two selected prototypes (dispersion).
    #[serde(rename = "prototype-spread", default = "default_prototype_spread")]
    pub prototype_spread: f32,
}

fn default_face_threshold() -> f32 {
    0.5
}
fn default_cluster_threshold() -> u32 {
    48
}
fn default_match_threshold() -> f32 {
    0.40
}
fn default_prototype_threshold() -> f32 {
    0.38
}
fn default_prototype_count() -> usize {
    8
}
fn default_prototype_spread() -> f32 {
    0.15
}

impl Default for MlFaceConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            model: default_face_model(),
            threshold: default_face_threshold(),
            cluster_threshold: default_cluster_threshold(),
            recognizer: String::new(),
            match_threshold: default_match_threshold(),
            prototype_threshold: default_prototype_threshold(),
            prototype_count: default_prototype_count(),
            prototype_spread: default_prototype_spread(),
        }
    }
}

impl Default for MlConfig {
    fn default() -> Self {
        Self {
            backend: default_ml_backend(),
            enabled: true,
            object: MlModelConfig::default(),
            scene: MlSceneConfig::default(),
            face: MlFaceConfig::default(),
            clip: MlClipConfig::default(),
            min_image_size: default_min_image_size(),
            exclude_tags: Vec::new(),
            onnx_threads: default_onnx_threads(),
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct CompressionConfig {
    #[serde(default = "default_true")]
    pub enabled: bool,
    /// Only replace the file when the saving reaches this percentage.
    #[serde(rename = "min-saving-percent", default = "default_min_saving")]
    pub min_saving_percent: f64,
    #[serde(rename = "png-level", default = "default_png_level")]
    pub png_level: u8,
    /// Path to `jpegtran` (libjpeg-turbo / mozjpeg) used for lossless JPEG optimisation.
    #[serde(rename = "jpegtran-path", default = "default_jpegtran")]
    pub jpegtran_path: String,
}

fn default_min_saving() -> f64 {
    3.0
}
fn default_png_level() -> u8 {
    2
}
fn default_jpegtran() -> String {
    "jpegtran".into()
}

impl Default for CompressionConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            min_saving_percent: default_min_saving(),
            png_level: default_png_level(),
            jpegtran_path: default_jpegtran(),
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct VideoConfig {
    /// Path to `ffprobe` used for duration/resolution/capture-time extraction.
    #[serde(rename = "ffprobe-path", default = "default_ffprobe")]
    pub ffprobe_path: String,
    /// Path to `ffmpeg` used for video poster (first frame) extraction.
    #[serde(rename = "ffmpeg-path", default = "default_ffmpeg")]
    pub ffmpeg_path: String,
}

fn default_ffprobe() -> String {
    "ffprobe".into()
}
fn default_ffmpeg() -> String {
    "ffmpeg".into()
}

impl Default for VideoConfig {
    fn default() -> Self {
        Self {
            ffprobe_path: default_ffprobe(),
            ffmpeg_path: default_ffmpeg(),
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct OriginalsConfig {
    #[serde(default = "default_true")]
    pub enabled: bool,
    #[serde(rename = "retention-days", default = "default_retention_30")]
    pub retention_days: u32,
    /// Stop writing new originals when `.originals/` exceeds this share of the data dir.
    #[serde(rename = "max-usage-percent", default = "default_originals_cap")]
    pub max_usage_percent: f64,
}

fn default_retention_30() -> u32 {
    30
}
fn default_originals_cap() -> f64 {
    10.0
}

impl Default for OriginalsConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            retention_days: default_retention_30(),
            max_usage_percent: default_originals_cap(),
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct TrashConfig {
    #[serde(rename = "retention-days", default = "default_retention_30")]
    pub retention_days: u32,
    /// Explicit trash root; defaults to `<data-dir>/trash`.
    #[serde(default)]
    pub dir: Option<String>,
}

impl Default for TrashConfig {
    fn default() -> Self {
        Self {
            retention_days: default_retention_30(),
            dir: None,
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct AuditConfig {
    #[serde(rename = "retention-days", default = "default_audit_retention")]
    pub retention_days: u32,
    #[serde(rename = "batch-size", default = "default_audit_batch")]
    pub batch_size: usize,
    #[serde(rename = "flush-interval-secs", default = "default_audit_flush")]
    pub flush_interval_secs: u64,
}

fn default_audit_retention() -> u32 {
    90
}
fn default_audit_batch() -> usize {
    256
}
fn default_audit_flush() -> u64 {
    5
}

impl Default for AuditConfig {
    fn default() -> Self {
        Self {
            retention_days: default_audit_retention(),
            batch_size: default_audit_batch(),
            flush_interval_secs: default_audit_flush(),
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct BackupConfig {
    /// Periodic `VACUUM INTO` snapshots of `homehub.db` (PRD §6 durability).
    #[serde(default = "default_true")]
    pub enabled: bool,
    #[serde(rename = "interval-hours", default = "default_backup_interval")]
    pub interval_hours: u32,
    /// Oldest snapshots are deleted once more than `keep` exist.
    #[serde(default = "default_backup_keep")]
    pub keep: u32,
    /// Explicit backup root; defaults to `<data-dir>/backups`.
    #[serde(default)]
    pub dir: Option<String>,
}

fn default_backup_interval() -> u32 {
    24
}
fn default_backup_keep() -> u32 {
    7
}

impl Default for BackupConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            interval_hours: default_backup_interval(),
            keep: default_backup_keep(),
            dir: None,
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct AlertConfig {
    #[serde(default = "default_true")]
    pub enabled: bool,
    #[serde(rename = "disk-usage-percent", default = "default_disk_threshold")]
    pub disk_usage_percent: f64,
    #[serde(rename = "task-failure-threshold", default = "default_task_failure")]
    pub task_failure_threshold: u32,
    /// Minimum seconds between two notifications of the same alert kind.
    #[serde(rename = "cooldown-secs", default = "default_alert_cooldown")]
    pub cooldown_secs: i64,
    #[serde(default)]
    pub ntfy: NtfyConfig,
    #[serde(default)]
    pub telegram: TelegramConfig,
    #[serde(default)]
    pub smtp: SmtpConfig,
}

fn default_disk_threshold() -> f64 {
    85.0
}
fn default_task_failure() -> u32 {
    5
}
fn default_alert_cooldown() -> i64 {
    3600
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
pub struct NtfyConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub url: String,
    #[serde(default)]
    pub topic: String,
    #[serde(default)]
    pub token: String,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
pub struct TelegramConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(rename = "bot-token", default)]
    pub bot_token: String,
    #[serde(rename = "chat-id", default)]
    pub chat_id: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct SmtpConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub host: String,
    #[serde(default = "default_smtp_port")]
    pub port: u16,
    #[serde(default)]
    pub username: String,
    #[serde(default)]
    pub password: String,
    #[serde(default)]
    pub from: String,
    #[serde(default)]
    pub to: String,
    #[serde(default = "default_true")]
    pub starttls: bool,
}

fn default_smtp_port() -> u16 {
    587
}

impl Default for SmtpConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            host: String::new(),
            port: default_smtp_port(),
            username: String::new(),
            password: String::new(),
            from: String::new(),
            to: String::new(),
            starttls: true,
        }
    }
}

impl Default for AlertConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            disk_usage_percent: default_disk_threshold(),
            task_failure_threshold: default_task_failure(),
            cooldown_secs: default_alert_cooldown(),
            ntfy: NtfyConfig::default(),
            telegram: TelegramConfig::default(),
            smtp: SmtpConfig::default(),
        }
    }
}

// ────────────────────────────── loading / saving ───────────────────────────

impl Config {
    /// Load from YAML, migrating the legacy `apps:` layout into `dirs:` when needed.
    pub fn load<P: AsRef<Path>>(path: P) -> Result<Self> {
        let path = path.as_ref();
        if !path.exists() {
            tracing::warn!("config {} not found, using defaults", path.display());
            return Ok(Self::default());
        }
        let content = std::fs::read_to_string(path)
            .with_context(|| format!("read config {}", path.display()))?;
        let mut config: Config = serde_yaml::from_str(&content)
            .with_context(|| format!("parse config {}", path.display()))?;
        config.migrate_legacy_apps();
        Ok(config)
    }

    pub fn save<P: AsRef<Path>>(&self, path: P) -> Result<()> {
        let content = serde_yaml::to_string(self)?;
        if let Some(parent) = path.as_ref().parent() {
            if !parent.as_os_str().is_empty() {
                std::fs::create_dir_all(parent).ok();
            }
        }
        std::fs::write(path, content)?;
        Ok(())
    }

    /// Convert a legacy `apps:` section into the unified directory registry.
    fn migrate_legacy_apps(&mut self) {
        let Some(legacy) = self.apps.take() else {
            return;
        };
        if !self.dirs.is_empty() {
            return;
        }
        let groups: [(&str, Vec<String>); 4] = [
            ("album", legacy.album),
            ("document", legacy.documents),
            ("video", legacy.videos),
            ("music", legacy.music),
        ];
        for (mark, entries) in groups {
            for entry in entries {
                let entry = entry.trim();
                if entry.is_empty() {
                    continue;
                }
                let (name, path) = match entry.split_once(':') {
                    Some((n, p)) => (n.trim().to_string(), p.trim().to_string()),
                    None => {
                        let p = entry.trim_end_matches('/');
                        let n = p.rsplit('/').next().unwrap_or(p).to_string();
                        (n, entry.to_string())
                    }
                };
                if self.dirs.iter().any(|d| d.name == name) {
                    continue;
                }
                self.dirs.push(DirConfig {
                    name,
                    path,
                    marks: vec![mark.to_string()],
                    ignore: default_ignore_rules(),
                    enabled: true,
                });
            }
        }
        if !self.dirs.is_empty() {
            tracing::info!(
                "migrated legacy apps config into {} dir registry entries",
                self.dirs.len()
            );
        }
    }

    pub fn db_path(&self) -> PathBuf {
        match &self.global.db_path {
            Some(p) => PathBuf::from(p),
            None => Path::new(&self.global.data_dir).join("homehub.db"),
        }
    }

    pub fn trash_dir(&self) -> PathBuf {
        match &self.runtime.trash.dir {
            Some(p) if !p.is_empty() => PathBuf::from(p),
            _ => Path::new(&self.global.data_dir).join("trash"),
        }
    }

    pub fn originals_dir(&self) -> PathBuf {
        match &self.global.originals_dir {
            Some(p) if !p.is_empty() => PathBuf::from(p),
            _ => Path::new(&self.global.data_dir).join("originals"),
        }
    }

    /// Where periodic SQLite snapshots are written. Defaults to `<data-dir>/backups`.
    pub fn backup_dir(&self) -> PathBuf {
        match &self.runtime.backup.dir {
            Some(p) if !p.is_empty() => PathBuf::from(p),
            _ => Path::new(&self.global.data_dir).join("backups"),
        }
    }

    pub fn database_url(&self) -> String {
        let p = self.db_path();
        format!("sqlite://{}?mode=rwc", p.display())
    }
}

pub fn default_ignore_rules() -> Vec<String> {
    vec![
        "@eaDir".into(),
        ".thumbnails".into(),
        "#recycle".into(),
        ".stfolder".into(),
        ".originals".into(),
        // macOS Photos / iPhoto libraries are *packages*: their insides are
        // derived thumbnails, edit sessions and databases, not photos the user
        // put in the folder. Pointing an album at `~/Pictures` used to index
        // every derivative and re-import the whole library on each edit.
        "*.photoslibrary".into(),
        "*.photolibrary".into(),
    ]
}

impl Default for Config {
    fn default() -> Self {
        Self {
            global: GlobalConfig::default(),
            // No built-in default password: `main` generates a strong random
            // one on first boot and logs it, so a fresh instance is never
            // reachable with a publicly-known credential.
            admin: AdminConfig {
                password: None,
                password_hash: None,
            },
            dirs: Vec::new(),
            wireguard: WireGuardConfig::default(),
            runtime: RuntimeSettings::default(),
            apps: None,
        }
    }
}

/// Cryptographically-random lowercase alphanumeric string.
pub fn generate_secret(n: usize) -> String {
    use rand::Rng;
    const ALPHABET: &[u8] = b"abcdefghijklmnopqrstuvwxyz0123456789";
    let mut rng = rand::thread_rng();
    (0..n).map(|_| ALPHABET[rng.gen_range(0..ALPHABET.len())] as char).collect()
}

/// Never run with publicly-known default credentials.
///
/// * missing admin password (fresh config) → generate one, log it, persist it
/// * legacy default password (`admin123`) → same treatment
/// * default JWT secret (`change-me-in-production`) → random secret
///
/// Returns true when the config was changed (and needs persisting).
pub fn harden_credentials(config: &mut Config) -> bool {
    let mut changed = false;
    let need_password = config.admin.password_hash.is_none()
        && match &config.admin.password {
            None => true,
            Some(p) => p.is_empty() || p == "admin123",
        };
    if need_password {
        let generated = generate_secret(16);
        tracing::warn!(
            "no usable admin password configured; generated one: {} \
             (stored in config.yaml — change it from the admin UI if desired)",
            generated
        );
        config.admin.password = Some(generated);
        changed = true;
    }
    if config.global.jwt_secret == default_jwt_secret() || config.global.jwt_secret.is_empty() {
        let secret = generate_secret(48);
        tracing::warn!("jwt-secret is the well-known default; generated a random one");
        config.global.jwt_secret = secret;
        changed = true;
    }
    changed
}

/// Shared, reloadable view of the config: the directory registry can be mutated
/// at runtime through the admin API (and is written back to `config.yaml`).
pub struct ConfigStore {
    path: PathBuf,
    inner: std::sync::RwLock<Arc<Config>>,
}

impl ConfigStore {
    pub fn new(config: Config, path: PathBuf) -> Self {
        Self {
            path,
            inner: std::sync::RwLock::new(Arc::new(config)),
        }
    }

    pub fn get(&self) -> Arc<Config> {
        self.inner.read().map(|g| g.clone()).unwrap_or_default()
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Replace the configuration and persist it to disk.
    pub fn update(&self, next: Config) -> Result<()> {
        {
            let mut guard = self.inner.write().map_err(|e| anyhow::anyhow!("{e}"))?;
            *guard = Arc::new(next.clone());
        }
        next.save(&self.path)?;
        Ok(())
    }

    /// Replace the directory registry only; keeps everything else untouched.
    pub fn update_dirs(&self, dirs: Vec<DirConfig>) -> Result<Arc<Config>> {
        let mut next = (*self.get()).clone();
        next.dirs = dirs;
        self.update(next)?;
        Ok(self.get())
    }
}


#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn legacy_apps_are_migrated() {
        let yaml = r#"
global:
  web-url: http://localhost:8485
apps:
  album:
    - photos:/mnt/nas/photos
  documents:
    - /mnt/nas/documents
"#;
        let mut cfg: Config = serde_yaml::from_str(yaml).unwrap();
        cfg.migrate_legacy_apps();
        assert_eq!(cfg.dirs.len(), 2);
        let photos = &cfg.dirs[0];
        assert_eq!(photos.name, "photos");
        assert_eq!(photos.path, "/mnt/nas/photos");
        assert_eq!(photos.marks, vec!["album".to_string()]);
        assert_eq!(cfg.dirs[1].name, "documents");
        assert_eq!(cfg.dirs[1].marks, vec!["document".to_string()]);
    }

    #[test]
    fn admin_password_verification() {
        let cfg = AdminConfig {
            password: Some("secret".into()),
            password_hash: None,
        };
        assert!(cfg.verify("secret"));
        assert!(!cfg.verify("nope"));

        let hashed = AdminConfig {
            password: None,
            password_hash: Some(
                "2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b".into(),
            ),
        };
        assert!(hashed.verify("secret"));
        assert!(!hashed.verify("other"));
    }

    #[test]
    fn work_window_wraps_midnight() {
        let w = WorkWindow {
            enabled: true,
            start: "22:00".into(),
            end: "06:00".into(),
        };
        assert!(w.contains_hour(23));
        assert!(w.contains_hour(2));
        assert!(!w.contains_hour(12));
    }
}
