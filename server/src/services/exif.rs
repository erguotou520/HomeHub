//! EXIF extraction: capture time, orientation, GPS, camera.

use std::path::Path;

use anyhow::Result;
use exif::{In, Reader, Tag, Value};

/// Everything we can learn about a photo without decoding the pixels.
#[derive(Debug, Clone, Default)]
pub struct PhotoMeta {
    pub taken_at: Option<i64>,
    pub orientation: u32,
    pub gps_lat: Option<f64>,
    pub gps_lng: Option<f64>,
    pub camera_make: Option<String>,
    pub camera_model: Option<String>,
    pub width: Option<i64>,
    pub height: Option<i64>,
}

/// Read EXIF and fall back to decoding the image for dimensions.
pub fn read_metadata(path: &Path) -> Result<PhotoMeta> {
    let mut meta = PhotoMeta {
        orientation: 1,
        ..Default::default()
    };

    if let Ok(file) = std::fs::File::open(path) {
        let mut bufreader = std::io::BufReader::new(&file);
        if let Ok(exif) = Reader::new().read_from_container(&mut bufreader) {
            meta.taken_at = read_date(&exif);
            meta.orientation = exif
                .get_field(Tag::Orientation, In::PRIMARY)
                .and_then(|f| f.value.get_uint(0))
                .unwrap_or(1)
                .clamp(1, 8);
            let (lat, lng) = read_gps(&exif);
            meta.gps_lat = lat;
            meta.gps_lng = lng;
            meta.camera_make = exif
                .get_field(Tag::Make, In::PRIMARY)
                .map(|f| f.display_value().to_string().trim().to_string())
                .filter(|s| !s.is_empty());
            meta.camera_model = exif
                .get_field(Tag::Model, In::PRIMARY)
                .map(|f| f.display_value().to_string().trim().to_string())
                .filter(|s| !s.is_empty());
            if let Some(w) = exif
                .get_field(Tag::PixelXDimension, In::PRIMARY)
                .and_then(|f| f.value.get_uint(0))
            {
                meta.width = Some(w as i64);
            }
            if let Some(h) = exif
                .get_field(Tag::PixelYDimension, In::PRIMARY)
                .and_then(|f| f.value.get_uint(0))
            {
                meta.height = Some(h as i64);
            }
        }
    }

    if meta.width.is_none() || meta.height.is_none() {
        if let Ok(img) = image::image_dimensions(path) {
            meta.width = Some(img.0 as i64);
            meta.height = Some(img.1 as i64);
        }
    }

    Ok(meta)
}

fn read_date(exif: &exif::Exif) -> Option<i64> {
    let field = exif
        .get_field(Tag::DateTimeOriginal, In::PRIMARY)
        .or_else(|| exif.get_field(Tag::DateTimeDigitized, In::PRIMARY))
        .or_else(|| exif.get_field(Tag::DateTime, In::PRIMARY))?;

    let raw = match &field.value {
        Value::Ascii(v) => v.first().map(|s| String::from_utf8_lossy(s).to_string()),
        _ => None,
    }?;
    parse_exif_datetime(raw.trim())
}

/// Parse `YYYY:MM:DD HH:MM:SS` (EXIF) into a unix timestamp.
pub fn parse_exif_datetime(raw: &str) -> Option<i64> {
    let (date, time) = raw.split_once(' ')?;
    let d: Vec<&str> = date.split(':').collect();
    let t: Vec<&str> = time.split(':').collect();
    if d.len() != 3 || t.len() < 2 {
        return None;
    }
    let year: i32 = d[0].parse().ok()?;
    let month: u32 = d[1].parse().ok()?;
    let day: u32 = d[2].parse().ok()?;
    let hour: u32 = t[0].parse().ok()?;
    let min: u32 = t[1].parse().ok()?;
    let sec: u32 = t.get(2).and_then(|s| s.parse().ok()).unwrap_or(0);

    let dt = chrono::NaiveDate::from_ymd_opt(year, month, day)?
        .and_hms_opt(hour, min, sec)?;
    Some(dt.and_utc().timestamp())
}

fn read_gps(exif: &exif::Exif) -> (Option<f64>, Option<f64>) {
    let lat_field = exif.get_field(Tag::GPSLatitude, In::PRIMARY);
    let lat_ref = exif
        .get_field(Tag::GPSLatitudeRef, In::PRIMARY)
        .map(|f| f.display_value().to_string())
        .unwrap_or_else(|| "N".to_string());
    let lng_field = exif.get_field(Tag::GPSLongitude, In::PRIMARY);
    let lng_ref = exif
        .get_field(Tag::GPSLongitudeRef, In::PRIMARY)
        .map(|f| f.display_value().to_string())
        .unwrap_or_else(|| "E".to_string());

    let lat = lat_field.and_then(|f| dms_to_decimal(&f.value));
    let lng = lng_field.and_then(|f| dms_to_decimal(&f.value));

    let lat = lat.map(|v| if lat_ref.starts_with('S') { -v } else { v });
    let lng = lng.map(|v| if lng_ref.starts_with('W') { -v } else { v });
    (lat, lng)
}

fn dms_to_decimal(value: &Value) -> Option<f64> {
    let deg = rational_at(value, 0)?;
    let min = rational_at(value, 1)?;
    let sec = rational_at(value, 2).unwrap_or(0.0);
    let dec = deg + min / 60.0 + sec / 3600.0;
    if dec.is_finite() {
        Some(dec)
    } else {
        None
    }
}

fn rational_at(value: &Value, idx: usize) -> Option<f64> {
    match value {
        Value::Rational(v) => {
            let r = v.get(idx)?;
            if r.denom == 0 {
                return None;
            }
            Some(r.num as f64 / r.denom as f64)
        }
        Value::SRational(v) => {
            let r = v.get(idx)?;
            if r.denom == 0 {
                return None;
            }
            Some(r.num as f64 / r.denom as f64)
        }
        _ => None,
    }
}

/// Rotation (in degrees, clockwise) implied by the EXIF orientation flag.
#[allow(dead_code)]
pub fn orientation_degrees(orientation: u32) -> i32 {
    match orientation {
        3 => 180,
        6 => 90,
        8 => 270,
        _ => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_exif_datetime() {
        let ts = parse_exif_datetime("2024:05:01 12:34:56").unwrap();
        // 2024-05-01T12:34:56Z
        assert_eq!(ts, 1714566896);
        assert!(parse_exif_datetime("garbage").is_none());
    }

    #[test]
    fn orientation_mapping() {
        assert_eq!(orientation_degrees(1), 0);
        assert_eq!(orientation_degrees(6), 90);
        assert_eq!(orientation_degrees(3), 180);
        assert_eq!(orientation_degrees(8), 270);
    }
}
