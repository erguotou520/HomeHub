//! Default detector used when no model is configured.
//!
//! It performs no inference; it only derives coarse scene hints from the image
//! itself (aspect ratio, brightness, colourfulness) so the pipeline stays
//! exercisable end-to-end without downloading models.

use anyhow::Result;
use image::DynamicImage;

use super::{Detection, Detector, FaceBox};

pub struct StubDetector;

impl Detector for StubDetector {
    fn name(&self) -> &'static str {
        "stub"
    }

    fn detect_objects(&self, _image: &DynamicImage) -> Result<Vec<Detection>> {
        Ok(Vec::new())
    }

    fn detect_scenes(&self, image: &DynamicImage) -> Result<Vec<Detection>> {
        use image::GenericImageView;
        let (w, h) = image.dimensions();
        if w == 0 || h == 0 {
            return Ok(Vec::new());
        }
        let small = image.thumbnail(64, 64).to_rgb8();
        let mut sum = 0u64;
        let mut sat = 0u64;
        let n = (small.width() * small.height()) as u64;
        for px in small.pixels() {
            let (r, g, b) = (px[0] as i32, px[1] as i32, px[2] as i32);
            let max = r.max(g).max(b);
            let min = r.min(g).min(b);
            sum += (r + g + b) as u64 / 3;
            sat += (max - min) as u64;
        }
        let brightness = sum / n.max(1);
        let saturation = sat / n.max(1);

        let mut out = Vec::new();
        let landscape = w as f32 / h as f32;
        if landscape > 1.6 {
            out.push(Detection {
                tag: "panorama".into(),
                confidence: 0.55,
            });
        }
        if brightness < 70 {
            out.push(Detection {
                tag: "night".into(),
                confidence: 0.5,
            });
        } else if brightness > 190 {
            out.push(Detection {
                tag: "bright".into(),
                confidence: 0.5,
            });
        }
        if saturation > 60 {
            out.push(Detection {
                tag: "colorful".into(),
                confidence: 0.45,
            });
        } else if saturation < 12 {
            out.push(Detection {
                tag: "monochrome".into(),
                confidence: 0.45,
            });
        }
        Ok(out)
    }

    fn detect_faces(&self, _image: &DynamicImage) -> Result<Vec<FaceBox>> {
        Ok(Vec::new())
    }
}
