//! File-level image editing.
//!
//! The album endpoints (`/api/photos/:id/rotate`) only work for assets that
//! are already in `photo_assets`. The file manager needs the same power for
//! *any* image inside a registered directory, so the pixel pipeline lives here
//! and both callers can share it.
//!
//! Rules that matter:
//! * JPEG rotate/flip stays lossless via `jpegtran` when the binary exists.
//! * Every destructive edit archives the current pixels to `.originals/` first,
//!   which is what makes "restore original" possible.
//! * The thumbnail cache and the search index are invalidated afterwards.

use std::path::{Path, PathBuf};

use anyhow::{anyhow, bail, Context, Result};
use serde::Serialize;

use crate::config::Config;
use crate::models::dir::DirRecord;
use crate::services::paths::safe_join;

/// A single edit step. Several can be batched into one request.
#[derive(Debug, Clone)]
pub enum Op {
    Rotate(i32),
    FlipH,
    FlipV,
    Resize {
        width: Option<u32>,
        height: Option<u32>,
        quality: Option<u8>,
    },
}

impl Op {
    /// `true` when the step can be done without re-encoding pixels.
    fn is_lossless(&self) -> bool {
        matches!(self, Op::Rotate(_) | Op::FlipH | Op::FlipV)
    }
}

#[derive(Debug, Serialize)]
pub struct TransformResult {
    pub path: String,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub size: i64,
    pub lossless: bool,
    pub archived: bool,
}

/// Parse `[{"op":"rotate","angle":90}, {"op":"flip-h"}]` into typed steps.
pub fn parse_ops(raw: &[serde_json::Value]) -> Result<Vec<Op>> {
    let mut ops = Vec::with_capacity(raw.len());
    for item in raw {
        let kind = item
            .get("op")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow!("missing 'op'"))?;
        let op = match kind {
            "rotate" => {
                let angle = item
                    .get("angle")
                    .and_then(|v| v.as_i64())
                    .ok_or_else(|| anyhow!("rotate needs 'angle'"))?;
                if ![90, 180, 270, -90, -180, -270].contains(&angle) {
                    bail!("angle must be 90, 180 or 270");
                }
                Op::Rotate(angle as i32)
            }
            "flip-h" | "flip_h" | "fliph" => Op::FlipH,
            "flip-v" | "flip_v" | "flipv" => Op::FlipV,
            "resize" => Op::Resize {
                width: item.get("width").and_then(|v| v.as_u64()).map(|v| v as u32),
                height: item.get("height").and_then(|v| v.as_u64()).map(|v| v as u32),
                quality: item.get("quality").and_then(|v| v.as_u64()).map(|v| v as u8),
            },
            other => bail!("unsupported op '{}'", other),
        };
        ops.push(op);
    }
    if ops.is_empty() {
        bail!("no operations supplied");
    }
    Ok(ops)
}

const IMAGE_EXTS: [&str; 8] = ["jpg", "jpeg", "jpe", "jfif", "png", "webp", "gif", "bmp"];

pub fn is_image(path: &Path) -> bool {
    path.extension()
        .and_then(|e| e.to_str())
        .map(|e| IMAGE_EXTS.contains(&e.to_lowercase().as_str()))
        .unwrap_or(false)
}

/// Apply `ops` to `<dir>/<rel_path>`, archiving the original first.
///
/// Returns the new dimensions and size. The caller is responsible for
/// invalidating caches and re-queueing a scan.
pub async fn transform(
    full: &Path,
    dir: &DirRecord,
    rel_path: &str,
    ops: &[Op],
    config: &Config,
) -> Result<TransformResult> {
    if !full.is_file() {
        bail!("not a file: {}", full.display());
    }
    if !is_image(full) {
        bail!("unsupported image type: {}", full.display());
    }

    let archived = crate::services::originals::archive(full, dir, rel_path, config)
        .await?
        .is_some();

    let lossless = ops.iter().all(Op::is_lossless);
    let jpegtran = config.runtime.compression.jpegtran_path.clone();

    let path = full.to_path_buf();
    let ops = ops.to_vec();
    tokio::task::spawn_blocking(move || apply(&path, &ops, lossless, &jpegtran))
        .await
        .map_err(|e| anyhow!("image task panicked: {e}"))??;

    let (width, height) = image::image_dimensions(full).unwrap_or((0, 0));
    let size = std::fs::metadata(full)?.len() as i64;

    Ok(TransformResult {
        path: rel_path.to_string(),
        width: if width > 0 { Some(width) } else { None },
        height: if height > 0 { Some(height) } else { None },
        size,
        lossless,
        archived,
    })
}

