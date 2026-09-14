//! ONNX Runtime backend (optional, `--features onnx`).
//!
//! Expects YOLOv8-style exported models:
//!
//! * object detection – YOLOv8n, output `[1, 4+nc, N]`, COCO labels
//! * face detection   – YOLOv8-face / yoloface, same output layout
//! * scene model      – a plain ImageNet classifier, output `[1, num_labels]`
//!
//! NOTE: this module is only compiled with the `onnx` feature. The default
//! build uses the stub backend and never downloads ONNX Runtime.

use std::path::Path;
use std::sync::Mutex;

use anyhow::{Context, Result};
use image::{DynamicImage, GenericImageView};
use ort::session::Session;
use ort::value::Tensor;

use crate::config::MlConfig;
use crate::services::hash::image_hash;
use crate::services::ml::{resolve_model_path, Detection, Detector, FaceBox};

const INPUT_SIZE: u32 = 640;
/// Classifier input side (ImageNet models are 224×224).
const SCENE_SIZE: u32 = 224;
/// `resize(crop_pct)` / `center_crop_crop` from timm's `MobileNetV3` recipe:
/// 224 / 0.875 = 256.
const SCENE_RESIZE: u32 = 256;
/// Standard YOLO letterbox padding colour (grey 114).
const PAD_VALUE: f32 = 114.0 / 255.0;
/// ImageNet normalisation — scene classifiers are trained on normalised input,
/// so feeding raw 0..1 pixels measurably degrades the predictions.
const IMAGENET_MEAN: [f32; 3] = [0.485, 0.456, 0.406];
const IMAGENET_STD: [f32; 3] = [0.229, 0.224, 0.225];

/// A session plus the tensor names we must use to talk to it. Models exported
/// by different toolchains name their inputs differently (`images`, `input`,
/// `input.1`, `x`, …), so we read the names off the model instead of guessing.
struct Loaded {
    session: Session,
    input: String,
    output: String,
}

pub struct OnnxDetector {
    object: Option<Mutex<Loaded>>,
    face: Option<Mutex<Loaded>>,
    scene: Option<Mutex<Loaded>>,
    recognizer: Option<Mutex<Loaded>>,
    labels: Vec<String>,
    scene_labels: Vec<String>,
    /// `runtime.ml.face.threshold` — was previously read from config but never
    /// applied, so every stray low-confidence box became a "person".
    face_threshold: f32,
}

impl OnnxDetector {
    pub fn new(config: &MlConfig, data_dir: &Path) -> Result<Self> {
        let labels =
            load_labels(&config.object.labels, data_dir).unwrap_or_else(coco_labels);
        let object = load_session(
            &config.object.model,
            config.object.enabled,
            config.onnx_threads,
            data_dir,
        )?;
        let face = load_session(
            &config.face.model,
            config.face.enabled,
            config.onnx_threads,
            data_dir,
        )?;
        let scene = load_session(
            &config.scene.model,
            config.scene.enabled,
            config.onnx_threads,
            data_dir,
        )?;
        // Recognizer (SFace-style 112x112 -> embedding) is optional; missing
        // model just means clustering falls back to phash.
        let recognizer = if config.face.recognizer.is_empty() {
            None
        } else {
            match load_session(&config.face.recognizer, true, config.onnx_threads, data_dir) {
                Ok(s) => s,
                Err(e) => {
                    tracing::warn!("face recognizer unavailable ({}); clustering falls back to phash", e);
                    None
                }
            }
        };
        let mut scene_labels = load_labels(&config.scene.labels, data_dir).unwrap_or_default();
        if scene.is_some() && scene_labels.is_empty() {
            tracing::warn!(
                "scene model loaded but `runtime.ml.scene.labels` is empty or unreadable — \
                 scene tags will be numbered (`scene_0`, `scene_1`, …). Point it at the \
                 label list matching the model's output order."
            );
        }
        // A short list usually means the file and the model disagree; numbering
        // is more honest than silently mislabelling every prediction.
        if let Some(labels_len) = probe_output_classes(&scene) {
            if labels_len > 0 && scene_labels.len() != labels_len {
                tracing::warn!(
                    "scene labels file has {} entries but the model outputs {} classes; \
                     ignoring the file",
                    scene_labels.len(),
                    labels_len
                );
                scene_labels.clear();
            }
        }

        Ok(Self {
            object: object.map(Mutex::new),
            face: face.map(Mutex::new),
            scene: scene.map(Mutex::new),
            recognizer: recognizer.map(Mutex::new),
            labels,
            scene_labels,
            face_threshold: config.face.threshold,
        })
    }
}

