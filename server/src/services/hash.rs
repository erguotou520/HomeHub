//! Hashing helpers: byte-level SHA-256 and perceptual hashes of decoded pixels.

use std::io::Read;
use std::path::Path;

use anyhow::Result;
use image::DynamicImage;
use sha2::{Digest, Sha256};

/// Byte-level SHA-256 of the file content. Used for exact dedup of uploads.
pub fn file_sha256(path: &Path) -> Result<String> {
    let mut file = std::fs::File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 128 * 1024];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hex::encode(hasher.finalize()))
}

/// SHA-256 of an in-memory buffer.
pub fn bytes_sha256(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    hex::encode(hasher.finalize())
}

/// 16x16 difference hash of the decoded (and orientation corrected) pixels.
///
/// Insensitive to lossless re-compression: the decoded bitmap is identical, so
/// the hash is identical. Returns a 64 char hex string (256 bits).
pub fn pixel_hash(path: &Path, orientation: u32) -> Option<String> {
    let img = image::open(path).ok()?;
    let img = apply_orientation(img, orientation);
    let small = img.resize_exact(17, 16, image::imageops::FilterType::Triangle);
    let gray = small.to_luma8();

    let mut bits: Vec<bool> = Vec::with_capacity(256);
    for y in 0..16u32 {
        for x in 0..16u32 {
            let left = gray.get_pixel(x, y)[0] as i32;
            let right = gray.get_pixel(x + 1, y)[0] as i32;
            bits.push(left < right);
        }
    }
    Some(bits_to_hex(&bits))
}

/// Same algorithm but for an already decoded image (face crops, etc.).
#[allow(dead_code)] // used by the optional `onnx` backend
pub fn image_hash(img: &DynamicImage, size: u32) -> String {
    let small = img.resize_exact(size + 1, size, image::imageops::FilterType::Triangle);
    let gray = small.to_luma8();
    let mut bits = Vec::new();
    for y in 0..size {
        for x in 0..size {
            let left = gray.get_pixel(x, y)[0] as i32;
            let right = gray.get_pixel(x + 1, y)[0] as i32;
            bits.push(left < right);
        }
    }
    bits_to_hex(&bits)
}

/// Apply the EXIF orientation transform so the pixels are stored upright.
pub fn apply_orientation(img: DynamicImage, orientation: u32) -> DynamicImage {
    match orientation {
        2 => img.fliph(),
        3 => img.rotate180(),
        4 => img.flipv(),
        5 => img.rotate270().fliph(),
        6 => img.rotate90(),
        7 => img.rotate90().fliph(),
        8 => img.rotate270(),
        _ => img,
    }
}

/// Hamming distance between two hex-encoded hashes of equal length.
pub fn hamming_distance_hex(a: &str, b: &str) -> Option<u32> {
    if a.len() != b.len() {
        return None;
    }
    let mut dist = 0u32;
    for (ca, cb) in a.chars().zip(b.chars()) {
        let va = u8::from_str_radix(&ca.to_string(), 16).ok()?;
        let vb = u8::from_str_radix(&cb.to_string(), 16).ok()?;
        dist += (va ^ vb).count_ones();
    }
    Some(dist)
}

fn bits_to_hex(bits: &[bool]) -> String {
    let mut out = String::with_capacity(bits.len() / 4);
    for chunk in bits.chunks(4) {
        let mut nibble = 0u8;
        for (i, b) in chunk.iter().enumerate() {
            if *b {
                nibble |= 1 << (3 - i);
            }
        }
        out.push_str(&format!("{:x}", nibble));
    }
    out
}


#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hamming_distance_works() {
        assert_eq!(hamming_distance_hex("00", "00"), Some(0));
        assert_eq!(hamming_distance_hex("0f", "00"), Some(4));
        assert_eq!(hamming_distance_hex("ff", "00"), Some(8));
        assert_eq!(hamming_distance_hex("abc", "ab"), None);
    }

    #[test]
    fn sha256_of_bytes() {
        assert_eq!(
            bytes_sha256(b""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
    }

    #[test]
    fn hash_is_stable_for_identical_pixels() {
        let dir = std::env::temp_dir().join("homehub-hash-test");
        std::fs::create_dir_all(&dir).unwrap();
        let a = dir.join("a.png");
        let b = dir.join("b.png");
        let img = image::DynamicImage::new_rgb8(64, 48);
        img.save(&a).unwrap();
        img.save(&b).unwrap();
        let ha = pixel_hash(&a, 1).unwrap();
        let hb = pixel_hash(&b, 1).unwrap();
        assert_eq!(ha, hb);
        assert_eq!(ha.len(), 64);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn different_images_have_different_hashes() {
        let dir = std::env::temp_dir().join("homehub-hash-test2");
        std::fs::create_dir_all(&dir).unwrap();
        let a = dir.join("a.png");
        let b = dir.join("b.png");

        // A: vertical split, B: horizontal split — clearly different structure.
        let mut left = image::RgbImage::new(64, 64);
        for (x, _, px) in left.enumerate_pixels_mut() {
            let v = if x < 32 { 0 } else { 255 };
            *px = image::Rgb([v, v, v]);
        }
        let mut top = image::RgbImage::new(64, 64);
        for (_, y, px) in top.enumerate_pixels_mut() {
            let v = if y < 32 { 0 } else { 255 };
            *px = image::Rgb([v, v, v]);
        }
        image::DynamicImage::ImageRgb8(left).save(&a).unwrap();
        image::DynamicImage::ImageRgb8(top).save(&b).unwrap();

        let ha = pixel_hash(&a, 1).unwrap();
        let hb = pixel_hash(&b, 1).unwrap();
        assert!(
            hamming_distance_hex(&ha, &hb).unwrap() > 20,
            "expected distinct hashes, got {} vs {}",
            ha,
            hb
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn pixel_hash_is_stable_across_png_and_jpeg() {
        let dir = std::env::temp_dir().join("homehub-hash-test3");
        std::fs::create_dir_all(&dir).unwrap();
        let png = dir.join("same.png");
        let jpg = dir.join("same.jpg");
        let mut img = image::RgbImage::new(64, 64);
        for (x, y, px) in img.enumerate_pixels_mut() {
            *px = image::Rgb([x as u8 * 4, y as u8 * 4, 128]);
        }
        let img = image::DynamicImage::ImageRgb8(img);
        img.save(&png).unwrap();
        img.save_with_format(&jpg, image::ImageFormat::Jpeg)
            .unwrap();

        let hp = pixel_hash(&png, 1).unwrap();
        let hj = pixel_hash(&jpg, 1).unwrap();
        // Lossy JPEG at default quality stays close, well inside the
        // "same picture" threshold used for de-duplication.
        assert!(hamming_distance_hex(&hp, &hj).unwrap() < 32);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