/// Restore the archived original over the current file.
pub async fn restore_original(
    full: &Path,
    dir: &DirRecord,
    rel_path: &str,
    config: &Config,
) -> Result<TransformResult> {
    let original = original_path(dir, rel_path, config)
        .ok_or_else(|| anyhow!("invalid archive path"))?;
    if !original.exists() {
        bail!("no archived original for {}", rel_path);
    }
    let tmp = full.with_extension(format!("restore.{}.tmp", unique_nonce()));
    tokio::fs::copy(&original, &tmp).await?;
    tokio::fs::rename(&tmp, full).await?;

    let (width, height) = image::image_dimensions(full).unwrap_or((0, 0));
    let size = std::fs::metadata(full)?.len() as i64;
    Ok(TransformResult {
        path: rel_path.to_string(),
        width: if width > 0 { Some(width) } else { None },
        height: if height > 0 { Some(height) } else { None },
        size,
        lossless: true,
        archived: false,
    })
}

/// Where the archived original for this file lives (if any).
pub fn original_path(dir: &DirRecord, rel_path: &str, config: &Config) -> Option<PathBuf> {
    safe_join(&config.originals_dir().join(&dir.name), rel_path)
}

pub fn has_original(dir: &DirRecord, rel_path: &str, config: &Config) -> bool {
    original_path(dir, rel_path, config)
        .map(|p| p.exists())
        .unwrap_or(false)
}

// ────────────────────────────── pixel work ──────────────────────────────

/// Unique per-call suffix so concurrent transforms of the same file never
/// share temp files (deterministic names made `rename` fail under load).
fn unique_nonce() -> u128 {
    use std::sync::atomic::{AtomicU64, Ordering};
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let n = COUNTER.fetch_add(1, Ordering::Relaxed) as u128;
    (n << 32) | (std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.subsec_nanos() as u128)
        .unwrap_or(0))
}

fn apply(path: &Path, ops: &[Op], lossless: bool, jpegtran_path: &str) -> Result<()> {
    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("jpg")
        .to_lowercase();
    let is_jpeg = matches!(ext.as_str(), "jpg" | "jpeg" | "jpe" | "jfif");

    // Lossless fast path: jpegtran can do each step without re-encoding.
    if lossless && is_jpeg && !jpegtran_path.trim().is_empty() {
        if let Ok(()) = lossless_chain(path, ops, jpegtran_path) {
            return Ok(());
        }
        // Fall through to the decoded path; the original was already archived.
    }

    decoded_chain(path, ops, &ext)
}

/// Run each step through `jpegtran`, feeding the output of one into the next.
fn lossless_chain(path: &Path, ops: &[Op], jpegtran_path: &str) -> Result<()> {
    let mut current = path.to_path_buf();
    let mut stage = 0usize;
    let mut temps: Vec<PathBuf> = Vec::new();
    let nonce = unique_nonce();

    for op in ops {
        let tmp = path.with_extension(format!("edit{stage}.{nonce}.tmp"));
        let mut cmd = std::process::Command::new(jpegtran_path);
        cmd.arg("-copy").arg("all");
        match op {
            Op::Rotate(a) => {
                let a = *a;
                let a = if a < 0 { a + 360 } else { a };
                cmd.arg("-rotate").arg(a.to_string());
            }
            Op::FlipH => {
                cmd.arg("-flip").arg("horizontal");
            }
            Op::FlipV => {
                cmd.arg("-flip").arg("vertical");
            }
            Op::Resize { .. } => bail!("resize is not lossless"),
        };
        cmd.arg("-outfile").arg(&tmp).arg(&current);
        let out = cmd.output().context("run jpegtran")?;
        if !out.status.success() {
            bail!(
                "jpegtran failed: {}",
                String::from_utf8_lossy(&out.stderr).trim()
            );
        }
        temps.push(tmp.clone());
        current = tmp;
        stage += 1;
    }

    // Reset orientation so viewers do not rotate the pixels a second time.
    crate::services::exif::reset_jpeg_orientation(&current, 1).ok();
    // Clean up intermediates on every exit path — a failed rename used to
    // leave the staged files behind forever.
    match std::fs::rename(&current, path) {
        Ok(()) => {
            for t in temps {
                let _ = std::fs::remove_file(t);
            }
            Ok(())
        }
        Err(e) => {
            for t in &temps {
                let _ = std::fs::remove_file(t);
            }
            Err(e).context("atomic replace")
        }
    }
}

