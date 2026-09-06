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
pub mod onnx;

use anyhow::Result;
use image::DynamicImage;

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
    /// Perceptual hash of the cropped face, used for clustering.
    pub hash: String,
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
pub fn build(config: &MlConfig) -> std::sync::Arc<dyn Detector> {
    #[cfg(feature = "onnx")]
    {
        if config.backend == "onnx" {
            match onnx::OnnxDetector::new(config) {
                Ok(d) => {
                    tracing::info!("ml backend: onnx");
                    return std::sync::Arc::new(d);
                }
                Err(e) => tracing::warn!("onnx backend unavailable ({}), using stub", e),
            }
        }
    }
    if config.backend == "onnx" {
        tracing::warn!("ml backend 'onnx' requested but the `onnx` feature is not compiled in");
    }
    std::sync::Arc::new(stub::StubDetector)
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