/// Number of classes the scene model outputs, if it is a `[1, N]` classifier.
fn probe_output_classes(scene: &Option<Loaded>) -> Option<usize> {
    let l = scene.as_ref()?;
    if l.session.outputs().len() != 1 {
        return None;
    }
    let dims = match l.session.outputs()[0].dtype() {
        ort::value::ValueType::Tensor { shape, .. } => shape.to_vec(),
        _ => return None,
    };
    if dims.len() != 2 {
        return None;
    }
    let n = dims[1];
    (n > 0).then_some(n as usize)
}

fn load_session(
    path: &str,
    enabled: bool,
    threads: usize,
    data_dir: &Path,
) -> Result<Option<Loaded>> {
    if !enabled || path.is_empty() {
        return Ok(None);
    }
    let resolved = resolve_model_path(path, data_dir);
    if !resolved.exists() {
        anyhow::bail!(
            "model not found: {} — place the ONNX file there (relative paths resolve \
             against `global.data-dir`, currently {}), or set the model path / disable \
             the detector in config.yaml",
            resolved.display(),
            data_dir.display()
        );
    }
    // `Session::builder()` and friends return `ort::Error` with a recover
    // payload that is not `Send`/`Sync`, so we cannot use `?` directly into
    // `anyhow::Result`. Convert the error to a string first.
    let mut builder = ort::session::Session::builder().map_err(|e| anyhow::anyhow!("{e}"))?;
    builder = builder
        .with_intra_threads(threads.max(1))
        .map_err(|e| anyhow::anyhow!("{e}"))?;
    let session = builder
        .commit_from_file(&resolved)
        .map_err(|e| anyhow::anyhow!("{}: {e}", resolved.display()))?;

    let input = session
        .inputs()
        .first()
        .map(|o| o.name().to_string())
        .with_context(|| format!("{} has no input tensor", resolved.display()))?;
    let output = session
        .outputs()
        .first()
        .map(|o| o.name().to_string())
        .with_context(|| format!("{} has no output tensor", resolved.display()))?;
    // Log the tensor signatures: a model whose output layout differs from what
    // the decoder expects (e.g. `[1, N, 6]` with built-in NMS instead of
    // `[1, 4+nc, N]`) fails *silently*, producing zero detections.
    tracing::debug!(
        "loaded {} (input '{}' {:?}, output '{}' {:?})",
        resolved.display(),
        input,
        session.inputs()[0].dtype(),
        output,
        session.outputs()[0].dtype()
    );

    Ok(Some(Loaded {
        session,
        input,
        output,
    }))
}

fn load_labels(path: &str, data_dir: &Path) -> Option<Vec<String>> {
    if path.is_empty() {
        return None;
    }
    let resolved = resolve_model_path(path, data_dir);
    let content = std::fs::read_to_string(&resolved).ok()?;
    let labels: Vec<String> = content
        .lines()
        .map(|l| l.trim().to_string())
        .filter(|l| !l.is_empty())
        .collect();
    (!labels.is_empty()).then_some(labels)
}

/// Run a session and copy the first output out as `(shape, data)`.
fn run(loaded: &mut Loaded, tensor: Tensor<f32>) -> Result<(Vec<i64>, Vec<f32>)> {
    let outputs = loaded
        .session
        .run(ort::inputs![loaded.input.as_str() => tensor])
        .map_err(|e| anyhow::anyhow!("{e}"))?;
    let value = outputs
        .get(loaded.output.as_str())
        .or_else(|| (outputs.len() > 0).then(|| &outputs[0]))
        .context("model produced no output")?;
    let (shape, data) = value.try_extract_tensor::<f32>()?;
    Ok((shape.iter().copied().collect(), data.to_vec()))
}

