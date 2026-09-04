use anyhow::Result;
use image::ImageFormat;
use std::path::{Path};
use tokio::fs;

const THUMBNAIL_SIZE: u32 = 300;
const THUMBNAIL_DIR: &str = ".thumbnails";

pub async fn get_or_create_thumbnail(file_path: &str) -> Result<String> {
    let file_path = Path::new(file_path);
    
    // Create thumbnail path in same directory
    let parent = file_path.parent().ok_or_else(|| anyhow::anyhow!("Invalid path"))?;
    let thumb_dir = parent.join(THUMBNAIL_DIR);
    
    let file_name = file_path.file_name()
        .ok_or_else(|| anyhow::anyhow!("Invalid filename"))?
        .to_string_lossy();
    
    let thumb_name = format!("{}.thumb.jpg", file_name);
    let thumb_path = thumb_dir.join(&thumb_name);
    
    // Return existing thumbnail
    if thumb_path.exists() {
        return Ok(thumb_path.to_string_lossy().to_string());
    }
    
    // Create thumbnail directory
    fs::create_dir_all(&thumb_dir).await?;
    
    // Generate thumbnail
    let thumb_path_clone = thumb_path.clone();
    let file_path_string = file_path.to_string_lossy().to_string();
    
    // Use blocking task for image processing
    tokio::task::spawn_blocking(move || {
        generate_thumbnail(&file_path_string, &thumb_path_clone.to_string_lossy())
    }).await??;
    
    Ok(thumb_path.to_string_lossy().to_string())
}

fn generate_thumbnail(src_path: &str, dest_path: &str) -> Result<()> {
    let img = image::open(src_path)?;
    
    // Resize maintaining aspect ratio
    let thumbnail = img.thumbnail(THUMBNAIL_SIZE, THUMBNAIL_SIZE);
    
    // Save as JPEG
    thumbnail.save_with_format(dest_path, ImageFormat::Jpeg)?;
    
    Ok(())
}

/// Get thumbnail for video (extract frame) - placeholder for now
pub async fn get_video_thumbnail(file_path: &str) -> Result<String> {
    // For videos, we'd need ffmpeg to extract a frame
    // For now, check for existing poster files
    let file_path = Path::new(file_path);
    let parent = file_path.parent().ok_or_else(|| anyhow::anyhow!("Invalid path"))?;
    
    // Check for poster images
    let stem = file_path.file_stem()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_default();
    
    let poster_names = [
        format!("{}-poster.jpg", stem),
        format!("{}.jpg", stem),
        "poster.jpg".to_string(),
        "folder.jpg".to_string(),
    ];
    
    for name in poster_names {
        let poster_path = parent.join(&name);
        if poster_path.exists() {
            return Ok(poster_path.to_string_lossy().to_string());
        }
    }
    
    Err(anyhow::anyhow!("No thumbnail available"))
}
