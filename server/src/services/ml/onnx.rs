//! ONNX Runtime backend (optional, `--features onnx`).
//!
//! Expects YOLOv8-style exported models:
//!
//! * object detection – YOLOv8n, output `[1, 4+nc, N]`, COCO labels
//! * face detection   – YOLOv8-face / yoloface, same output layout
//! * scene model      – a plain classifier, output `[1, num_labels]`
//!
//! NOTE: this module is only compiled with the `onnx` feature. The default
//! build uses the stub backend and never downloads ONNX Runtime.

use std::path::Path;
use std::sync::Mutex;

use anyhow::{Context, Result};
use image::{DynamicImage, GenericImageView};

use crate::config::MlConfig;
use crate::services::hash::image_hash;
use crate::services::ml::{Detection, Detector, FaceBox};

const INPUT_SIZE: u32 = 640;

pub struct OnnxDetector {
    object: Option<Mutex<ort::session::Session>>,
    face: Option<Mutex<ort::session::Session>>,
    scene: Option<Mutex<ort::session::Session>>,
    labels: Vec<String>,
    scene_labels: Vec<String>,
}

impl OnnxDetector {
    pub fn new(config: &MlConfig) -> Result<Self> {
        let labels = load_labels(&config.object.labels).unwrap_or_else(coco_labels);
        let object = load_session(&config.object.model, config.object.enabled, config.onnx_threads)?;
        let face = load_session(&config.face.model, config.face.enabled, config.onnx_threads)?;
        let scene = load_session(&config.scene.model, config.scene.enabled, config.onnx_threads)?;
        let scene_labels = load_labels(&config.scene.labels).unwrap_or_default();

        Ok(Self {
            object: object.map(Mutex::new),
            face: face.map(Mutex::new),
            scene: scene.map(Mutex::new),
            labels,
            scene_labels,
        })
    }
}

fn load_session(path: &str, enabled: bool, threads: usize) -> Result<Option<ort::session::Session>> {
    if !enabled || path.is_empty() {
        return Ok(None);
    }
    let resolved = resolve_model_path(path);
    if !resolved.exists() {
        anyhow::bail!(
            "model not found: {} — place the ONNX file there (relative paths resolve \
             against DATA_DIR), or set the model path / disable the detector in config.yaml",
            resolved.display()
        );
    }
    // `Session::builder()` and friends return `ort::Error` with a recover
    // payload that is not `Send`/`Sync`, so we cannot use `?` directly into
    // `anyhow::Result`. Convert the error to a string first.
    let mut builder = ort::session::Session::builder()
        .map_err(|e| anyhow::anyhow!("{e}"))?;
    builder = builder
        .with_intra_threads(threads.max(1))
        .map_err(|e| anyhow::anyhow!("{e}"))?;
    let session = builder
        .commit_from_file(&resolved)
        .map_err(|e| anyhow::anyhow!("{e}"))?;
    Ok(Some(session))
}

/// Resolve a configured model path: absolute paths are used as-is, relative
/// paths are resolved against `DATA_DIR` (default `./data`).
fn resolve_model_path(path: &str) -> std::path::PathBuf {
    let p = std::path::Path::new(path);
    if p.is_absolute() {
        p.to_path_buf()
    } else {
        let base = std::env::var("DATA_DIR").unwrap_or_else(|_| "data".to_string());
        std::path::Path::new(&base).join(p)
    }
}

fn load_labels(path: &str) -> Option<Vec<String>> {
    if path.is_empty() {
        return None;
    }
    let content = std::fs::read_to_string(path).ok()?;
    Some(
        content
            .lines()
            .map(|l| l.trim().to_string())
            .filter(|l| !l.is_empty())
            .collect(),
    )
}

impl Detector for OnnxDetector {
    fn name(&self) -> &'static str {
        "onnx"
    }

    fn detect_objects(&self, image: &DynamicImage) -> Result<Vec<Detection>> {
        let Some(session) = &self.object else {
            return Ok(Vec::new());
        };
        let tensor = preprocess(image, INPUT_SIZE)?;
        let mut session = session.lock().map_err(|e| anyhow::anyhow!("{e}"))?;
        let outputs = session.run(ort::inputs!["images" => tensor])?;
        let tensor = tensor_ref(&outputs)?;
        Ok(yolo_raw(tensor.1, tensor.0.as_slice(), &self.labels)
            .into_iter()
            .map(|r| Detection {
                tag: r.tag,
                confidence: r.confidence,
            })
            .collect())
    }

    fn detect_scenes(&self, image: &DynamicImage) -> Result<Vec<Detection>> {
        let Some(session) = &self.scene else {
            return Ok(Vec::new());
        };
        let tensor = preprocess(image, 224)?;
        let mut session = session.lock().map_err(|e| anyhow::anyhow!("{e}"))?;
        let outputs = session.run(ort::inputs!["images" => tensor])?;
        let (_, scores) = tensor_ref(&outputs)?;
        let mut out: Vec<Detection> = scores
            .iter()
            .enumerate()
            .map(|(i, score)| Detection {
                tag: self
                    .scene_labels
                    .get(i)
                    .cloned()
                    .unwrap_or_else(|| format!("scene_{}", i)),
                confidence: *score,
            })
            .collect();
        out.sort_by(|a, b| b.confidence.partial_cmp(&a.confidence).unwrap());
        out.truncate(5);
        Ok(out)
    }

    fn detect_faces(&self, image: &DynamicImage) -> Result<Vec<FaceBox>> {
        let Some(session) = &self.face else {
            return Ok(Vec::new());
        };
        let tensor = preprocess(image, INPUT_SIZE)?;
        let mut session = session.lock().map_err(|e| anyhow::anyhow!("{e}"))?;
        let outputs = session.run(ort::inputs!["images" => tensor])?;
        let (shape, data) = tensor_ref(&outputs)?;
        let raw = yolo_raw(data, shape.as_slice(), &["face".to_string()]);

        let mut faces = Vec::new();
        for r in raw {
            let crop = crop_normalised(image, &r);
            faces.push(FaceBox {
                x: r.box_x,
                y: r.box_y,
                w: r.box_w,
                h: r.box_h,
                hash: image_hash(&crop, 16),
            });
        }
        Ok(faces)
    }
}