/// Letterbox geometry: how a source image was scaled and padded into the
/// square model input.
#[derive(Debug, Clone, Copy)]
struct Letterbox {
    scale: f32,
    pad_x: f32,
    pad_y: f32,
    nw: u32,
    nh: u32,
}

/// Fit `w × h` inside a `size × size` square, preserving aspect ratio, and
/// centre it. The shortened axis is the one that gets padded — a 320×640
/// portrait image scales to 320×640 and gains left/right padding, a landscape
/// one gains top/bottom padding.
fn letterbox_for(w: u32, h: u32, size: u32) -> Letterbox {
    let scale = (size as f32 / w as f32).min(size as f32 / h as f32);
    let nw = ((w as f32 * scale).round() as u32).clamp(1, size);
    let nh = ((h as f32 * scale).round() as u32).clamp(1, size);
    Letterbox {
        scale,
        pad_x: (size - nw) as f32 / 2.0,
        pad_y: (size - nh) as f32 / 2.0,
        nw,
        nh,
    }
}

/// Letterbox a source image into a `size × size` NCHW float tensor, preserving
/// aspect ratio. Stretching (plain `resize_exact`) distorts every object the
/// detector was trained to recognise, which shows up as missed or garbled
/// labels on non-square photos.
fn preprocess_detector(image: &DynamicImage, size: u32) -> Result<(Tensor<f32>, Letterbox)> {
    let (w, h) = image.dimensions();
    if w == 0 || h == 0 {
        anyhow::bail!("image has zero extent");
    }
    let lb = letterbox_for(w, h, size);

    let resized = image
        .resize_exact(lb.nw, lb.nh, image::imageops::FilterType::Triangle)
        .to_rgb8();
    let plane = (size * size) as usize;
    let mut data = vec![PAD_VALUE; plane * 3];
    for (x, y, px) in resized.enumerate_pixels() {
        let dx = x + lb.pad_x as u32;
        let dy = y + lb.pad_y as u32;
        if dx >= size || dy >= size {
            continue;
        }
        let idx = (dy * size + dx) as usize;
        for c in 0..3 {
            data[c * plane + idx] = px[c] as f32 / 255.0;
        }
    }

    let tensor = Tensor::from_array(([1usize, 3, size as usize, size as usize], data))
        .map_err(|e| anyhow::anyhow!("{e}"))?;
    Ok((tensor, lb))
}

impl Detector for OnnxDetector {
    fn name(&self) -> &'static str {
        "onnx"
    }

    fn detect_objects(&self, image: &DynamicImage) -> Result<Vec<Detection>> {
        let Some(session) = &self.object else {
            return Ok(Vec::new());
        };
        let (tensor, lb) = preprocess_detector(image, INPUT_SIZE)?;
        let (shape, data) = run(&mut *session.lock().map_err(|e| anyhow::anyhow!("{e}"))?, tensor)?;
        Ok(yolo_raw(&data, &shape, &self.labels, lb, image)
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
        let tensor = preprocess_classifier(image, SCENE_RESIZE, SCENE_SIZE)?;
        let (shape, data) = run(&mut *session.lock().map_err(|e| anyhow::anyhow!("{e}"))?, tensor)?;
        if shape.len() != 2 || shape[1] <= 0 {
            return Ok(Vec::new());
        }
        let scores = softmax(&data);

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
        let (tensor, lb) = preprocess_detector(image, INPUT_SIZE)?;
        let (shape, data) = run(&mut *session.lock().map_err(|e| anyhow::anyhow!("{e}"))?, tensor)?;
        let raw = yolo_raw(&data, &shape, &["face".to_string()], lb, image);

        let mut faces = Vec::new();
        for r in raw {
            if r.confidence < self.face_threshold {
                continue;
            }
            let crop = crop_normalised(image, &r);
            let embedding = self
                .recognizer
                .as_ref()
                .and_then(|sess| embed_crop(image, &r.box_x, &r.box_y, &r.box_w, &r.box_h, sess).ok())
                .flatten();
            faces.push(FaceBox {
                x: r.box_x,
                y: r.box_y,
                w: r.box_w,
                h: r.box_h,
                hash: image_hash(&crop, 16),
                embedding,
            });
        }
        Ok(faces)
    }
}

