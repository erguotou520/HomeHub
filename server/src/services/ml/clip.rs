//! Chinese-CLIP (RN50) encoders: image + text embeddings for semantic search.
//!
//! * image encoder: 224x224 RGB, CLIP normalisation, `[1,3,224,224]` -> 1024-d
//! * text encoder: BERT WordPiece tokens (52 ctx, `[CLS]..[SEP]` pad 0) -> 1024-d
//!
//! Both outputs are unnormalised in-graph; we L2-normalise so a dot product is
//! the cosine similarity.

use anyhow::{Context, Result};
use image::DynamicImage;
use ort::value::Tensor;
use std::collections::HashMap;
use std::path::Path;
use std::sync::Mutex;

use crate::config::MlClipConfig;

const CTX_LEN: usize = 52;
const EMBED_DIM: usize = 1024;
const CLIP_MEAN: [f32; 3] = [0.48145466, 0.4578275, 0.40821073];
const CLIP_STD: [f32; 3] = [0.26862954, 0.26130258, 0.27577711];

struct Loaded {
    session: ort::session::Session,
    input: String,
}

pub struct ClipEncoder {
    image: Mutex<Loaded>,
    text: Mutex<Loaded>,
    vocab: HashMap<String, u32>,
    unk: u32,
    cls: u32,
    sep: u32,
}

impl ClipEncoder {
    pub fn new(config: &MlClipConfig, threads: usize, data_dir: &Path) -> Result<Self> {
        let image = load(&config.image_model, threads, data_dir)?;
        let text = load(&config.text_model, threads, data_dir)?;
        let vocab = load_vocab(&config.vocab, data_dir)?;
        let get = |k: &str| vocab.get(k).copied().context(format!("vocab missing {k}"));
        Ok(Self {
            image: Mutex::new(image),
            text: Mutex::new(text),
            unk: get("[UNK]")?,
            cls: get("[CLS]")?,
            sep: get("[SEP]")?,
            vocab,
        })
    }

    /// L2-normalised image embedding.
    pub fn embed_image(&self, image: &DynamicImage) -> Result<Vec<f32>> {
        const S: u32 = 224;
        let rgb = DynamicImage::ImageRgba8(image.to_rgba8())
            .resize_exact(S, S, image::imageops::FilterType::Triangle)
            .to_rgb8();
        // NCHW, channel-normalised.
        let plane = (S * S) as usize;
        let mut data = vec![0f32; plane * 3];
        for (x, y, px) in rgb.enumerate_pixels() {
            let idx = (y * S + x) as usize;
            for c in 0..3 {
                data[c * plane + idx] = (px[c] as f32 / 255.0 - CLIP_MEAN[c]) / CLIP_STD[c];
            }
        }
        let tensor = f32_tensor(&[1, 3, S as usize, S as usize], &data)?;
        let loaded = &mut *self.image.lock().map_err(|e| anyhow::anyhow!("{e}"))?;
        let out = run_f32(loaded, tensor)?;
        normalize(out)
    }

    /// L2-normalised text embedding (Chinese or mixed).
    pub fn embed_text(&self, text: &str) -> Result<Vec<f32>> {
        let ids = self.tokenize(text);
        let tensor = Tensor::<i64>::from_array(([1usize, CTX_LEN], ids))
            .map_err(|e| anyhow::anyhow!("{e}"))?;
        let loaded = &mut *self.text.lock().map_err(|e| anyhow::anyhow!("{e}"))?;
        let out = run_i64(loaded, tensor)?;
        normalize(out)
    }

    /// `[CLS] tokens [SEP]` padded to 52 with 0, truncating overflow.
    fn tokenize(&self, text: &str) -> Vec<i64> {
        let mut ids: Vec<i64> = vec![self.cls as i64];
        for tok in wordpiece(basic_tokenize(text), &self.vocab, self.unk) {
            ids.push(tok as i64);
            if ids.len() >= CTX_LEN - 1 {
                break;
            }
        }
        ids.push(self.sep as i64);
        ids.resize(CTX_LEN, 0);
        ids
    }
}

// ───────────────────────── tokenizer (BERT WordPiece, cn_clip-compatible) ─────────────────────────

/// BERT basic tokenization: strip control chars, lowercase, split on
/// whitespace, and explode CJK codepoints into single-char tokens.
fn basic_tokenize(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    for word in text.split_whitespace() {
        let mut cur = String::new();
        for ch in word.chars() {
            if is_cjk(ch) {
                if !cur.is_empty() {
                    out.push(cur.to_lowercase());
                    cur.clear();
                }
                out.push(ch.to_string());
            } else if ch.is_alphanumeric() {
                cur.push(ch);
            } else if !cur.is_empty() {
                out.push(cur.to_lowercase());
                cur.clear();
            }
        }
        if !cur.is_empty() {
            out.push(cur.to_lowercase());
        }
    }
    out
}

/// CJK Unified Ideographs + extensions A/B + compat (enough for photo queries).
fn is_cjk(ch: char) -> bool {
    matches!(ch as u32,
        0x4E00..=0x9FFF | 0x3400..=0x4DBF | 0x20000..=0x2A6DF | 0xF900..=0xFAFF)
}

