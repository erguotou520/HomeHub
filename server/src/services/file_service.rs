use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::path::Path;
use tokio::fs;

use crate::utils::video_detector::{detect_video_entry, VideoEntry};

// ─── Shared types ─────────────────────────────────────────────────────────

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct MediaMetadata {
    pub title: Option<String>,
    pub poster: Option<String>,
    pub fanart: Option<String>,
    pub year: Option<String>,
    pub plot: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct FileEntry {
    pub name: String,
    pub path: String,
    pub is_dir: bool,
    pub size: Option<u64>,
    pub modified: Option<i64>,
    pub mime_type: Option<String>,
    pub thumbnail: Option<String>,
    pub metadata: Option<MediaMetadata>,
}


pub async fn list_directory(path: &str, app_type: &str) -> Result<Vec<FileEntry>> {
    let path = Path::new(path);
    
    if !path.exists() {
        return Err(anyhow::anyhow!("Directory not found"));
    }
    
    if !path.is_dir() {
        return Err(anyhow::anyhow!("Path is not a directory"));
    }
    
    let mut entries = Vec::new();
    let mut dir = fs::read_dir(path).await?;
    
    while let Some(entry) = dir.next_entry().await? {
        let metadata = entry.metadata().await?;
        let file_name = entry.file_name().to_string_lossy().to_string();
        let file_path = entry.path();
        
        // Skip hidden files
        if file_name.starts_with('.') {
            continue;
        }
        
        let is_dir = metadata.is_dir();
        let size = if is_dir { None } else { Some(metadata.len()) };
        let modified = metadata.modified().ok().map(|t| {
            t.duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs() as i64)
                .unwrap_or(0)
        });
        
        // Detect mime type
        let mime_type = if !is_dir {
            mime_guess::from_path(&file_path)
                .first()
                .map(|m| m.to_string())
        } else {
            None
        };
        
        // For video app, detect metadata
        let media_metadata = if app_type == "videos" && is_dir {
            detect_video_metadata(&file_path).await
        } else {
            None
        };
        
        entries.push(FileEntry {
            name: file_name,
            path: file_path.to_string_lossy().to_string(),
            is_dir,
            size,
            modified,
            mime_type,
            thumbnail: None, // Generated on demand
            metadata: media_metadata,
        });
    }
    
    // Sort: directories first, then by name
    entries.sort_by(|a, b| {
        match (a.is_dir, b.is_dir) {
            (true, false) => std::cmp::Ordering::Less,
            (false, true) => std::cmp::Ordering::Greater,
            _ => a.name.to_lowercase().cmp(&b.name.to_lowercase()),
        }
    });
    
    Ok(entries)
}

async fn detect_video_metadata(dir_path: &Path) -> Option<MediaMetadata> {
    let video_entry = detect_video_entry(dir_path).await.ok()?;
    
    match video_entry {
        VideoEntry::MovieWithMeta { poster, fanart, nfo_data, .. } => {
            Some(MediaMetadata {
                title: nfo_data.as_ref().and_then(|n| n.title.clone()),
                poster,
                fanart,
                year: nfo_data.as_ref().and_then(|n| n.year.clone()),
                plot: nfo_data.as_ref().and_then(|n| n.plot.clone()),
            })
        }
        VideoEntry::TvSeries { title, poster, .. } => {
            Some(MediaMetadata {
                title: Some(title),
                poster,
                fanart: None,
                year: None,
                plot: None,
            })
        }
        _ => None,
    }
}

pub async fn copy_file(src: &str, dest: &str) -> Result<()> {
    let src_path = Path::new(src);
    let dest_path = Path::new(dest);
    
    if src_path.is_dir() {
        copy_dir_recursive(src_path, dest_path).await
    } else {
        fs::copy(src_path, dest_path).await?;
        Ok(())
    }
}

async fn copy_dir_recursive(src: &Path, dest: &Path) -> Result<()> {
    fs::create_dir_all(dest).await?;
    
    let mut dir = fs::read_dir(src).await?;
    while let Some(entry) = dir.next_entry().await? {
        let src_path = entry.path();
        let dest_path = dest.join(entry.file_name());
        
        if src_path.is_dir() {
            Box::pin(copy_dir_recursive(&src_path, &dest_path)).await?;
        } else {
            fs::copy(&src_path, &dest_path).await?;
        }
    }
    
    Ok(())
}

pub async fn move_file(src: &str, dest: &str) -> Result<()> {
    fs::rename(src, dest).await?;
    Ok(())
}

pub async fn delete_file(path: &str) -> Result<()> {
    let path = Path::new(path);
    
    if path.is_dir() {
        fs::remove_dir_all(path).await?;
    } else {
        fs::remove_file(path).await?;
    }
    
    Ok(())
}
