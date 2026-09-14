//! Video metadata probing and poster extraction via ffprobe/ffmpeg.
//!
//! Both binaries are optional: when missing, videos are still indexed and
//! browsable — they just show no duration badge and fall back to the
//! neighbour-poster thumbnail lookup.

use std::path::Path;

use anyhow::Result;
use serde::Deserialize;

/// Metadata extracted from a media file with ffprobe.
#[derive(Debug, Clone, Default)]
pub struct VideoMeta {
    pub duration_ms: Option<i64>,
    pub width: Option<i64>,
    pub height: Option<i64>,
    pub taken_at: Option<i64>,
    pub gps_lat: Option<f64>,
    pub gps_lng: Option<f64>,
    pub codec: Option<String>,
}

#[derive(Debug, Deserialize)]
struct FfprobeOutput {
    #[serde(default)]
    streams: Vec<FfprobeStream>,
    #[serde(default)]
    format: Option<FfprobeFormat>,
}

#[derive(Debug, Deserialize)]
struct FfprobeStream {
    codec_type: Option<String>,
    codec_name: Option<String>,
    width: Option<i64>,
    height: Option<i64>,
    #[serde(default)]
    tags: std::collections::HashMap<String, String>,
    #[serde(default)]
    side_data_list: Vec<FfprobeSideData>,
}

#[derive(Debug, Deserialize)]
struct FfprobeSideData {
    #[serde(rename = "side_data_type", default)]
    side_data_type: String,
    #[serde(default)]
    location: Option<String>,
}

#[derive(Debug, Deserialize)]
struct FfprobeFormat {
    duration: Option<String>,
    #[serde(default)]
    tags: std::collections::HashMap<String, String>,
}

/// Look up a binary from config, falling back to PATH.
pub fn resolve(configured: &str, fallback: &str) -> Option<String> {
    if !configured.is_empty() && which_exists(configured) {
        return Some(configured.to_string());
    }
    if which_exists(fallback) {
        return Some(fallback.to_string());
    }
    None
}

fn which_exists(name: &str) -> bool {
    if name.contains('/') {
        return std::path::Path::new(name).exists();
    }
    std::env::var("PATH")
        .unwrap_or_default()
        .split(':')
        .any(|dir| std::path::Path::new(dir).join(name).exists())
}

/// Run ffprobe on a media file and parse the essentials.
pub async fn probe(file_path: &Path, ffprobe_path: &str) -> Result<VideoMeta> {
    let Some(bin) = resolve(ffprobe_path, "ffprobe") else {
        anyhow::bail!("ffprobe not available");
    };
    let output = tokio::process::Command::new(bin)
        .args([
            "-v", "quiet",
            "-print_format", "json",
            "-show_format", "-show_streams",
        ])
        .arg(file_path)
        .output()
        .await?;
    if !output.status.success() {
        anyhow::bail!("ffprobe failed: {}", String::from_utf8_lossy(&output.stderr));
    }
    let parsed: FfprobeOutput = serde_json::from_slice(&output.stdout)?;

    let mut meta = VideoMeta::default();
    if let Some(format) = &parsed.format {
        meta.duration_ms = format
            .duration
            .as_deref()
            .and_then(|d| d.parse::<f64>().ok())
            .map(|secs| (secs * 1000.0).round() as i64);
        // mp4/mov creation_time (UTC "2024-05-01T10:00:00.000000Z").
        for key in ["creation_time", "com.apple.quicktime.creationdate"] {
            if let Some(ts) = format.tags.get(key).and_then(|s| parse_creation_time(s)) {
                meta.taken_at = Some(ts);
                break;
            }
        }
    }
    for stream in &parsed.streams {
        if stream.codec_type.as_deref() != Some("video") {
            continue;
        }
        meta.width = stream.width;
        meta.height = stream.height;
        meta.codec = stream.codec_name.clone();
        if meta.taken_at.is_none() {
            for key in ["creation_time", "com.apple.quicktime.creationdate"] {
                if let Some(ts) = stream.tags.get(key).and_then(|s| parse_creation_time(s)) {
                    meta.taken_at = Some(ts);
                    break;
                }
            }
        }
        // GPS from quicktime side data: "±dd.ddd±ddd.ddd/".
        for sd in &stream.side_data_list {
            if sd.side_data_type == "Display Matrix" {
                continue;
            }
            if let Some(loc) = &sd.location {
                if let Some((lat, lng)) = parse_location(loc) {
                    meta.gps_lat = Some(lat);
                    meta.gps_lng = Some(lng);
                }
            }
        }
        break;
    }
    Ok(meta)
}

/// Parse an ISO-8601 UTC timestamp into unix seconds.
fn parse_creation_time(s: &str) -> Option<i64> {
    let s = s.trim();
    chrono::DateTime::parse_from_rfc3339(s)
        .map(|d| d.timestamp())
        .ok()
        .or_else(|| {
            // ffmpeg sometimes emits "2024-05-01 10:00:00".
            chrono::NaiveDateTime::parse_from_str(s, "%Y-%m-%d %H:%M:%S")
                .ok()
                .map(|d| d.and_utc().timestamp())
        })
}

/// Parse quicktime location "+31.2304+121.4737/" or "+31.2304-121.4737/".
fn parse_location(loc: &str) -> Option<(f64, f64)> {
    let body = loc.trim().trim_end_matches('/');
    let bytes: Vec<char> = body.chars().collect();
    let split = bytes
        .iter()
        .skip(1)
        .position(|c| *c == '+' || *c == '-')?;
    let lat: f64 = body[..split + 1].parse().ok()?;
    let lng: f64 = body[split + 1..].parse().ok()?;
    Some((lat, lng))
}

/// Extract the first video frame (at ~1s) as a JPEG poster.
pub async fn extract_poster(
    file_path: &Path,
    out_path: &Path,
    ffmpeg_path: &str,
) -> Result<()> {
    let Some(bin) = resolve(ffmpeg_path, "ffmpeg") else {
        anyhow::bail!("ffmpeg not available");
    };
    if let Some(parent) = out_path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    let src = file_path.to_string_lossy().to_string();
    let dst = out_path.to_string_lossy().to_string();
    let output = tokio::process::Command::new(bin)
        .args([
            "-y",
            "-v", "error",
            "-ss", "1",
            "-i", &src,
            "-frames:v", "1",
            "-vf", "scale='min(256,iw)':-2",
            &dst,
        ])
        .output()
        .await?;
    if !output.status.success() {
        anyhow::bail!("ffmpeg failed: {}", String::from_utf8_lossy(&output.stderr));
    }
    if !out_path.exists() {
        anyhow::bail!("ffmpeg produced no output");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_location() {
        assert_eq!(
            parse_location("+31.2304+121.4737/"),
            Some((31.2304, 121.4737))
        );
        assert_eq!(
            parse_location("-33.8688+151.2093/"),
            Some((-33.8688, 151.2093))
        );
        assert_eq!(parse_location("garbage"), None);
    }

    #[test]
    fn test_parse_creation_time() {
        assert_eq!(
            parse_creation_time("2024-05-01T10:00:00.000000Z"),
            Some(1714557600)
        );
        assert_eq!(parse_creation_time("n/a"), None);
    }
}