/// Greedy longest-match-first WordPiece with the `##` continuation prefix.
fn wordpiece(words: Vec<String>, vocab: &HashMap<String, u32>, unk: u32) -> Vec<u32> {
    let mut out = Vec::new();
    for word in words {
        let chars: Vec<char> = word.chars().collect();
        let mut i = 0usize;
        let mut emitted = false;
        while i < chars.len() {
            // Longest suffix-from-i match, first iteration without `##`.
            let mut found: Option<(u32, usize)> = None;
            let max = chars.len();
            let mut end = max;
            while end > i {
                let mut cand: String = chars[i..end].iter().collect();
                if i > 0 {
                    cand = format!("##{}", cand);
                }
                if let Some(&id) = vocab.get(&cand) {
                    found = Some((id, end));
                    break;
                }
                end -= 1;
            }
            match found {
                Some((id, next)) => {
                    out.push(id);
                    i = next;
                    emitted = true;
                }
                None => {
                    if !emitted {
                        out.push(unk);
                    }
                    break;
                }
            }
        }
    }
    out
}

// ───────────────────────── runtime helpers (API aligned with onnx.rs) ─────────────────────────

fn load(model_path: &str, threads: usize, data_dir: &Path) -> Result<Loaded> {
    let resolved = crate::services::ml::resolve_model_path(model_path, data_dir);
    if !resolved.exists() {
        anyhow::bail!("clip model not found: {}", resolved.display());
    }
    let mut builder = ort::session::Session::builder().map_err(|e| anyhow::anyhow!("{e}"))?;
    builder = builder
        .with_intra_threads(threads)
        .map_err(|e| anyhow::anyhow!("{e}"))?;
    let session = builder
        .commit_from_file(&resolved)
        .with_context(|| resolved.display().to_string())?;
    let input = session
        .inputs()
        .first()
        .map(|o| o.name().to_string())
        .context("clip model has no input")?
        .clone();
    Ok(Loaded { session, input })
}

fn load_vocab(path: &str, data_dir: &Path) -> Result<HashMap<String, u32>> {
    let resolved = crate::services::ml::resolve_model_path(path, data_dir);
    let content = std::fs::read_to_string(&resolved)
        .with_context(|| format!("clip vocab {}", resolved.display()))?;
    let mut vocab = HashMap::new();
    for (idx, line) in content.lines().enumerate() {
        let tok = line.trim_end_matches('\r');
        if !tok.is_empty() {
            vocab.insert(tok.to_string(), idx as u32);
        }
    }
    Ok(vocab)
}

fn f32_tensor(shape: &[usize; 4], data: &[f32]) -> Result<Tensor<f32>> {
    Tensor::from_array((shape.to_owned(), data.to_vec())).map_err(|e| anyhow::anyhow!("{e}"))
}

fn run_f32(loaded: &mut Loaded, tensor: Tensor<f32>) -> Result<Vec<f32>> {
    let outputs = loaded
        .session
        .run(ort::inputs![loaded.input.as_str() => tensor])
        .map_err(|e| anyhow::anyhow!("{e}"))?;
    let value = outputs
        .get(loaded.input.as_str())
        .or_else(|| (outputs.len() > 0).then(|| &outputs[0]))
        .context("clip produced no output")?;
    let (_, data) = value.try_extract_tensor::<f32>()?;
    Ok(data.to_vec())
}

fn run_i64(loaded: &mut Loaded, tensor: Tensor<i64>) -> Result<Vec<f32>> {
    let outputs = loaded
        .session
        .run(ort::inputs![loaded.input.as_str() => tensor])
        .map_err(|e| anyhow::anyhow!("{e}"))?;
    let value = outputs
        .get(loaded.input.as_str())
        .or_else(|| (outputs.len() > 0).then(|| &outputs[0]))
        .context("clip produced no output")?;
    let (_, data) = value.try_extract_tensor::<f32>()?;
    Ok(data.to_vec())
}

fn normalize(mut v: Vec<f32>) -> Result<Vec<f32>> {
    if v.len() != EMBED_DIM {
        anyhow::bail!("clip embedding dim {} != {}", v.len(), EMBED_DIM);
    }
    let norm = v.iter().map(|x| x * x).sum::<f32>().sqrt();
    if norm <= 0.0 {
        anyhow::bail!("clip embedding has zero norm");
    }
    for x in &mut v {
        *x /= norm;
    }
    Ok(v)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn basic_tokenize_splits_cjk_and_latin() {
        let toks = basic_tokenize("海边 Play 水上活动");
        assert_eq!(toks, vec!["海", "边", "play", "水", "上", "活", "动"]);
    }

    #[test]
    fn wordpiece_greedy_longest_match() {
        let mut vocab = HashMap::new();
        vocab.insert("海".to_string(), 10);
        vocab.insert("##边".to_string(), 11);
        let ids = wordpiece(vec!["海边".into()], &vocab, 1);
        assert_eq!(ids, vec![10, 11]);
    }

    #[test]
    fn wordpiece_unknown_falls_back_to_unk() {
        let mut vocab = HashMap::new();
        vocab.insert("海".to_string(), 10);
        let ids = wordpiece(vec!["海zz".into()], &vocab, 99);
        assert_eq!(ids, vec![10, 99]);
    }
}
