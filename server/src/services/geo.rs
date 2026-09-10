//! Reverse geocoding through OpenStreetMap Nominatim.
//!
//! Photo GPS is WGS-84, which is exactly what OSM uses — no GCJ-02
//! conversion is needed server-side (the mobile map still converts for
//! display, but the lookup can use the raw EXIF coordinates).
//!
//! Nominatim is free and key-less; its usage policy asks for a descriptive
//! User-Agent and modest request rates, both trivially satisfied here since
//! results are cached in `geo_places` (see `handlers::geo`).

use serde_json::Value;

const USER_AGENT: &str = "HomeHub/0.1 (self-hosted photo manager; contact: local)";

/// Resolve WGS-84 coordinates to a short place label, e.g.
/// "北京市 东城区 东华门街道 正义路". Returns None on any failure.
pub async fn reverse_label(client: &reqwest::Client, lat: f64, lng: f64) -> Option<String> {
    let url = format!(
        "https://nominatim.openstreetmap.org/reverse?lat={lat}&lon={lng}\
         &format=jsonv2&accept-language=zh&zoom=18&addressdetails=1"
    );
    let value: Value = client
        .get(&url)
        .header(reqwest::header::USER_AGENT, USER_AGENT)
        .send()
        .await
        .ok()?
        .json()
        .await
        .ok()?;
    value.get("address").and_then(|a| compose_label(a))
}

/**
 * Build the label from Nominatim's `address` object.
 *
 * Field naming for China is inconsistent (city/district/suburb swap roles
 * across provinces), so collect candidates by priority and keep the first
 * non-blank of each level. The result is "省 市 区 街道" — as specific as
 * the data gets, without house numbers, capped so the mobile filter header
 * stays on one line.
 */
fn compose_label(addr: &Value) -> Option<String> {
    let pick = |keys: &[&str]| -> Option<String> {
        keys.iter()
            .filter_map(|k| addr.get(k).and_then(|v| v.as_str()))
            .map(str::trim)
            .find(|s| !s.is_empty() && *s != "[]")
            .map(str::to_string)
    };

    // 有市/区时省级太宽泛（且直辖市和市完全同名），只在拿不到市一级时退回省级。
    let city = pick(&["city", "town", "county"]);
    let parts = [
        city.clone().or_else(|| pick(&["state", "province", "region"])),
        pick(&["district", "borough"]),
        pick(&["suburb"]),
        pick(&["road", "pedestrian", "quarter", "neighbourhood"]),
    ];
    let mut out: Vec<String> = Vec::new();
    for part in parts.into_iter().flatten() {
        if out.last() != Some(&part) {
            out.push(part);
        }
    }
    let label = out.join(" ");
    (label.chars().count() >= 2).then(|| label.chars().take(24).collect())
}

#[cfg(test)]
mod tests {
    use super::compose_label;
    use serde_json::json;

    #[test]
    fn composes_city_district_road() {
        let addr = json!({
            "road": "正义路",
            "suburb": "东华门街道",
            "district": "东城区",
            "city": "北京市",
            "state": "北京市",
            "country": "中国"
        });
        assert_eq!(
            compose_label(&addr).as_deref(),
            Some("北京市 东城区 东华门街道 正义路")
        );
    }

    #[test]
    fn drops_redundant_province_when_specific() {
        // 有市/区时省级太宽泛，去掉
        let addr = json!({
            "city": "上海市",
            "state": "上海市",
            "district": "黄浦区",
            "country": "中国"
        });
        assert_eq!(compose_label(&addr).as_deref(), Some("上海市 黄浦区"));
    }

    #[test]
    fn keeps_province_when_nothing_better() {
        let addr = json!({"state": "西藏自治区", "country": "中国"});
        assert_eq!(compose_label(&addr).as_deref(), Some("西藏自治区"));
    }

    #[test]
    fn none_when_empty() {
        assert_eq!(compose_label(&json!({})).is_none(), true);
        assert_eq!(compose_label(&json!({"country": "中国"})).is_none(), true);
    }
}
