//! Photo asset, tag, face and person-group models.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct PhotoAsset {
    pub id: i64,
    pub dir_id: i64,
    pub rel_path: String,
    pub fingerprint: String,
    pub file_hash: Option<String>,
    pub pixel_hash: Option<String>,
    pub size: i64,
    pub mtime: i64,
    pub taken_at: Option<i64>,
    pub width: Option<i64>,
    pub height: Option<i64>,
    pub orientation: i64,
    pub gps_lat: Option<f64>,
    pub gps_lng: Option<f64>,
    pub camera_make: Option<String>,
    pub camera_model: Option<String>,
    pub status: String,
    pub compressed: i64,
    pub media_kind: String,
    pub duration_ms: Option<i64>,
    pub video_codec: Option<String>,
    pub created_at: i64,
    pub updated_at: i64,
}

fn default_media_kind() -> String {
    "photo".to_string()
}

impl PhotoAsset {
    /// Timestamp used for ordering: EXIF capture time, falling back to mtime.
    #[allow(dead_code)]
    pub fn sort_time(&self) -> i64 {
        self.taken_at.unwrap_or(self.mtime)
    }
}

/// Lightweight projection returned to clients.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PhotoItem {
    pub id: i64,
    pub dir_id: i64,
    pub dir_name: String,
    pub rel_path: String,
    pub name: String,
    pub taken_at: i64,
    pub width: Option<i64>,
    pub height: Option<i64>,
    pub size: i64,
    pub orientation: i64,
    pub gps_lat: Option<f64>,
    pub gps_lng: Option<f64>,
    pub camera_make: Option<String>,
    pub camera_model: Option<String>,
    /// "photo" | "video"
    #[serde(rename = "media_kind", default = "default_media_kind")]
    pub media_kind: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub duration_ms: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub video_codec: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub file_hash: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pixel_hash: Option<String>,
    pub tags: Vec<PhotoTag>,
    #[serde(rename = "thumb_url")]
    pub thumb_url: String,
    #[serde(rename = "url")]
    pub url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct PhotoTag {
    pub tag: String,
    pub kind: String,
    pub confidence: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct Face {
    pub id: i64,
    pub photo_id: i64,
    pub box_x: f64,
    pub box_y: f64,
    pub box_w: f64,
    pub box_h: f64,
    pub phash: Option<String>,
    pub group_id: Option<i64>,
    pub created_at: i64,
}

#[allow(dead_code)]
#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct PersonGroup {
    pub id: i64,
    pub name: Option<String>,
    pub representative_face_id: Option<i64>,
    pub created_at: i64,
}

#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct PersonGroupSummary {
    pub id: i64,
    pub name: Option<String>,
    pub face_count: i64,
    pub photo_count: i64,
    pub cover_url: Option<String>,
}

#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct TagSummary {
    pub tag: String,
    pub kind: String,
    pub photo_count: i64,
    pub cover_url: Option<String>,
}

/// One group of the timeline response.
#[derive(Debug, Clone, Serialize)]
pub struct TimelineGroup {
    pub key: String,
    pub label: String,
    pub year: i32,
    pub month: Option<u32>,
    pub count: i64,
    pub items: Vec<PhotoItem>,
}

/// Aggregated geo cluster for the map views.
#[derive(Debug, Clone, Serialize)]
pub struct GeoPoint {
    pub lat: f64,
    pub lng: f64,
    pub count: i64,
    pub thumb_url: Option<String>,
    pub photo_ids: Vec<i64>,
}

/// Directory tree node used by the "browse by folder" view.
#[derive(Debug, Clone, Serialize)]
pub struct TreeGroup {
    pub dir_id: i64,
    pub dir_name: String,
    pub path: String,
    pub count: i64,
    pub items: Vec<PhotoItem>,
}

/// One enterable folder in the 目录 (browse-by-folder) view.
///
/// `count` aggregates every photo at or below the folder, so the number is
/// meaningful before drilling in. `path` is the folder's path relative to its
/// own dir; the top level (a dir itself) has an empty one.
#[derive(Debug, Clone, Serialize)]
pub struct FolderNode {
    pub dir_id: i64,
    pub dir_name: String,
    pub path: String,
    /// Display label: the dir name at the top level, else the last path segment.
    pub name: String,
    pub count: i64,
}

/// Duplicate detection result.
#[derive(Debug, Clone, Serialize)]
pub struct DuplicateGroup {
    pub fingerprint: String,
    pub reason: String,
    pub items: Vec<PhotoItem>,
}