/// Square-margin crop around a normalised face box, resized to 112x112 raw
/// BGR 0-255 (SFace normalises in-graph) and run through the recognizer.
fn embed_crop(
    image: &DynamicImage,
    nx: &f32,
    ny: &f32,
    nw: &f32,
    nh: &f32,
    session: &Mutex<Loaded>,
) -> Result<Option<Vec<f32>>> {
    const EMBED_SIZE: u32 = 112;
    const MARGIN: f32 = 0.2;
    let (iw, ih) = image.dimensions();
    if iw == 0 || ih == 0 {
        return Ok(None);
    }
    let (iwf, ihf) = (iw as f32, ih as f32);
    let cx = (nx + nw / 2.0) * iwf;
    let cy = (ny + nh / 2.0) * ihf;
    let side = (nw * iwf).max(nh * ihf) * (1.0 + MARGIN);
    if side < 16.0 {
        return Ok(None);
    }
    let l = (cx - side / 2.0).floor().max(0.0) as i64;
    let t = (cy - side / 2.0).floor().max(0.0) as i64;
    let r = ((cx + side / 2.0).ceil() as i64).min(iw as i64);
    let b = ((cy + side / 2.0).ceil() as i64).min(ih as i64);
    let (cw, ch) = ((r - l) as u32, (b - t) as u32);
    if cw < 8 || ch < 8 {
        return Ok(None);
    }
    let crop = DynamicImage::ImageRgba8(image.view(l as u32, t as u32, cw, ch).to_image())
        .resize_exact(EMBED_SIZE, EMBED_SIZE, image::imageops::FilterType::Triangle)
        .to_rgba8();

    // SFace/MobileFaceNet: raw BGR 0-255, NCHW; (x-127.5)/128 is in-graph.
    let mut data = vec![0f32; (EMBED_SIZE * EMBED_SIZE * 3) as usize];
    for (x, y, px) in crop.enumerate_pixels() {
        let idx = (y * EMBED_SIZE + x) as usize;
        data[idx] = px[2] as f32; // B
        data[112 * 112 + idx] = px[1] as f32; // G
        data[2 * 112 * 112 + idx] = px[0] as f32; // R
    }
    let tensor = Tensor::from_array(([1usize, 3, EMBED_SIZE as usize, EMBED_SIZE as usize], data))
        .map_err(|e| anyhow::anyhow!("{e}"))?;
    let (shape, out) = run(&mut *session.lock().map_err(|e| anyhow::anyhow!("{e}"))?, tensor)?;
    if shape.last() != Some(&128) || out.len() != 128 {
        // Not the expected recognizer layout; treat as unavailable.
        return Ok(None);
    }
    Ok(Some(out))
}

struct RawDetection {
    tag: String,
    confidence: f32,
    /// Normalised (0..1) coordinates in the *original* image.
    box_x: f32,
    box_y: f32,
    box_w: f32,
    box_h: f32,
}

