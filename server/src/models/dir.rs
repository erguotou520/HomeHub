//! Directory registry models: unified storage + ownership marks.

use serde::{Deserialize, Serialize};

/// Ownership marks. A directory may carry several at once.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum DirMark {
    /// Participates in the photo album.
    Album,
    Video,
    Music,
    Document,
    /// Pure file management, excluded from album views.
    None,
}

impl DirMark {
    pub fn parse(s: &str) -> Option<Self> {
        match s.trim().to_lowercase().as_str() {
            "album" | "photo" | "photos" => Some(Self::Album),
            "video" | "videos" => Some(Self::Video),
            "music" => Some(Self::Music),
            "document" | "documents" => Some(Self::Document),
            "none" => Some(Self::None),
            _ => None,
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Album => "album",
            Self::Video => "video",
            Self::Music => "music",
            Self::Document => "document",
            Self::None => "none",
        }
    }

    pub fn parse_csv(s: &str) -> Vec<Self> {
        let mut out: Vec<Self> = s
            .split(',')
            .filter_map(Self::parse)
            .collect();
        if out.is_empty() {
            out.push(Self::None);
        }
        out
    }

    pub fn to_csv(marks: &[Self]) -> String {
        marks.iter().map(|m| m.as_str()).collect::<Vec<_>>().join(",")
    }
}

/// A directory as stored in SQLite (mirror of `config.yaml`).
#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct DirRecord {
    pub id: i64,
    pub name: String,
    pub path: String,
    pub marks: String,
    pub ignore_rules: String,
    pub enabled: i64,
    pub created_at: i64,
}

impl DirRecord {
    pub fn marks_vec(&self) -> Vec<DirMark> {
        DirMark::parse_csv(&self.marks)
    }

    pub fn has_mark(&self, mark: DirMark) -> bool {
        self.marks_vec().contains(&mark)
    }

    pub fn ignore_list(&self) -> Vec<String> {
        self.ignore_rules
            .split(',')
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect()
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled != 0
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct DirStats {
    pub id: i64,
    pub name: String,
    pub path: String,
    pub marks: Vec<DirMark>,
    pub ignore_rules: Vec<String>,
    pub enabled: bool,
    pub file_count: i64,
    pub photo_count: i64,
    pub tagged_count: i64,
    pub bytes_saved: i64,
    pub total_bytes: i64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn csv_roundtrip() {
        let csv = DirMark::to_csv(&[DirMark::Album, DirMark::Video]);
        assert_eq!(csv, "album,video");
        let parsed = DirMark::parse_csv(&csv);
        assert_eq!(parsed, vec![DirMark::Album, DirMark::Video]);
    }

    #[test]
    fn empty_marks_default_to_none() {
        assert_eq!(DirMark::parse_csv(""), vec![DirMark::None]);
    }
}
