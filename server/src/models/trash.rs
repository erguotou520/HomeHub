//! Trash (soft delete), WireGuard peer, alert models.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct TrashEntry {
    pub id: i64,
    pub dir_id: Option<i64>,
    pub dir_name: String,
    pub rel_path: String,
    pub trash_path: String,
    pub is_dir: i64,
    pub size: i64,
    pub trashed_at: i64,
    pub purged_due: i64,
}

#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct WgPeer {
    pub id: i64,
    pub public_key: Option<String>,
    pub name: String,
    pub tunnel_ip: String,
    pub first_seen: i64,
    pub last_seen: i64,
    pub enabled: i64,
    pub source: String,
}

#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct PeerTraffic {
    pub peer_id: i64,
    pub peer_name: String,
    pub tunnel_ip: String,
    pub requests: i64,
    pub bytes: i64,
    pub last_seen: Option<i64>,
}

#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct AuditLog {
    pub id: i64,
    pub peer_id: Option<i64>,
    pub peer_ip: Option<String>,
    pub method: String,
    pub path: String,
    pub status: i64,
    pub bytes: i64,
    pub created_at: i64,
}

#[derive(Debug, Clone, Serialize, sqlx::FromRow, Deserialize)]
pub struct Alert {
    pub id: i64,
    pub level: String,
    pub kind: String,
    pub message: String,
    pub created_at: i64,
    pub resolved_at: Option<i64>,
    pub notified: i64,
}
