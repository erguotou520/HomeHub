//! Lossless compression: PNG via oxipng, JPEG via `jpegtran` (libjpeg-turbo/mozjpeg).
//!
//! The file is only replaced when the saving reaches the configured threshold
//! and the pixel data is provably unchanged. No `.originals/` copy is made.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result};

use crate::config::{CompressionConfig, Config};

#[derive(Debug, Clone, Default)]
pub struct CompressResult {
    pub applied: bool,
    pub original_size: u64,
    pub new_size: u64,
    pub saved: u64,
    pub reason: String,
}

/// Try to recompress `path` in place. Never changes the decoded pixels.
pub async fn compress(path: &Path, config: &Config) -> Result<CompressResult> {
    let cfg = &config.runtime.compression;
    if !cfg.enabled {
        return Ok(CompressResult {
            reason: "compression disabled".into(),
            ..Default::default()
        });
    }

    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_lowercase();

    let original_size = std::fs::metadata(path).map(|m| m.len()).unwrap_or(0);
    let mut result = CompressResult {
        original_size,
        ..Default::default()
    };

    let tmp = temp_path(path);
    let produced = match ext.as_str() {
        "png" => compress_png(path, &tmp, cfg).await?,
        "jpg" | "jpeg" => compress_jpeg(path, &tmp, cfg).await?,
        _ => {
            result.reason = "unsupported type".into();
            None
        }
    };

    let Some((new_size, verified)) = produced else {
        let _ = tokio::fs::remove_file(&tmp).await;
        if result.reason.is_empty() {
            result.reason = "no improvement".into();
        }
        return Ok(result);
    };

    if !verified {
        let _ = tokio::fs::remove_file(&tmp).await;
        result.reason = "pixel mismatch, kept original".into();
        return Ok(result);
    }

    let saved = original_size.saturating_sub(new_size);
    let percent = if original_size > 0 {
        saved as f64 / original_size as f64 * 100.0
    } else {
        0.0
    };

    if percent < cfg.min_saving_percent {
        let _ = tokio::fs::remove_file(&tmp).await;
        result.reason = format!("saving {:.1}% below threshold", percent);
        return Ok(result);
    }

    // Preserve the original mtime so scanners do not see a "changed" file.
    let mtime = std::fs::metadata(path).and_then(|m| m.modified()).ok();
    tokio::fs::rename(&tmp, path).await.context("atomic replace")?;
    if let Some(t) = mtime {
        let ft = filetime::FileTime::from_system_time(t);
        let p = path.to_path_buf();
        tokio::task::spawn_blocking(move || filetime::set_file_mtime(&p, ft)).await??;
    }

    result.applied = true;
    result.new_size = new_size;
    result.saved = saved;
    result.reason = format!("saved {:.1}%", percent);
    Ok(result)
}

fn temp_path(path: &Path) -> PathBuf {
    let mut name = path.file_name().map(|n| n.to_os_string()).unwrap_or_default();
    name.push(".hh-compress.tmp");
    path.with_file_name(name)
}

async fn compress_png(
    path: &Path,
    out: &Path,
    cfg: &CompressionConfig,
) -> Result<Option<(u64, bool)>> {
    let src = path.to_path_buf();
    let dst = out.to_path_buf();
    let level = cfg.png_level;
    let produced = tokio::task::spawn_blocking(move || -> Result<bool> {
        let mut opts = oxipng::Options::from_preset(level);
        opts.strip = oxipng::StripChunks::None;
        opts.interlace = None;
        oxipng::optimize(
            &oxipng::InFile::Path(src),
            &oxipng::OutFile::Path {
                path: Some(dst),
                preserve_attrs: false,
            },
            &opts,
        )?;
        Ok(true)
    })
    .await?;

    match produced {
        Ok(true) => {
            let size = std::fs::metadata(out).map(|m| m.len()).unwrap_or(0);
            Ok(Some((size, pixels_equal(path, out))))
        }
        Ok(false) => Ok(None),
        Err(e) => {
            tracing::debug!("oxipng failed for {}: {}", path.display(), e);
            Ok(None)
        }
    }
}

async fn compress_jpeg(
    path: &Path,
    out: &Path,
    cfg: &CompressionConfig,
) -> Result<Option<(u64, bool)>> {
    // `jpegtran -copy all -optimize` is a lossless Huffman-table optimisation.
    let child = tokio::process::Command::new(&cfg.jpegtran_path)
        .arg("-copy")
        .arg("all")
        .arg("-optimize")
        .arg("-outfile")
        .arg(out)
        .arg(path)
        .output()
        .await;

    match child {
        Ok(output) if output.status.success() => {
            let size = std::fs::metadata(out).map(|m| m.len()).unwrap_or(0);
            Ok(Some((size, true)))
        }
        Ok(output) => {
            tracing::debug!(
                "jpegtran unavailable/failed for {}: {}",
                path.display(),
                String::from_utf8_lossy(&output.stderr)
            );
            Ok(None)
        }
        Err(e) => {
            tracing::debug!("cannot run {}: {}", cfg.jpegtran_path, e);
            Ok(None)
        }
    }
}

/// Compare the decoded pixels of two images.
fn pixels_equal(a: &Path, b: &Path) -> bool {
    match (image::open(a), image::open(b)) {
        (Ok(ia), Ok(ib)) => {
            if ia.width() != ib.width() || ia.height() != ib.height() {
                return false;
            }
            let ra = ia.to_rgba8();
            let rb = ib.to_rgba8();
            ra.as_raw() == rb.as_raw()
        }
        _ => false,
    }
}
