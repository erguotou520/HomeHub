//! Machine learning pipeline: object / scene detection and face clustering.
//!
//! Detection backends live behind the [`Detector`] trait:
//!
//! * `stub` – deterministic no-op, used when no model is configured.
//! * `onnx` – real ONNX Runtime inference (`--features onnx`, needs models).
//!
//! Face grouping is implemented in pure Rust (perceptual hash + greedy
//! clustering against the group representative), see [`cluster`].

pub mod cluster;
pub mod stub;

#[cfg(feature = "onnx")]
pub mod clip;
#[cfg(feature = "onnx")]
pub mod onnx;

use anyhow::Result;
use image::DynamicImage;
use std::path::{Path, PathBuf};

use crate::config::MlConfig;

/// One detected object / scene label.
#[derive(Debug, Clone)]
pub struct Detection {
    pub tag: String,
    pub confidence: f32,
}

/// A detected face, in normalised (0..1) coordinates.
#[derive(Debug, Clone)]
pub struct FaceBox {
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
    /// Perceptual hash of the cropped face (fallback clustering signal).
    pub hash: String,
    /// Recognition embedding (128-d, unit length not required) when a
    /// recognizer model is configured; cosine similarity clusters same-person
    /// faces far more reliably than the phash above.
    pub embedding: Option<Vec<f32>>,
}

pub trait Detector: Send + Sync {
    fn name(&self) -> &'static str;

    /// COCO-style object labels.
    fn detect_objects(&self, image: &DynamicImage) -> Result<Vec<Detection>>;

    /// Scene / landscape labels.
    fn detect_scenes(&self, image: &DynamicImage) -> Result<Vec<Detection>>;

    /// Face bounding boxes.
    fn detect_faces(&self, image: &DynamicImage) -> Result<Vec<FaceBox>>;
}

/// Build the detector described by the configuration.
///
/// `data_dir` is the configured `global.data-dir`; relative model paths resolve
/// against it (see [`resolve_model_path`]).
pub fn build(config: &MlConfig, data_dir: &str) -> std::sync::Arc<dyn Detector> {
    #[cfg(feature = "onnx")]
    {
        if config.backend == "onnx" {
            match onnx::OnnxDetector::new(config, Path::new(data_dir)) {
                Ok(d) => {
                    tracing::info!("ml backend: onnx");
                    return std::sync::Arc::new(d);
                }
                Err(e) => tracing::warn!("onnx backend unavailable ({}), using stub", e),
            }
        }
    }
    #[cfg(not(feature = "onnx"))]
    {
        let _ = data_dir;
    }
    if config.backend == "onnx" {
        tracing::warn!("ml backend 'onnx' requested but the `onnx` feature is not compiled in");
    }
    std::sync::Arc::new(stub::StubDetector)
}

/// Resolve a configured model path.
///
/// Absolute paths are used as-is. Relative paths are resolved against the
/// configured `data-dir` first — this is what the docs promise — falling back
/// to the `DATA_DIR` environment variable (the Docker image sets it) and
/// finally to the process working directory. The `data-dir` candidate is
/// returned even when nothing exists, so the resulting error message points at
/// the place the operator was told to put the file.
pub fn resolve_model_path(path: &str, data_dir: &Path) -> PathBuf {
    let p = Path::new(path);
    if p.is_absolute() {
        return p.to_path_buf();
    }
    let primary = if data_dir.as_os_str().is_empty() {
        p.to_path_buf()
    } else {
        data_dir.join(p)
    };
    if primary.exists() {
        return primary;
    }
    if let Ok(env_dir) = std::env::var("DATA_DIR") {
        let alt = Path::new(&env_dir).join(p);
        if alt.exists() {
            return alt;
        }
    }
    if p.exists() {
        return p.to_path_buf();
    }
    primary
}


/// Filter out labels below the threshold, excluded labels, and duplicates.
pub fn sanitize(
    detections: Vec<Detection>,
    threshold: f32,
    excluded: &[String],
    limit: usize,
) -> Vec<Detection> {
    let mut out: Vec<Detection> = Vec::new();
    let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();
    for d in detections {
        let tag = d.tag.trim().to_lowercase().replace(' ', "_");
        if tag.is_empty() || d.confidence < threshold {
            continue;
        }
        if excluded.iter().any(|e| e.eq_ignore_ascii_case(&tag)) {
            continue;
        }
        if !seen.insert(tag.clone()) {
            continue;
        }
        out.push(Detection {
            tag,
            confidence: d.confidence,
        });
        if out.len() >= limit {
            break;
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn absolute_model_path_is_untouched() {
        let got = resolve_model_path("/opt/models/yolov8n.onnx", Path::new("/data"));
        assert_eq!(got, PathBuf::from("/opt/models/yolov8n.onnx"));
    }

    /// The regression this guards: relative paths used to resolve against the
    /// `DATA_DIR` env var / cwd, so a model placed in the directory the config
    /// actually points at (`data-dir`) was reported as missing.
    #[test]
    fn relative_model_path_resolves_against_data_dir() {
        let tmp = std::env::temp_dir().join(format!("hh-ml-{}", std::process::id()));
        let models = tmp.join("models");
        std::fs::create_dir_all(&models).unwrap();
        std::fs::write(models.join("scene.onnx"), b"x").unwrap();

        let got = resolve_model_path("models/scene.onnx", &tmp);
        assert_eq!(got, models.join("scene.onnx"));
        assert!(got.exists());

        std::fs::remove_dir_all(&tmp).ok();
    }

    #[test]
    fn missing_model_reports_the_configured_location() {
        let got = resolve_model_path("models/nope.onnx", Path::new("/data"));
        assert_eq!(got, PathBuf::from("/data/models/nope.onnx"));
        assert!(!got.exists());
    }

    #[test]
    fn sanitize_drops_low_confidence_excluded_and_duplicates() {
        let dets = vec![
            Detection { tag: "Person".into(), confidence: 0.9 },
            Detection { tag: "person".into(), confidence: 0.8 },
            Detection { tag: "dog".into(), confidence: 0.1 },
            Detection { tag: "tv".into(), confidence: 0.7 },
        ];
        let out = sanitize(dets, 0.35, &["tv".to_string()], 10);
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].tag, "person");
    }
}
