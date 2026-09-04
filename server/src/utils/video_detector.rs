use anyhow::Result;
use serde::Serialize;
use std::path::Path;
use tokio::fs;

/// Video entry types detected from file structure
#[derive(Debug, Clone, Serialize)]
pub enum VideoEntry {
    /// Single video file without metadata
    SingleFile {
        path: String,
        title: String,
    },
    /// Video with accompanying metadata files (poster, fanart, nfo)
    MovieWithMeta {
        video_path: String,
        poster: Option<String>,
        fanart: Option<String>,
        nfo_data: Option<NfoData>,
        title: String,
    },
    /// TV Series with multiple episodes
    TvSeries {
        folder: String,
        title: String,
        episodes: Vec<Episode>,
        poster: Option<String>,
    },
    /// Blu-ray disc structure
    BluRay {
        folder: String,
        title: String,
        main_video: Option<String>,
    },
    /// ISO file
    IsoFile {
        path: String,
        title: String,
        nfo: Option<String>,
        poster: Option<String>,
    },
}

#[derive(Debug, Clone, Serialize)]
pub struct Episode {
    pub number: u32,
    pub title: String,
    pub path: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct NfoData {
    pub title: Option<String>,
    pub year: Option<String>,
    pub plot: Option<String>,
    pub rating: Option<f32>,
}

const VIDEO_EXTENSIONS: &[&str] = &[
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts",
];

const IMAGE_EXTENSIONS: &[&str] = &["jpg", "jpeg", "png", "webp"];

/// Detect video entry type from a directory or file
pub async fn detect_video_entry(path: &Path) -> Result<VideoEntry> {
    if path.is_file() {
        return detect_single_file(path).await;
    }
    
    // Check for Blu-ray structure
    if path.join("BDMV").exists() {
        return detect_bluray(path).await;
    }
    
    // Scan directory contents
    let mut videos: Vec<String> = Vec::new();
    let mut iso_files: Vec<String> = Vec::new();
    let mut nfo_files: Vec<String> = Vec::new();
    let mut poster: Option<String> = None;
    let mut fanart: Option<String> = None;
    
    let mut dir = fs::read_dir(path).await?;
    while let Some(entry) = dir.next_entry().await? {
        let file_name = entry.file_name().to_string_lossy().to_string();
        let file_path = entry.path();
        
        if let Some(ext) = file_path.extension() {
            let ext_lower = ext.to_string_lossy().to_lowercase();
            
            // Check for videos
            if VIDEO_EXTENSIONS.contains(&ext_lower.as_str()) {
                videos.push(file_path.to_string_lossy().to_string());
            }
            
            // Check for ISO
            if ext_lower == "iso" {
                iso_files.push(file_path.to_string_lossy().to_string());
            }
            
            // Check for NFO
            if ext_lower == "nfo" {
                nfo_files.push(file_path.to_string_lossy().to_string());
            }
            
            // Check for poster/fanart images
            if IMAGE_EXTENSIONS.contains(&ext_lower.as_str()) {
                let name_lower = file_name.to_lowercase();
                if name_lower.contains("-poster") || name_lower.contains("poster") {
                    poster = Some(file_path.to_string_lossy().to_string());
                } else if name_lower.contains("-fanart") || name_lower.contains("fanart") {
                    fanart = Some(file_path.to_string_lossy().to_string());
                }
            }
        }
    }
    
    let folder_name = path.file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_default();
    
    // Check for ISO files first
    if !iso_files.is_empty() {
        let iso_path = iso_files.first().unwrap().clone();
        return Ok(VideoEntry::IsoFile {
            path: iso_path,
            title: folder_name,
            nfo: nfo_files.first().cloned(),
            poster,
        });
    }
    
    // Check if this looks like a TV series (multiple numbered videos)
    if is_tv_series(&videos) {
        let episodes = extract_episodes(&videos);
        return Ok(VideoEntry::TvSeries {
            folder: path.to_string_lossy().to_string(),
            title: folder_name,
            episodes,
            poster,
        });
    }
    
    // Single or few videos with metadata
    if !videos.is_empty() {
        let video_path = videos.first().unwrap().clone();
        let nfo_data = if let Some(nfo_path) = nfo_files.first() {
            parse_nfo(nfo_path).await.ok()
        } else {
            None
        };
        
        return Ok(VideoEntry::MovieWithMeta {
            video_path,
            poster,
            fanart,
            nfo_data,
            title: folder_name,
        });
    }
    
    // Default to single file entry for the folder
    Ok(VideoEntry::SingleFile {
        path: path.to_string_lossy().to_string(),
        title: folder_name,
    })
}

async fn detect_single_file(path: &Path) -> Result<VideoEntry> {
    let title = path.file_stem()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_default();
    
    Ok(VideoEntry::SingleFile {
        path: path.to_string_lossy().to_string(),
        title,
    })
}

async fn detect_bluray(path: &Path) -> Result<VideoEntry> {
    let folder_name = path.file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_default();
    
    // Find main video in BDMV/STREAM
    let stream_path = path.join("BDMV/STREAM");
    let mut main_video = None;
    
    if stream_path.exists() {
        let mut dir = fs::read_dir(&stream_path).await?;
        let mut largest_size = 0u64;
        
        while let Some(entry) = dir.next_entry().await? {
            if let Some(ext) = entry.path().extension() {
                if ext.to_string_lossy().to_lowercase() == "m2ts" {
                    if let Ok(meta) = entry.metadata().await {
                        if meta.len() > largest_size {
                            largest_size = meta.len();
                            main_video = Some(entry.path().to_string_lossy().to_string());
                        }
                    }
                }
            }
        }
    }
    
    Ok(VideoEntry::BluRay {
        folder: path.to_string_lossy().to_string(),
        title: folder_name,
        main_video,
    })
}

/// Check if videos look like TV series (numbered episodes)
fn is_tv_series(videos: &[String]) -> bool {
    if videos.len() < 3 {
        return false;
    }
    
    // Check for common episode patterns
    let patterns = &["集", "第", "episode", "ep", "e0", "e1", "e2", "s0", "s1"];
    let mut matching = 0;
    
    for video in videos {
        let lower = video.to_lowercase();
        if patterns.iter().any(|p| lower.contains(p)) {
            matching += 1;
        }
    }
    
    // If more than 60% match episode patterns, it's a series
    matching * 100 / videos.len() > 60
}

/// Extract episode info from video filenames
fn extract_episodes(videos: &[String]) -> Vec<Episode> {
    let mut episodes: Vec<Episode> = videos
        .iter()
        .enumerate()
        .map(|(i, path)| {
            let file_name = Path::new(path)
                .file_stem()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_default();
            
            // Try to extract episode number
            let number = extract_episode_number(&file_name).unwrap_or((i + 1) as u32);
            
            Episode {
                number,
                title: file_name,
                path: path.clone(),
            }
        })
        .collect();
    
    episodes.sort_by_key(|e| e.number);
    episodes
}

/// Extract episode number from filename
fn extract_episode_number(name: &str) -> Option<u32> {
    use std::str::FromStr;
    
    // Patterns: "第1集", "E01", "EP01", "Episode 1", "01集"
    let patterns = [
        r"第(\d+)集",
        r"[Ee][Pp]?(\d+)",
        r"[Ee]pisode\s*(\d+)",
        r"(\d+)集",
    ];
    
    for pattern in patterns {
        if let Some(caps) = regex_lite::Regex::new(pattern).ok()?.captures(name) {
            if let Some(num_str) = caps.get(1) {
                if let Ok(num) = u32::from_str(num_str.as_str()) {
                    return Some(num);
                }
            }
        }
    }
    
    None
}

/// Parse NFO file for movie/show metadata
pub async fn parse_nfo(path: &str) -> Result<NfoData> {
    let content = fs::read_to_string(path).await?;
    
    // Simple XML parsing for common NFO fields
    let title = extract_xml_field(&content, "title");
    let year = extract_xml_field(&content, "year");
    let plot = extract_xml_field(&content, "plot");
    let rating = extract_xml_field(&content, "rating")
        .and_then(|r| r.parse::<f32>().ok());
    
    Ok(NfoData {
        title,
        year,
        plot,
        rating,
    })
}

fn extract_xml_field(content: &str, field: &str) -> Option<String> {
    let open_tag = format!("<{}>", field);
    let close_tag = format!("</{}>", field);
    
    if let Some(start) = content.find(&open_tag) {
        let start_content = start + open_tag.len();
        if let Some(end) = content[start_content..].find(&close_tag) {
            let value = content[start_content..start_content + end].trim();
            if !value.is_empty() {
                return Some(value.to_string());
            }
        }
    }
    
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_extract_episode_number() {
        assert_eq!(extract_episode_number("第1集"), Some(1));
        assert_eq!(extract_episode_number("E01"), Some(1));
        assert_eq!(extract_episode_number("哪吒传奇 第10集"), Some(10));
        assert_eq!(extract_episode_number("Episode 5"), Some(5));
    }
    
    #[test]
    fn test_is_tv_series() {
        let series = vec![
            "第1集.mp4".to_string(),
            "第2集.mp4".to_string(),
            "第3集.mp4".to_string(),
        ];
        assert!(is_tv_series(&series));
        
        let movie = vec!["Movie.mp4".to_string()];
        assert!(!is_tv_series(&movie));
    }
}