/// Resize-shortest-side + centre-crop + ImageNet normalisation, matching
/// torchvision's `Resize(256)` → `CenterCrop(224)` inference transform.
///
/// Note the resize target: `Resize(256)` scales so the *shorter* side is 256
/// and the longer side stays ≥ 256, which is what makes the following 224×224
/// centre crop possible. `DynamicImage::resize(256, 256)` instead fits the
/// whole image *inside* 256×256, leaving a wide photo 256×144 — cropping that
/// to 224×224 is impossible and the old code silently fed the classifier a
/// mostly-zero tensor.
fn preprocess_classifier(image: &DynamicImage, resize_to: u32, crop: u32) -> Result<Tensor<f32>> {
    let (w, h) = image.dimensions();
    if w == 0 || h == 0 {
        anyhow::bail!("image has zero extent");
    }
    let (w, h) = (w as f32, h as f32);
    let scale = resize_to as f32 / w.min(h);
    let nw = ((w * scale).round() as u32).max(crop);
    let nh = ((h * scale).round() as u32).max(crop);

    let resized = image.resize_exact(nw, nh, image::imageops::FilterType::Triangle);
    let x0 = (nw - crop) / 2;
    let y0 = (nh - crop) / 2;
    let cropped = resized.crop_imm(x0, y0, crop, crop).to_rgb8();

    let plane = (crop * crop) as usize;
    let mut data = vec![0f32; plane * 3];
    for (x, y, px) in cropped.enumerate_pixels() {
        let idx = (y * crop + x) as usize;
        for c in 0..3 {
            data[c * plane + idx] = (px[c] as f32 / 255.0 - IMAGENET_MEAN[c]) / IMAGENET_STD[c];
        }
    }
    Tensor::from_array(([1usize, 3, crop as usize, crop as usize], data))
        .map_err(|e| anyhow::anyhow!("{e}"))
}

/// Numerically stable softmax — classifier models emit raw logits.
fn softmax(logits: &[f32]) -> Vec<f32> {
    if logits.is_empty() {
        return Vec::new();
    }
    let max = logits.iter().copied().fold(f32::NEG_INFINITY, f32::max);
    let exps: Vec<f32> = logits.iter().map(|v| (v - max).exp()).collect();
    let sum: f32 = exps.iter().sum();
    if !sum.is_finite() || sum <= 0.0 {
        return vec![0.0; logits.len()];
    }
    exps.into_iter().map(|e| e / sum).collect()
}

