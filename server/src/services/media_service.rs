use anyhow::Result;
use serde::Serialize;
use std::path::Path;

use crate::utils::music_parser;
use crate::utils::video_detector;

#[derive(Debug, Serialize)]
pub struct MediaInfo {
    #[serde(flatten)]
    pub video_info: Option<video_detector::VideoEntry>,
    #[serde(flatten)]
    pub music_info: Option<music_parser::MusicInfo>,
}

pub async fn get_lyrics(path: &str) -> Result<Vec<music_parser::LyricsLine>> {
    music_parser::get_lyrics(path).await
}

pub async fn get_media_info(path: &str) -> Result<MediaInfo> {
    let file_path = Path::new(path);
    
    if let Some(ext) = file_path.extension() {
        let ext_lower = ext.to_string_lossy().to_lowercase();
        
        // Audio files
        if matches!(ext_lower.as_str(), "mp3" | "flac" | "m4a" | "ogg" | "wav" | "aac") {
            let music_info = music_parser::get_music_info(path).await?;
            return Ok(MediaInfo {
                video_info: None,
                music_info: Some(music_info),
            });
        }
        
        // Video files or directories
        let video_info = video_detector::detect_video_entry(file_path).await?;
        return Ok(MediaInfo {
            video_info: Some(video_info),
            music_info: None,
        });
    }
    
    // Try as directory (video folder)
    if file_path.is_dir() {
        let video_info = video_detector::detect_video_entry(file_path).await?;
        return Ok(MediaInfo {
            video_info: Some(video_info),
            music_info: None,
        });
    }
    
    Err(anyhow::anyhow!("Unknown media type"))
}
