//! Shared image decoding with a magic-byte fallback.
//!
//! Some phone-exported files carry a wrong extension (JPEG bytes in a `.png`),
//! which makes extension-driven `image::open` fail with e.g. "Invalid PNG
//! signature" even though the pixels are perfectly good. `open_image` retries
//! by sniffing the real format from the file's magic bytes.

use std::fs::File;
use std::io::Read;
use std::path::Path;

use anyhow::{Context, Result};
use image::io::Reader as ImageReader;
use image::{DynamicImage, ImageFormat};

/// Sniff the real format from the leading magic bytes.
pub fn sniff_magic(buf: &[u8]) -> Option<ImageFormat> {
    if buf.starts_with(&[0xFF, 0xD8, 0xFF]) {
        Some(ImageFormat::Jpeg)
    } else if buf.starts_with(&[0x89, b'P', b'N', b'G']) {
        Some(ImageFormat::Png)
    } else if buf.len() >= 12 && &buf[0..4] == b"RIFF" && &buf[8..12] == b"WEBP" {
        Some(ImageFormat::WebP)
    } else {
        None
    }
}

/// Decode an image, falling back to magic-byte detection when the extension
/// does not match the actual content. Genuinely corrupt files still fail, with
/// an error that says so explicitly.
pub fn open_image(path: &Path) -> Result<DynamicImage> {
    match image::open(path) {
        Ok(img) => Ok(img),
        Err(primary) => {
            let mut file = File::open(path)
                .with_context(|| format!("open {}", path.display()))?;
            let mut buf = Vec::new();
            file.read_to_end(&mut buf)
                .with_context(|| format!("read {}", path.display()))?;
            let format = sniff_magic(&buf).with_context(|| {
                format!(
                    "{} — and no known magic bytes, so the file is genuinely \
                     corrupt, not just a wrong extension",
                    primary
                )
            })?;
            let img = ImageReader::with_format(std::io::Cursor::new(&buf), format)
                .decode()
                .with_context(|| format!("magic fallback ({:?}) failed", format))?;
            tracing::warn!(
                "decoded {} via magic-byte fallback (extension {:?} lies, real format {:?})",
                path.display(),
                path.extension().and_then(|e| e.to_str()),
                format
            );
            Ok(img)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_jpeg(path: &Path) {
        let mut img = image::RgbImage::new(32, 32);
        for (x, y, px) in img.enumerate_pixels_mut() {
            *px = image::Rgb([x as u8, y as u8, 128]);
        }
        // save_with_format: `.save()` would honour the (wrong) extension.
        image::DynamicImage::ImageRgb8(img)
            .save_with_format(path, image::ImageFormat::Jpeg)
            .unwrap();
    }

    #[test]
    fn sniff_magic_detects_jpeg_png_webp() {
        assert_eq!(sniff_magic(&[0xFF, 0xD8, 0xFF, 0xE0]), Some(ImageFormat::Jpeg));
        assert_eq!(sniff_magic(&[0x89, b'P', b'N', b'G']), Some(ImageFormat::Png));
        assert_eq!(
            sniff_magic(b"RIFF\x04\x00\x00\x00WEBP"),
            Some(ImageFormat::WebP)
        );
        assert_eq!(sniff_magic(b"whatever"), None);
    }

    #[test]
    fn wrong_extension_jpeg_in_png_decodes_via_fallback() {
        let dir = std::env::temp_dir().join("homehub-decode-test");
        std::fs::create_dir_all(&dir).unwrap();
        let fake = dir.join("realjpegbutfake.png");
        make_jpeg(&fake);

        // image::open fails on the .png extension; open_image must recover.
        assert!(image::open(&fake).is_err());
        let img = open_image(&fake).expect("magic fallback should decode it");
        assert_eq!((img.width(), img.height()), (32, 32));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn genuinely_corrupt_file_still_fails() {
        let dir = std::env::temp_dir().join("homehub-decode-test2");
        std::fs::create_dir_all(&dir).unwrap();
        let bad = dir.join("garbage.jpg");
        std::fs::write(&bad, b"this is not an image at all").unwrap();
        assert!(open_image(&bad).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