/// Decode once, apply everything in memory, re-encode.
fn decoded_chain(path: &Path, ops: &[Op], ext: &str) -> Result<()> {
    let mut img = image::open(path)?;
    // Bake the EXIF orientation in so the saved pixels match what was shown.
    img = apply_orientation(img, crate::services::exif::orientation_of(path));

    for op in ops {
        img = match op {
            Op::Rotate(a) => match a {
                90 | -270 => img.rotate90(),
                180 | -180 => img.rotate180(),
                270 | -90 => img.rotate270(),
                _ => img,
            },
            Op::FlipH => img.fliph(),
            Op::FlipV => img.flipv(),
            Op::Resize { width, height, .. } => {
                let (w, h) = (img.width(), img.height());
                let target = match (*width, *height) {
                    (Some(w2), Some(h2)) => (w2, h2),
                    (Some(w2), None) => (w2, (h as f64 * w2 as f64 / w as f64).round() as u32),
                    (None, Some(h2)) => ((w as f64 * h2 as f64 / h as f64).round() as u32, h2),
                    (None, None) => (w, h),
                };
                if target.0 == 0 || target.1 == 0 {
                    bail!("invalid resize target");
                }
                img.resize(target.0, target.1, image::imageops::FilterType::Lanczos3)
            }
        };
    }

    let quality = ops.iter().find_map(|op| match op {
        Op::Resize { quality, .. } => *quality,
        _ => None,
    });
    let tmp = path.with_extension(format!("edit.{}.tmp", unique_nonce()));
    let result = (|| -> Result<()> {
        match ext {
            "png" => img.save_with_format(&tmp, image::ImageFormat::Png)?,
            "webp" => img.save_with_format(&tmp, image::ImageFormat::WebP)?,
            "gif" => img.save_with_format(&tmp, image::ImageFormat::Gif)?,
            "bmp" => img.save_with_format(&tmp, image::ImageFormat::Bmp)?,
            _ => {
                // Least-bad option for JPEG: the untouched pixels are archived.
                let q = quality.unwrap_or(95).clamp(1, 100);
                let mut out = std::fs::File::create(&tmp)?;
                let mut enc = image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out, q);
                enc.encode_image(&img)?;
            }
        }
        Ok(())
    })();

    match result {
        Ok(()) => match std::fs::rename(&tmp, path) {
            Ok(()) => {
                if matches!(ext, "jpg" | "jpeg" | "jpe" | "jfif") {
                    crate::services::exif::reset_jpeg_orientation(path, 1).ok();
                }
                Ok(())
            }
            Err(e) => {
                let _ = std::fs::remove_file(&tmp);
                Err(e).context("atomic replace")
            }
        },
        Err(e) => {
            let _ = std::fs::remove_file(&tmp);
            Err(e)
        }
    }
}

/// Rotate the decoded pixels to match the EXIF orientation tag.
pub fn apply_orientation(img: image::DynamicImage, orientation: u32) -> image::DynamicImage {
    match orientation {
        2 => img.fliph(),
        3 => img.rotate180(),
        4 => img.flipv(),
        5 => img.rotate90().fliph(),
        6 => img.rotate90(),
        7 => img.rotate270().fliph(),
        8 => img.rotate270(),
        _ => img,
    }
}