/// Decode a YOLOv8 `[1, 4+nc, N]` output into detections (with NMS), mapping
/// boxes from letterbox space back to normalised original-image coordinates.
fn yolo_raw(
    data: &[f32],
    shape: &[i64],
    labels: &[String],
    lb: Letterbox,
    image: &DynamicImage,
) -> Vec<RawDetection> {
    let mut raw: Vec<RawDetection> = Vec::new();
    if shape.len() != 3 {
        return raw;
    }
    let rows = shape[2] as usize;
    let cols = shape[1] as usize;
    // `cols` is `4 + num_classes`, so a single-class model (face detectors) has
    // exactly 5 — an earlier `cols < 6` guard threw every one of those away and
    // face detection silently returned nothing regardless of the image.
    if rows == 0 || cols < 5 {
        return raw;
    }
    let num_classes = cols - 4;

    let (iw, ih) = image.dimensions();
    if iw == 0 || ih == 0 || lb.scale <= 0.0 {
        return raw;
    }
    let (iw, ih) = (iw as f32, ih as f32);
    let to_norm = |v: f32, pad: f32, extent: f32| ((v - pad) / lb.scale / extent).clamp(0.0, 1.0);

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
        let x = to_norm(cx - w / 2.0, lb.pad_x, iw);
        let y = to_norm(cy - h / 2.0, lb.pad_y, ih);
        let bw = (w / lb.scale / iw).clamp(0.0, 1.0);
        let bh = (h / lb.scale / ih).clamp(0.0, 1.0);
        if bw <= 0.0 || bh <= 0.0 {
            continue;
        }
        raw.push(RawDetection {
            tag: labels
                .get(best.0)
                .cloned()
                .unwrap_or_else(|| format!("class_{}", best.0)),
            confidence: best.1,
            box_x: x,
            box_y: y,
            box_w: bw,
            box_h: bh,
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn softmax_is_a_distribution() {
        let p = softmax(&[1.0, 2.0, 3.0]);
        assert!((p.iter().sum::<f32>() - 1.0).abs() < 1e-5);
        assert!(p[2] > p[1] && p[1] > p[0]);
    }

    #[test]
    fn softmax_handles_large_logits_without_overflow() {
        let p = softmax(&[1000.0, 1000.0]);
        assert!(p.iter().all(|v| v.is_finite()));
        assert!((p[0] - 0.5).abs() < 1e-5);
    }

    /// A 320×640 portrait must be padded on the *left/right*, while a 640×320
    /// landscape is padded top/bottom. Getting this backwards used to be the
    /// failure mode of the coordinate mapping.
    #[test]
    fn letterbox_pads_the_short_axis() {
        let portrait = letterbox_for(320, 640, 640);
        assert_eq!((portrait.nw, portrait.nh), (320, 640));
        assert!((portrait.pad_x - 160.0).abs() < 1e-4);
        assert!(portrait.pad_y.abs() < 1e-4);

        let landscape = letterbox_for(640, 320, 640);
        assert_eq!((landscape.nw, landscape.nh), (640, 320));
        assert!(landscape.pad_x.abs() < 1e-4);
        assert!((landscape.pad_y - 160.0).abs() < 1e-4);

        let square = letterbox_for(400, 400, 640);
        assert!((square.scale - 1.6).abs() < 1e-4);
        assert!(square.pad_x.abs() < 1e-4 && square.pad_y.abs() < 1e-4);
    }

    /// Boxes must come back in original-image coordinates, not letterbox
    /// coordinates — otherwise a face in a 4:3 photo is cropped from the wrong
    /// place and the perceptual hash (and therefore person grouping) is junk.
    #[test]
    fn letterbox_maps_box_back_to_original_extent() {
        let (iw, ih) = (320u32, 640u32);
        let img = DynamicImage::new_rgb8(iw, ih);
        let lb = letterbox_for(iw, ih, 640);

        // A face at pixels x=40, y=220, 80×100 in the original image.
        let (fx, fy, fw, fh) = (40.0f32, 220.0f32, 80.0f32, 100.0f32);
        // …sits here in letterbox space:
        let cx = fx * lb.scale + lb.pad_x + fw * lb.scale / 2.0;
        let cy = fy * lb.scale + lb.pad_y + fh * lb.scale / 2.0;

        let rows = 1usize;
        let cols = 6usize; // 4 box params + 2 classes
        let mut data = vec![0f32; rows * cols];
        data[0] = cx;
        data[rows] = cy;
        data[2 * rows] = fw * lb.scale;
        data[3 * rows] = fh * lb.scale;
        data[5 * rows] = 0.9; // class 1 wins

        let out = yolo_raw(
            &data,
            &[1, cols as i64, rows as i64],
            &["a".into(), "b".into()],
            lb,
            &img,
        );
        assert_eq!(out.len(), 1);
        let d = &out[0];
        assert_eq!(d.tag, "b");
        assert!((d.box_x - fx / iw as f32).abs() < 1e-4, "x = {}", d.box_x);
        assert!((d.box_y - fy / ih as f32).abs() < 1e-4, "y = {}", d.box_y);
        assert!((d.box_w - fw / iw as f32).abs() < 1e-4, "w = {}", d.box_w);
        assert!((d.box_h - fh / ih as f32).abs() < 1e-4, "h = {}", d.box_h);
    }

    #[test]
    fn nms_keeps_highest_confidence_overlap() {
        let mut dets = vec![
            RawDetection { tag: "person".into(), confidence: 0.9, box_x: 0.0, box_y: 0.0, box_w: 0.5, box_h: 0.5 },
            RawDetection { tag: "person".into(), confidence: 0.5, box_x: 0.01, box_y: 0.01, box_w: 0.5, box_h: 0.5 },
            RawDetection { tag: "dog".into(), confidence: 0.4, box_x: 0.01, box_y: 0.01, box_w: 0.5, box_h: 0.5 },
        ];
        nms(&mut dets, 0.45);
        assert_eq!(dets.len(), 2);
        assert!(dets.iter().any(|d| d.tag == "person" && d.confidence == 0.9));
        assert!(dets.iter().any(|d| d.tag == "dog"));
    }
}
