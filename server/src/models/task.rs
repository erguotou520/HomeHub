//! Persistent background task queue models.

use serde::{Deserialize, Serialize};
use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TaskKind {
    /// Walk a registered directory and index its contents.
    Scan,
    /// Build the 256px thumbnail.
    Thumb,
    /// YOLO-ish object detection.
    DetectObject,
    /// Scene / landscape classification.
    DetectScene,
    /// Face detection + clustering.
    DetectFace,
    /// Lossless compression.
    Compress,
    /// (Re)read EXIF GPS / time.
    Geo,
    /// Full library duplicate scan.
    DedupScan,
    /// Purge expired trash + originals.
    CleanTrash,
    /// Purge expired audit logs.
    AuditRetention,
    /// Periodic health check.
    HealthCheck,
    /// Refresh the WireGuard peer list.
    PeerSync,
    /// Snapshot `homehub.db` with `VACUUM INTO`.
    Backup,
}

impl TaskKind {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Scan => "scan",
            Self::Thumb => "thumb",
            Self::DetectObject => "detect_object",
            Self::DetectScene => "detect_scene",
            Self::DetectFace => "detect_face",
            Self::Compress => "compress",
            Self::Geo => "geo",
            Self::DedupScan => "dedup_scan",
            Self::CleanTrash => "clean_trash",
            Self::AuditRetention => "audit_retention",
            Self::HealthCheck => "health_check",
            Self::PeerSync => "peer_sync",
            Self::Backup => "backup",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "scan" => Some(Self::Scan),
            "thumb" => Some(Self::Thumb),
            "detect_object" => Some(Self::DetectObject),
            "detect_scene" => Some(Self::DetectScene),
            "detect_face" => Some(Self::DetectFace),
            "compress" => Some(Self::Compress),
            "geo" => Some(Self::Geo),
            "dedup_scan" => Some(Self::DedupScan),
            "clean_trash" => Some(Self::CleanTrash),
            "audit_retention" => Some(Self::AuditRetention),
            "health_check" => Some(Self::HealthCheck),
            "peer_sync" => Some(Self::PeerSync),
            "backup" => Some(Self::Backup),
            _ => None,
        }
    }

    /// CPU heavy work is throttled harder and only runs at full speed in the work window.
    pub fn is_cpu_bound(&self) -> bool {
        matches!(
            self,
            Self::DetectObject | Self::DetectScene | Self::DetectFace | Self::DedupScan
        )
    }

    /// Maintenance tasks are never blocked by the work window.
    pub fn is_maintenance(&self) -> bool {
        matches!(
            self,
            Self::CleanTrash | Self::AuditRetention | Self::HealthCheck | Self::PeerSync
                | Self::Backup
        )
    }

    pub fn all() -> &'static [TaskKind] {
        &[
            Self::Scan,
            Self::Thumb,
            Self::DetectObject,
            Self::DetectScene,
            Self::DetectFace,
            Self::Compress,
            Self::Geo,
            Self::DedupScan,
            Self::CleanTrash,
            Self::AuditRetention,
            Self::HealthCheck,
            Self::PeerSync,
            Self::Backup,
        ]
    }
}

impl fmt::Display for TaskKind {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TaskStatus {
    Pending,
    Running,
    Done,
    Failed,
}

impl TaskStatus {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Pending => "pending",
            Self::Running => "running",
            Self::Done => "done",
            Self::Failed => "failed",
        }
    }
}

#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct Task {
    pub id: i64,
    pub kind: String,
    pub payload: String,
    pub priority: i32,
    pub status: String,
    pub attempts: i32,
    pub error: Option<String>,
    pub created_at: i64,
    pub updated_at: i64,
    pub scheduled_at: i64,
}

/// Queue depth per kind, surfaced by the task centre.
#[derive(Debug, Clone, Serialize)]
pub struct QueueStatus {
    pub kind: String,
    pub pending: i64,
    pub running: i64,
    pub failed: i64,
    pub done: i64,
    pub concurrency: usize,
}

/// Payload for `scan` / `thumb` / `compress` / detection tasks.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileTaskPayload {
    pub dir_id: i64,
    pub rel_path: String,
    /// 0 = normal, higher values bypass the work window (freshly uploaded).
    #[serde(default)]
    pub priority: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScanDirPayload {
    pub dir_id: i64,
    /// Ignore mtime/size fingerprints and re-index everything.
    #[serde(default)]
    pub full: bool,
}