/// Borrow the first output tensor as `(shape, data)`.
fn tensor_ref<'a>(
    outputs: &'a ort::session::SessionOutputs<'_>,
) -> Result<(Vec<i64>, &'a [f32])> {
    let value = outputs
        .get("output0")
        .or_else(|| (outputs.len() > 0).then(|| &outputs[0]))
        .context("model produced no output")?;
    let (shape, data) = value.try_extract_tensor::<f32>()?;
    Ok((shape.iter().copied().collect(), data))
}

struct RawDetection {
    tag: String,
    confidence: f32,
    box_x: f32,
    box_y: f32,
    box_w: f32,
    box_h: f32,
}

/// Letterbox resize + NCHW float tensor in 0..1.
fn preprocess(image: &DynamicImage, size: u32) -> Result<ort::value::Tensor<f32>> {
    let resized = image.resize_exact(size, size, image::imageops::FilterType::Triangle);
    let rgb = resized.to_rgb8();
    let mut data = Vec::with_capacity((size * size * 3) as usize);
    for c in 0..3 {
        for px in rgb.pixels() {
            data.push(px[c] as f32 / 255.0);
        }
    }
    ort::value::Tensor::from_array(([1usize, 3, size as usize, size as usize], data))
        .map_err(|e| anyhow::anyhow!("{e}"))
}

/// Decode a YOLOv8 `[1, 4+nc, N]` output into detections (with NMS).
fn yolo_raw(data: &[f32], shape: &[i64], labels: &[String]) -> Vec<RawDetection> {
    let mut raw: Vec<RawDetection> = Vec::new();
    if shape.len() != 3 {
        return raw;
    }
    let rows = shape[2] as usize;
    let cols = shape[1] as usize;
    if rows == 0 || cols < 6 {
        return raw;
    }
    let num_classes = cols - 4;

    for i in 0..rows {
        let cx = *data.get(i).unwrap_or(&0.0);
        let cy = *data.get(rows + i).unwrap_or(&0.0);
        let w = *data.get(2 * rows + i).unwrap_or(&0.0);
        let h = *data.get(3 * rows + i).unwrap_or(&0.0);

        let mut best = (0usize, 0f32);
        for c in 0..num_classes {
            let score = *data.get((4 + c) * rows + i).unwrap_or(&0.0);
            if score > best.1 {
                best = (c, score);
            }
        }
        if best.1 <= 0.01 {
            continue;
        }
        raw.push(RawDetection {
            tag: labels
                .get(best.0)
                .cloned()
                .unwrap_or_else(|| format!("class_{}", best.0)),
            confidence: best.1,
            box_x: (cx - w / 2.0) / INPUT_SIZE as f32,
            box_y: (cy - h / 2.0) / INPUT_SIZE as f32,
            box_w: w / INPUT_SIZE as f32,
            box_h: h / INPUT_SIZE as f32,
        });
    }

    nms(&mut raw, 0.45);
    raw
}

fn nms(detections: &mut Vec<RawDetection>, iou_threshold: f32) {
    detections.sort_by(|a, b| b.confidence.partial_cmp(&a.confidence).unwrap());
    let mut keep: Vec<RawDetection> = Vec::new();
    for d in detections.drain(..) {
        let suppressed = keep.iter().any(|k| k.tag == d.tag && iou(k, &d) > iou_threshold);
        if !suppressed {
            keep.push(d);
        }
    }
    *detections = keep;
}

fn iou(a: &RawDetection, b: &RawDetection) -> f32 {
    let x1 = a.box_x.max(b.box_x);
    let y1 = a.box_y.max(b.box_y);
    let x2 = (a.box_x + a.box_w).min(b.box_x + b.box_w);
    let y2 = (a.box_y + a.box_h).min(b.box_y + b.box_h);
    let inter = (x2 - x1).max(0.0) * (y2 - y1).max(0.0);
    let union = a.box_w * a.box_h + b.box_w * b.box_h - inter;
    if union <= 0.0 {
        0.0
    } else {
        inter / union
    }
}

fn crop_normalised(image: &DynamicImage, r: &RawDetection) -> DynamicImage {
    let (iw, ih) = image.dimensions();
    let px = (r.box_x * iw as f32).max(0.0) as u32;
    let py = (r.box_y * ih as f32).max(0.0) as u32;
    let pw = (r.box_w * iw as f32).max(1.0).min((iw - px) as f32) as u32;
    let ph = (r.box_h * ih as f32).max(1.0).min((ih - py) as f32) as u32;
    DynamicImage::ImageRgba8(image.view(px, py, pw.max(1), ph.max(1)).to_image())
}

pub fn coco_labels() -> Vec<String> {
    [
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic_light", "fire_hydrant", "stop_sign", "parking_meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports_ball",
        "kite", "baseball_bat", "baseball_glove", "skateboard", "surfboard", "tennis_racket",
        "bottle", "wine_glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot_dog", "pizza", "donut", "cake", "chair",
        "couch", "potted_plant", "bed", "dining_table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell_phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy_bear", "hair_drier",
        "toothbrush",
    ]
    .iter()
    .map(|s| s.to_string())
    .collect()
}
