use anyhow::Result;
use serde::Serialize;
use std::path::Path;
use tokio::fs;
use lofty::prelude::{Accessor, TaggedFileExt, AudioFile};
use lofty::probe::Probe;

#[derive(Debug, Clone, Serialize)]
pub struct MusicInfo {
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub year: Option<u32>,
    pub duration_secs: Option<u64>,
    pub cover_art: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct LyricsLine {
    pub time_ms: u64,
    pub text: String,
}

pub async fn get_music_info(path: &str) -> Result<MusicInfo> {
    let path = Path::new(path);
    
    // Use lofty for audio metadata
    let tagged_file = Probe::open(path)?.read()?;
    
    let mut info = MusicInfo {
        title: None,
        artist: None,
        album: None,
        year: None,
        duration_secs: None,
        cover_art: None,
    };
    
    if let Some(tag) = tagged_file.primary_tag().or(tagged_file.first_tag()) {
        info.title = tag.title().map(|s: std::borrow::Cow<'_, str>| s.to_string());
        info.artist = tag.artist().map(|s: std::borrow::Cow<'_, str>| s.to_string());
        info.album = tag.album().map(|s: std::borrow::Cow<'_, str>| s.to_string());
        info.year = tag.year();
    }
    
    // Get duration from properties
    let properties = tagged_file.properties();
    info.duration_secs = Some(properties.duration().as_secs());
    
    // Check for cover art file
    let cover_path = path.with_extension("jpg");
    if cover_path.exists() {
        info.cover_art = Some(cover_path.to_string_lossy().to_string());
    } else {
        // Check for folder.jpg or cover.jpg in same directory
        if let Some(parent) = path.parent() {
            for name in &["cover.jpg", "folder.jpg", "album.jpg"] {
                let cover = parent.join(name);
                if cover.exists() {
                    info.cover_art = Some(cover.to_string_lossy().to_string());
                    break;
                }
            }
        }
    }
    
    Ok(info)
}

pub async fn get_lyrics(path: &str) -> Result<Vec<LyricsLine>> {
    let audio_path = Path::new(path);
    
    // Try to find .lrc file with same name
    let lrc_path = audio_path.with_extension("lrc");
    
    if !lrc_path.exists() {
        return Err(anyhow::anyhow!("Lyrics file not found"));
    }
    
    let content = fs::read_to_string(&lrc_path).await?;
    parse_lrc(&content)
}

/// Parse LRC lyrics file format
fn parse_lrc(content: &str) -> Result<Vec<LyricsLine>> {
    let mut lines = Vec::new();
    
    for line in content.lines() {
        let line = line.trim();
        if line.is_empty() || !line.starts_with('[') {
            continue;
        }
        
        // Skip metadata tags like [ar:Artist], [ti:Title]
        if line.chars().nth(1).map(|c| c.is_alphabetic()).unwrap_or(false) {
            continue;
        }
        
        // Parse timestamp [mm:ss.xx] or [mm:ss:xx]
        if let Some(end_bracket) = line.find(']') {
            let timestamp = &line[1..end_bracket];
            let text = line[end_bracket + 1..].trim();
            
            if let Some(time_ms) = parse_lrc_timestamp(timestamp) {
                lines.push(LyricsLine {
                    time_ms,
                    text: text.to_string(),
                });
            }
        }
    }
    
    lines.sort_by_key(|l| l.time_ms);
    Ok(lines)
}

fn parse_lrc_timestamp(ts: &str) -> Option<u64> {
    // Format: mm:ss.xx or mm:ss:xx
    let parts: Vec<&str> = ts.split(|c| c == ':' || c == '.').collect();
    
    if parts.len() >= 2 {
        let minutes: u64 = parts[0].parse().ok()?;
        let seconds: u64 = parts[1].parse().ok()?;
        let centis: u64 = parts.get(2).and_then(|s| s.parse().ok()).unwrap_or(0);
        
        Some(minutes * 60 * 1000 + seconds * 1000 + centis * 10)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_parse_lrc_timestamp() {
        assert_eq!(parse_lrc_timestamp("01:23.45"), Some(83450));
        assert_eq!(parse_lrc_timestamp("00:05.00"), Some(5000));
        assert_eq!(parse_lrc_timestamp("02:30:50"), Some(150500));
    }
    
    #[test]
    fn test_parse_lrc() {
        let content = r#"
[ar:Artist Name]
[ti:Song Title]
[00:12.00]First line of lyrics
[00:17.20]Second line of lyrics
        "#;
        
        let lines = parse_lrc(content).unwrap();
        assert_eq!(lines.len(), 2);
        assert_eq!(lines[0].time_ms, 12000);
        assert_eq!(lines[0].text, "First line of lyrics");
    }
}
