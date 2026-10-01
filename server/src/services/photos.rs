//! Photo album service: indexing, browsing views and destructive edits.

use std::path::Path;

use anyhow::{Context, Result};
use chrono::Datelike;
use sqlx::Row;

use crate::db::Db;
use crate::models::dir::DirRecord;
use crate::models::photo::{
    DuplicateGroup, GeoPoint, PersonGroupSummary, PhotoAsset, PhotoItem, PhotoTag, TagSummary,
    TimelineGroup, TreeGroup,
};
use crate::services::dirs::DirRegistry;
use crate::services::exif::PhotoMeta;

// ───────────────────────────────── indexing ────────────────────────────────

/// Insert or update a media asset row discovered by the scanner.
#[allow(clippy::too_many_arguments)]
pub async fn upsert(
    db: &Db,
    dir: &DirRecord,
    rel_path: &str,
    fp: &str,
    size: i64,
    mtime: i64,
    meta: &PhotoMeta,
    file_hash: &str,
    pixel_hash: Option<&str>,
    media_kind: &str,
    duration_ms: Option<i64>,
    video_codec: Option<&str>,
) -> Result<i64> {
    let now = crate::db::now();
    sqlx::query(
        "INSERT INTO photo_assets \
            (dir_id, rel_path, fingerprint, file_hash, pixel_hash, size, mtime, taken_at, \
             width, height, orientation, gps_lat, gps_lng, camera_make, camera_model, \
             status, compressed, media_kind, duration_ms, video_codec, created_at, updated_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ok', 0, ?, ?, ?, ?, ?) \
         ON CONFLICT(dir_id, rel_path) DO UPDATE SET \
             fingerprint = excluded.fingerprint, file_hash = excluded.file_hash, \
             pixel_hash = excluded.pixel_hash, size = excluded.size, mtime = excluded.mtime, \
             taken_at = excluded.taken_at, width = excluded.width, height = excluded.height, \
             orientation = excluded.orientation, gps_lat = excluded.gps_lat, \
             gps_lng = excluded.gps_lng, camera_make = excluded.camera_make, \
             camera_model = excluded.camera_model, status = 'ok', \
             media_kind = excluded.media_kind, duration_ms = excluded.duration_ms, \
             video_codec = excluded.video_codec, updated_at = excluded.updated_at",
    )
    .bind(dir.id)
    .bind(rel_path)
    .bind(fp)
    .bind(file_hash)
    .bind(pixel_hash)
    .bind(size)
    .bind(mtime)
    .bind(meta.taken_at)
    .bind(meta.width)
    .bind(meta.height)
    .bind(meta.orientation as i64)
    .bind(meta.gps_lat)
    .bind(meta.gps_lng)
    .bind(&meta.camera_make)
    .bind(&meta.camera_model)
    .bind(media_kind)
    .bind(duration_ms)
    .bind(video_codec)
    .bind(now)
    .bind(now)
    .execute(db)
    .await?;

    let id = photo_id(db, dir.id, rel_path).await?;
    Ok(id)
}

pub async fn photo_id(db: &Db, dir_id: i64, rel_path: &str) -> Result<i64> {
    let row: (i64,) = sqlx::query_as("SELECT id FROM photo_assets WHERE dir_id = ? AND rel_path = ?")
        .bind(dir_id)
        .bind(rel_path)
        .fetch_one(db)
        .await?;
    Ok(row.0)
}

pub async fn get(db: &Db, id: i64) -> Result<Option<PhotoAsset>> {
    Ok(sqlx::query_as::<_, PhotoAsset>("SELECT * FROM photo_assets WHERE id = ?")
        .bind(id)
        .fetch_optional(db)
        .await?)
}

#[allow(dead_code)]
pub async fn get_path(db: &Db, dir_id: i64, rel_path: &str) -> Result<Option<PhotoAsset>> {
    Ok(
        sqlx::query_as::<_, PhotoAsset>(
            "SELECT * FROM photo_assets WHERE dir_id = ? AND rel_path = ?",
        )
        .bind(dir_id)
        .bind(rel_path)
        .fetch_optional(db)
        .await?,
    )
}

/// Files removed from disk are marked `trashed` rather than deleted outright,
/// so accidental deletions stay recoverable.
pub async fn mark_trashed(db: &Db, dir_id: i64, rel_path: &str) -> Result<()> {
    sqlx::query("UPDATE photo_assets SET status = 'trashed', updated_at = ? WHERE dir_id = ? AND rel_path = ?")
        .bind(crate::db::now())
        .bind(dir_id)
        .bind(rel_path)
        .execute(db)
        .await?;
    Ok(())
}

pub async fn delete_photo(db: &Db, id: i64) -> Result<()> {
    sqlx::query("DELETE FROM photo_assets WHERE id = ?")
        .bind(id)
        .execute(db)
        .await?;
    Ok(())
}


// ──────────────────────────────── tag store ────────────────────────────────

pub async fn replace_tags(
    db: &Db,
    photo_id: i64,
    kind: &str,
    tags: &[(String, f64)],
) -> Result<()> {
    sqlx::query("DELETE FROM photo_tags WHERE photo_id = ? AND kind = ?")
        .bind(photo_id)
        .bind(kind)
        .execute(db)
        .await?;
    for (tag, confidence) in tags {
        sqlx::query("INSERT OR REPLACE INTO photo_tags (photo_id, tag, kind, confidence) VALUES (?, ?, ?, ?)")
            .bind(photo_id)
            .bind(tag)
            .bind(kind)
            .bind(confidence)
            .execute(db)
            .await?;
    }
    Ok(())
}

pub async fn tags_of(db: &Db, photo_ids: &[i64]) -> Result<std::collections::HashMap<i64, Vec<PhotoTag>>> {
    let mut out: std::collections::HashMap<i64, Vec<PhotoTag>> = std::collections::HashMap::new();
    if photo_ids.is_empty() {
        return Ok(out);
    }
    let placeholders = photo_ids.iter().map(|_| "?").collect::<Vec<_>>().join(",");
    let sql = format!(
        "SELECT photo_id, tag, kind, confidence FROM photo_tags WHERE photo_id IN ({}) ORDER BY confidence DESC",
        placeholders
    );
    let mut q = sqlx::query(&sql);
    for id in photo_ids {
        q = q.bind(id);
    }
    let rows = q.fetch_all(db).await?;
    for row in rows {
        let pid: i64 = row.try_get("photo_id")?;
        out.entry(pid).or_default().push(PhotoTag {
            tag: row.try_get("tag")?,
            kind: row.try_get("kind")?,
            confidence: row.try_get("confidence")?,
        });
    }
    Ok(out)
}

/// Text used to feed the FTS index for a photo.
pub async fn search_text(db: &Db, photo_id: i64) -> Result<String> {
    let tags: Vec<(String,)> =
        sqlx::query_as("SELECT tag FROM photo_tags WHERE photo_id = ? ORDER BY confidence DESC")
            .bind(photo_id)
            .fetch_all(db)
            .await?;
    Ok(tags
        .into_iter()
        .map(|t| t.0)
        .collect::<Vec<_>>()
        .join(" "))
}

// ───────────────────────────────── browsing ────────────────────────────────

#[derive(Debug, Clone, Default)]
pub struct PhotoQuery {
    pub dir_id: Option<i64>,
    pub tag: Option<String>,
    pub person_id: Option<i64>,
    pub from: Option<i64>,
    pub to: Option<i64>,
    pub has_gps: Option<bool>,
    /// "photo" | "video" — media type filter.
    pub kind: Option<String>,
    /// Explicit id list — used by the map views to expand one geo cluster.
    pub ids: Vec<i64>,
    pub limit: i64,
    pub offset: i64,
}

const SELECT_PHOTO: &str = "SELECT p.*, d.name AS dir_name FROM photo_assets p \
     JOIN dirs d ON d.id = p.dir_id \
     WHERE p.status = 'ok'";

/// Album-directory restriction, templated on the table alias it will be spliced
/// next to. Callers MUST pass the alias in scope at the splice point.
///
/// A fragment written for `p` and injected into a subquery whose own table is
/// aliased `p2` does not fail — SQLite resolves the bare `p` to the *outer*
/// query (correlated subquery), so the filter silently stops constraining the
/// subquery's rows. No error, no warning, just a wrong result; this is what made
/// `tag_summary`'s cover thumbnails leak in from non-album directories.
fn album_clause(album_dirs: &[DirRecord], alias: &str) -> String {
    if album_dirs.is_empty() {
        return " AND 0".to_string();
    }
    let ids = album_dirs
        .iter()
        .map(|d| d.id.to_string())
        .collect::<Vec<_>>()
        .join(",");
    format!(" AND {alias}.dir_id IN ({ids})")
}

pub async fn list(db: &Db, registry: &DirRegistry, q: &PhotoQuery) -> Result<(Vec<PhotoItem>, i64)> {
    let album_dirs = registry.album_dirs();
    let mut filters = album_clause(&album_dirs, "p");

    if let Some(dir_id) = q.dir_id {
        filters.push_str(&format!(" AND p.dir_id = {}", dir_id));
    }
    if q.tag.is_some() {
        filters.push_str(
            " AND EXISTS (SELECT 1 FROM photo_tags t WHERE t.photo_id = p.id AND t.tag = ?)",
        );
    }
    if let Some(person_id) = q.person_id {
        filters.push_str(&format!(
            " AND EXISTS (SELECT 1 FROM faces f WHERE f.photo_id = p.id AND f.group_id = {})",
            person_id
        ));
    }
    if let Some(from) = q.from {
        filters.push_str(&format!(" AND COALESCE(p.taken_at, p.mtime) >= {}", from));
    }
    if let Some(to) = q.to {
        filters.push_str(&format!(" AND COALESCE(p.taken_at, p.mtime) <= {}", to));
    }
    if q.has_gps.unwrap_or(false) {
        filters.push_str(" AND p.gps_lat IS NOT NULL AND p.gps_lng IS NOT NULL");
    }
    if let Some(kind) = &q.kind {
        // Validated by the handler; inlining keeps the bind order simple.
        let k = if kind == "video" { "video" } else { "photo" };
        filters.push_str(&format!(" AND p.media_kind = '{}'", k));
    }
    // Ids are validated integers formatted by us, so inlining them is safe.
    if !q.ids.is_empty() {
        let ids = q
            .ids
            .iter()
            .map(|i| i.to_string())
            .collect::<Vec<_>>()
            .join(",");
        filters.push_str(&format!(" AND p.id IN ({})", ids));
    }

    let count_sql = format!(
        "SELECT COUNT(*) FROM photo_assets p WHERE p.status = 'ok'{}",
        filters
    );
    let data_sql = format!(
        "{} {} ORDER BY COALESCE(p.taken_at, p.mtime) DESC, p.id DESC LIMIT ? OFFSET ?",
        SELECT_PHOTO, filters
    );

    let mut count_q = sqlx::query_as::<_, (i64,)>(&count_sql);
    let mut data_q = sqlx::query_as::<_, PhotoRow>(&data_sql);
    if let Some(tag) = &q.tag {
        count_q = count_q.bind(tag);
        data_q = data_q.bind(tag);
    }
    let total = count_q.fetch_one(db).await.map(|r| r.0).unwrap_or(0);
    let rows = data_q.bind(q.limit).bind(q.offset).fetch_all(db).await?;

    Ok((to_items(db, rows).await?, total))
}

/// Media grouped by year / month / day, aggregated across every album directory.
pub async fn timeline(
    db: &Db,
    registry: &DirRegistry,
    group: &str,
    kind: Option<&str>,
    from: Option<i64>,
    to: Option<i64>,
    limit_per_group: i64,
) -> Result<Vec<TimelineGroup>> {
    let albums = registry.album_dirs();
    let clause = album_clause(&albums, "p");
    let mut sql = format!(
        "SELECT p.*, d.name AS dir_name FROM photo_assets p JOIN dirs d ON d.id = p.dir_id \
         WHERE p.status = 'ok'{}",
        clause
    );
    if let Some(kind) = kind {
        let k = if kind == "video" { "video" } else { "photo" };
        sql.push_str(&format!(" AND p.media_kind = '{}'", k));
    }
    if let Some(from) = from {
        sql.push_str(&format!(" AND COALESCE(p.taken_at, p.mtime) >= {}", from));
    }
    if let Some(to) = to {
        sql.push_str(&format!(" AND COALESCE(p.taken_at, p.mtime) <= {}", to));
    }
    sql.push_str(" ORDER BY COALESCE(p.taken_at, p.mtime) DESC, p.id DESC");

    let rows = sqlx::query_as::<_, PhotoRow>(&sql).fetch_all(db).await?;
    let items = to_items(db, rows).await?;

    let granularity = match group {
        "day" => 2,
        "month" => 1,
        _ => 0,
    };
    let today = chrono::Local::now().date_naive();
    let mut groups: Vec<TimelineGroup> = Vec::new();
    for item in items {
        let dt = chrono::DateTime::from_timestamp(item.taken_at, 0);
        let (year, month, day) = match dt.map(|d| d.with_timezone(&chrono::Local)) {
            Some(d) => (d.year(), d.month(), d.day()),
            None => (1970, 1, 1),
        };
        let (key, label) = match granularity {
            2 => {
                let key = format!("{:04}-{:02}-{:02}", year, month, day);
                let date = chrono::NaiveDate::from_ymd_opt(year, month, day);
                let label = match date {
                    Some(d) if d == today => "今天".to_string(),
                    Some(d) if d == today.pred_opt().unwrap_or(today) => "昨天".to_string(),
                    Some(d) if d.year() == today.year() => {
                        format!("{}月{}日 星期{}", month, day, weekday_cn(d))
                    }
                    _ => format!("{}年{}月{}日", year, month, day),
                };
                (key, label)
            }
            1 => (
                format!("{:04}-{:02}", year, month),
                format!("{}年{}月", year, month),
            ),
            _ => (format!("{:04}", year), format!("{}年", year)),
        };

        let slot = match groups.iter_mut().find(|g| g.key == key) {
            Some(g) => {
                g.count += 1;
                g
            }
            None => {
                groups.push(TimelineGroup {
                    key,
                    label,
                    year,
                    month: if granularity >= 1 { Some(month) } else { None },
                    count: 1,
                    items: Vec::new(),
                });
                groups.last_mut().unwrap()
            }
        };
        if slot.items.len() < limit_per_group as usize {
            slot.items.push(item);
        }
    }
    Ok(groups)
}

fn weekday_cn(d: chrono::NaiveDate) -> &'static str {
    match d.weekday() {
        chrono::Weekday::Mon => "一",
        chrono::Weekday::Tue => "二",
        chrono::Weekday::Wed => "三",
        chrono::Weekday::Thu => "四",
        chrono::Weekday::Fri => "五",
        chrono::Weekday::Sat => "六",
        chrono::Weekday::Sun => "日",
    }
}

/// Photos grouped by their immediate parent folder.
pub async fn tree(
    db: &Db,
    registry: &DirRegistry,
    dir_id: Option<i64>,
) -> Result<Vec<TreeGroup>> {
    let albums = registry.album_dirs();
    let mut clause = album_clause(&albums, "p");
    if let Some(id) = dir_id {
        clause.push_str(&format!(" AND p.dir_id = {}", id));
    }
    let sql = format!(
        "SELECT p.*, d.name AS dir_name FROM photo_assets p JOIN dirs d ON d.id = p.dir_id \
         WHERE p.status = 'ok'{} ORDER BY d.name, p.rel_path",
        clause
    );
    let rows = sqlx::query_as::<_, PhotoRow>(&sql).fetch_all(db).await?;
    let items = to_items(db, rows).await?;

    let mut groups: Vec<TreeGroup> = Vec::new();
    for item in items {
        let folder = parent_folder(&item.rel_path);
        let key = format!("{}:{}", item.dir_id, folder);
        match groups.iter_mut().find(|g| format!("{}:{}", g.dir_id, g.path) == key) {
            Some(g) => {
                g.count += 1;
                g.items.push(item);
            }
            None => groups.push(TreeGroup {
                dir_id: item.dir_id,
                dir_name: item.dir_name.clone(),
                path: folder,
                count: 1,
                items: vec![item],
            }),
        }
    }
    Ok(groups)
}

pub async fn tag_summary(db: &Db, registry: &DirRegistry) -> Result<Vec<TagSummary>> {
    let albums = registry.album_dirs();
    // One clause per alias: the three cover subqueries each alias their own copy
    // of `photo_assets` (p2/p3/p5), so the outer fragment does not reach them.
    let clause = album_clause(&albums, "p");
    let cover_rel_album = album_clause(&albums, "p2");
    let cover_dir_album = album_clause(&albums, "p3");
    let cover_fp_album = album_clause(&albums, "p5");
    let sql = format!(
        "SELECT t.tag AS tag, t.kind AS kind, COUNT(*) AS photo_count, \
                (SELECT p2.rel_path FROM photo_tags t2 JOIN photo_assets p2 ON p2.id = t2.photo_id \
                  WHERE t2.tag = t.tag AND t2.kind = t.kind AND p2.status = 'ok'{} \
                  ORDER BY COALESCE(p2.taken_at, p2.mtime) DESC LIMIT 1) AS cover_rel, \
                (SELECT d2.name FROM photo_tags t3 JOIN photo_assets p3 ON p3.id = t3.photo_id \
                   JOIN dirs d2 ON d2.id = p3.dir_id \
                  WHERE t3.tag = t.tag AND t3.kind = t.kind AND p3.status = 'ok'{} \
                  ORDER BY COALESCE(p3.taken_at, p3.mtime) DESC LIMIT 1) AS cover_dir, \
                (SELECT p5.fingerprint FROM photo_tags t5 JOIN photo_assets p5 ON p5.id = t5.photo_id \
                  WHERE t5.tag = t.tag AND t5.kind = t.kind AND p5.status = 'ok'{} \
                  ORDER BY COALESCE(p5.taken_at, p5.mtime) DESC LIMIT 1) AS cover_fp \
         FROM photo_tags t JOIN photo_assets p ON p.id = t.photo_id \
         WHERE p.status = 'ok'{} \
         GROUP BY t.kind, t.tag ORDER BY photo_count DESC, t.tag",
        cover_rel_album, cover_dir_album, cover_fp_album, clause
    );
    let rows = sqlx::query(&sql).fetch_all(db).await?;
    let mut out = Vec::new();
    for row in rows {
        let cover_rel: Option<String> = row.try_get("cover_rel").ok().flatten();
        let cover_dir: Option<String> = row.try_get("cover_dir").ok().flatten();
        let cover_fp: Option<String> = row.try_get("cover_fp").ok().flatten();
        out.push(TagSummary {
            tag: row.try_get("tag")?,
            kind: row.try_get("kind")?,
            photo_count: row.try_get("photo_count")?,
            cover_url: cover_rel
                .zip(cover_dir)
                .map(|(rel, dir)| thumb_url(&dir, &rel, cover_fp.as_deref().unwrap_or(""))),
        });
    }
    Ok(out)
}

pub async fn people(db: &Db, registry: &DirRegistry) -> Result<Vec<PersonGroupSummary>> {
    let albums = registry.album_dirs();
    let ids: Vec<String> = albums.iter().map(|d| d.id.to_string()).collect();
    let in_clause = if ids.is_empty() { "0".into() } else { ids.join(",") };

    let sql = format!(
        "SELECT g.id AS id, g.name AS name, \
                (SELECT COUNT(*) FROM faces f WHERE f.group_id = g.id) AS face_count, \
                (SELECT COUNT(DISTINCT f2.photo_id) FROM faces f2 JOIN photo_assets p2 ON p2.id = f2.photo_id \
                   WHERE f2.group_id = g.id AND p2.status = 'ok' AND p2.dir_id IN ({ids})) AS photo_count, \
                (SELECT p3.rel_path FROM faces f3 JOIN photo_assets p3 ON p3.id = f3.photo_id \
                   WHERE f3.group_id = g.id AND p3.status = 'ok' AND p3.dir_id IN ({ids}) \
                   ORDER BY COALESCE(p3.taken_at, p3.mtime) DESC LIMIT 1) AS cover_rel, \
                (SELECT d2.name FROM faces f4 JOIN photo_assets p4 ON p4.id = f4.photo_id \
                   JOIN dirs d2 ON d2.id = p4.dir_id \
                   WHERE f4.group_id = g.id AND p4.status = 'ok' AND p4.dir_id IN ({ids}) \
                   ORDER BY COALESCE(p4.taken_at, p4.mtime) DESC LIMIT 1) AS cover_dir, \
                (SELECT p5.fingerprint FROM faces f5 JOIN photo_assets p5 ON p5.id = f5.photo_id \
                   WHERE f5.group_id = g.id AND p5.status = 'ok' AND p5.dir_id IN ({ids}) \
                   ORDER BY COALESCE(p5.taken_at, p5.mtime) DESC LIMIT 1) AS cover_fp \
         FROM person_groups g ORDER BY photo_count DESC, g.id",
        ids = in_clause
    );
    let rows = sqlx::query(&sql).fetch_all(db).await?;
    let mut out = Vec::new();
    for row in rows {
        let cover_rel: Option<String> = row.try_get("cover_rel").ok().flatten();
        let cover_dir: Option<String> = row.try_get("cover_dir").ok().flatten();
        let cover_fp: Option<String> = row.try_get("cover_fp").ok().flatten();
        out.push(PersonGroupSummary {
            id: row.try_get("id")?,
            name: row.try_get("name")?,
            face_count: row.try_get("face_count")?,
            photo_count: row.try_get("photo_count")?,
            cover_url: cover_rel
                .zip(cover_dir)
                .map(|(rel, dir)| thumb_url(&dir, &rel, cover_fp.as_deref().unwrap_or(""))),
        });
    }
    Ok(out)
}

/// Aggregated geo clusters (grid size in degrees).
pub async fn geo(db: &Db, registry: &DirRegistry, precision: f64) -> Result<Vec<GeoPoint>> {
    let albums = registry.album_dirs();
    let clause = album_clause(&albums, "p");
    let sql = format!(
        "SELECT p.id AS id, p.gps_lat AS lat, p.gps_lng AS lng, p.rel_path AS rel_path, \
                p.fingerprint AS fingerprint, \
                d.name AS dir_name FROM photo_assets p JOIN dirs d ON d.id = p.dir_id \
         WHERE p.status = 'ok' AND p.gps_lat IS NOT NULL AND p.gps_lng IS NOT NULL{}",
        clause
    );
    let rows = sqlx::query(&sql).fetch_all(db).await?;

    let step = if precision <= 0.0 { 0.02 } else { precision };
    let mut map: std::collections::HashMap<(i64, i64), GeoPoint> = std::collections::HashMap::new();
    for row in rows {
        let id: i64 = row.try_get("id")?;
        let lat: f64 = row.try_get("lat")?;
        let lng: f64 = row.try_get("lng")?;
        let rel: String = row.try_get("rel_path")?;
        let dir: String = row.try_get("dir_name")?;
        let fp: String = row.try_get("fingerprint").unwrap_or_default();
        let key = ((lat / step).round() as i64, (lng / step).round() as i64);
        let entry = map.entry(key).or_insert(GeoPoint {
            lat: key.0 as f64 * step,
            lng: key.1 as f64 * step,
            count: 0,
            thumb_url: Some(thumb_url(&dir, &rel, &fp)),
            photo_ids: Vec::new(),
        });
        entry.count += 1;
        // Cap matches the `ids` filter in the list endpoint (take(1000)), so a
        // cluster click can always reveal every photo the map claimed.
        if entry.photo_ids.len() < 1000 {
            entry.photo_ids.push(id);
        }
    }
    let mut out: Vec<GeoPoint> = map.into_values().collect();
    out.sort_by(|a, b| b.count.cmp(&a.count));
    Ok(out)
}

/// Duplicate groups found by byte hash or perceptual hash.
pub async fn duplicates(db: &Db, registry: &DirRegistry) -> Result<Vec<DuplicateGroup>> {
    let albums = registry.album_dirs();
    let clause = album_clause(&albums, "p");

    let sql = format!(
        "SELECT p.*, d.name AS dir_name FROM photo_assets p JOIN dirs d ON d.id = p.dir_id \
         WHERE p.status = 'ok'{} AND ( \
            p.file_hash IN (SELECT file_hash FROM photo_assets WHERE status = 'ok' AND file_hash IS NOT NULL GROUP BY file_hash HAVING COUNT(*) > 1) \
            OR p.pixel_hash IN (SELECT pixel_hash FROM photo_assets WHERE status = 'ok' AND pixel_hash IS NOT NULL GROUP BY pixel_hash HAVING COUNT(*) > 1) \
         ) ORDER BY p.file_hash, p.pixel_hash, p.id",
        clause
    );
    let rows = sqlx::query_as::<_, PhotoRow>(&sql).fetch_all(db).await?;
    let items = to_items(db, rows).await?;

    let mut by_exact: std::collections::HashMap<String, Vec<PhotoItem>> =
        std::collections::HashMap::new();
    let mut by_pixel: std::collections::HashMap<String, Vec<PhotoItem>> =
        std::collections::HashMap::new();
    for item in items {
        if let Some(h) = &item.file_hash {
            by_exact.entry(h.clone()).or_default().push(item.clone());
        }
        if let Some(h) = &item.pixel_hash {
            by_pixel.entry(h.clone()).or_default().push(item);
        }
    }

    let mut out = Vec::new();
    for (hash, group) in by_exact {
        if group.len() > 1 {
            out.push(DuplicateGroup {
                fingerprint: hash,
                reason: "file_hash".into(),
                items: group,
            });
        }
    }
    let exact_keys: std::collections::HashSet<String> =
        out.iter().map(|g| g.fingerprint.clone()).collect();
    for (hash, group) in by_pixel {
        if group.len() > 1 && !exact_keys.contains(&hash) {
            out.push(DuplicateGroup {
                fingerprint: hash,
                reason: "pixel_hash".into(),
                items: group,
            });
        }
    }
    Ok(out)
}

// ─────────────────────────────── destructive ops ───────────────────────────

/// Rotate a photo by `angle` degrees (90/180/270), writing the file back.
///
/// This is a destructive operation: the original pixels are archived into
/// `.originals/` before the file is replaced (see `originals` service).
pub async fn rotate(
    db: &Db,
    registry: &DirRegistry,
    config: &crate::config::Config,
    id: i64,
    angle: i32,
) -> Result<PhotoItem> {
    if ![90, 180, 270].contains(&angle) {
        anyhow::bail!("angle must be 90, 180 or 270");
    }
    let photo = get(db, id)
        .await?
        .ok_or_else(|| anyhow::anyhow!("photo {} not found", id))?;
    if photo.media_kind == "video" {
        anyhow::bail!("videos cannot be rotated");
    }
    let (dir, full) = registry
        .resolve_id(photo.dir_id, &photo.rel_path)
        .ok_or_else(|| anyhow::anyhow!("cannot resolve photo path"))?;
    if !full.exists() {
        anyhow::bail!("file missing on disk: {}", full.display());
    }

    // 1) archive the current pixels
    crate::services::originals::archive(&full, &dir, &photo.rel_path, config).await?;

    // 2) lossless rotate + reset orientation to 1
    let full_clone = full.clone();
    let jpegtran_path = config.runtime.compression.jpegtran_path.clone();
    tokio::task::spawn_blocking(move || rotate_file(&full_clone, angle, &jpegtran_path)).await??;

    // 3) refresh metadata, drop the stale thumbnail
    let metadata = std::fs::metadata(&full)?;
    let mtime = metadata
        .modified()?
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    let size = metadata.len() as i64;
    let meta = crate::services::exif::read_metadata(&full)?;
    let fp = crate::services::paths::fingerprint(mtime, size as u64);
    let file_hash = crate::services::hash::file_sha256(&full)?;
    let pixel_hash = crate::services::hash::pixel_hash(&full, 1);

    let new_id = upsert(
        db,
        &dir,
        &photo.rel_path,
        &fp,
        size,
        mtime,
        &meta,
        &file_hash,
        pixel_hash.as_deref(),
        "photo",
        photo.duration_ms,
        photo.video_codec.as_deref(),
    )
    .await?;

    let _ = tokio::fs::remove_file(crate::services::thumbs::thumbnail_path(
        &full,
        &photo.fingerprint,
    ))
    .await;

    let item = get(db, new_id)
        .await?
        .ok_or_else(|| anyhow::anyhow!("photo disappeared"))?;
    crate::services::search::index_photo(db, &item, &dir.name).await?;
    Ok(to_item(db, PhotoRow::from_asset(item, dir.name)).await?)
}

fn rotate_file(path: &Path, angle: i32, jpegtran_path: &str) -> Result<()> {
    let tmp = path.with_extension("rotate.tmp");
    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("jpg")
        .to_lowercase();
    let is_jpeg = matches!(ext.as_str(), "jpg" | "jpeg" | "jpe" | "jfif");

    // JPEG: prefer a truly lossless rotation via jpegtran (Huffman-safe,
    // no re-quantisation) plus an EXIF Orientation reset so viewers don't
    // rotate the already-rotated pixels a second time.
    if is_jpeg
        && lossless_rotate_jpeg(path, &tmp, angle, jpegtran_path)
        && lossless_rotate_ok(path, &tmp, angle)
        && crate::services::exif::reset_jpeg_orientation(&tmp, 1).is_ok()
        && lossless_rotate_ok(path, &tmp, angle)
    {
        std::fs::rename(&tmp, path).context("atomic replace")?;
        return Ok(());
    }
    let _ = std::fs::remove_file(&tmp);

    // Fallback (non-JPEG, jpegtran missing, or any lossless-path failure):
    // decode → rotate → re-encode. JPEG gets quality 95 as the least-bad
    // option; the untouched pixels were archived to `.originals/` first.
    let img = image::open(path)?;
    let rotated = match angle {
        90 => img.rotate90(),
        180 => img.rotate180(),
        270 => img.rotate270(),
        _ => img,
    };
    let format = match ext.as_str() {
        "png" => image::ImageFormat::Png,
        "webp" => image::ImageFormat::WebP,
        "gif" => image::ImageFormat::Gif,
        "bmp" => image::ImageFormat::Bmp,
        _ => image::ImageFormat::Jpeg,
    };
    if format == image::ImageFormat::Jpeg {
        let mut out = std::fs::File::create(&tmp)?;
        let mut enc = image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out, 95);
        enc.encode_image(&rotated)?;
        drop(enc);
        drop(out);
    } else {
        rotated.save_with_format(&tmp, format)?;
    }
    std::fs::rename(&tmp, path).context("atomic replace")?;
    Ok(())
}

/// Rotate JPEG pixels losslessly with jpegtran. Returns true on success.
fn lossless_rotate_jpeg(path: &Path, out: &Path, angle: i32, jpegtran_path: &str) -> bool {
    let result = std::process::Command::new(jpegtran_path)
        .arg("-copy")
        .arg("all")
        .arg("-rotate")
        .arg(angle.to_string())
        .arg("-outfile")
        .arg(out)
        .arg(path)
        .output();
    match result {
        Ok(o) if o.status.success() && out.exists() => true,
        Ok(o) => {
            tracing::debug!(
                "jpegtran rotate failed for {}: {}",
                path.display(),
                String::from_utf8_lossy(&o.stderr)
            );
            false
        }
        Err(e) => {
            tracing::debug!("cannot run {}: {}", jpegtran_path, e);
            false
        }
    }
}

/// Sanity-check the lossless result: the file must decode and the pixel
/// dimensions must match the expected post-rotation size.
fn lossless_rotate_ok(original: &Path, rotated: &Path, angle: i32) -> bool {
    let (Some(orig), Some(rot)) = (
        image::image_dimensions(original).ok(),
        image::image_dimensions(rotated).ok(),
    ) else {
        return false;
    };
    match angle {
        90 | 270 => orig.0 == rot.1 && orig.1 == rot.0,
        _ => orig == rot,
    }
}

// ───────────────────────────────── helpers ─────────────────────────────────

/// Row projection used by every list-like query.
#[allow(dead_code)]
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct PhotoRow {
    pub id: i64,
    pub dir_id: i64,
    pub rel_path: String,
    pub fingerprint: String,
    pub file_hash: Option<String>,
    pub pixel_hash: Option<String>,
    pub size: i64,
    pub mtime: i64,
    pub taken_at: Option<i64>,
    pub width: Option<i64>,
    pub height: Option<i64>,
    pub orientation: i64,
    pub gps_lat: Option<f64>,
    pub gps_lng: Option<f64>,
    pub camera_make: Option<String>,
    pub camera_model: Option<String>,
    pub status: String,
    pub compressed: i64,
    pub media_kind: String,
    pub duration_ms: Option<i64>,
    pub video_codec: Option<String>,
    pub created_at: i64,
    pub updated_at: i64,
    pub dir_name: String,
}

impl PhotoRow {
    pub fn from_asset(a: PhotoAsset, dir_name: String) -> Self {
        Self {
            id: a.id,
            dir_id: a.dir_id,
            rel_path: a.rel_path,
            fingerprint: a.fingerprint,
            file_hash: a.file_hash,
            pixel_hash: a.pixel_hash,
            size: a.size,
            mtime: a.mtime,
            taken_at: a.taken_at,
            width: a.width,
            height: a.height,
            orientation: a.orientation,
            gps_lat: a.gps_lat,
            gps_lng: a.gps_lng,
            camera_make: a.camera_make,
            camera_model: a.camera_model,
            status: a.status,
            compressed: a.compressed,
            media_kind: a.media_kind,
            duration_ms: a.duration_ms,
            video_codec: a.video_codec,
            created_at: a.created_at,
            updated_at: a.updated_at,
            dir_name,
        }
    }

    pub fn sort_time(&self) -> i64 {
        self.taken_at.unwrap_or(self.mtime)
    }
}

pub fn parent_folder(rel: &str) -> String {
    match rel.rfind('/') {
        Some(idx) => rel[..idx].to_string(),
        None => String::new(),
    }
}

/// URL-safe form of a photo's fingerprint, used as the media cache token.
///
/// `fingerprint` is `mtime:size`; the colon is normalised to `-` so the query
/// string stays free of reserved characters.
fn version_token(fingerprint: &str) -> String {
    if fingerprint.is_empty() {
        "0".to_string()
    } else {
        fingerprint.replace(':', "-")
    }
}

/// Thumbnail URL, carrying a version token.
///
/// Photos are edited in place (rotate / flip / restore), yet their path never
/// changes. Without a version in the URL an HTTP cache is entitled to keep
/// serving the pre-edit pixels — Coil honoured the `max-age=86400` on
/// `/api/media` and showed stale thumbnails for a day, even after a restart.
/// `photo_assets.fingerprint` (`mtime:size`) moves on every edit, so it makes
/// the URL change with the pixels.
pub fn thumb_url(dir_name: &str, rel_path: &str, fingerprint: &str) -> String {
    format!(
        "/api/media/{}/{}?size=thumb&v={}",
        dir_name,
        rel_path,
        version_token(fingerprint)
    )
}

/// Full-size media URL; versioned for the same reason as [thumb_url].
pub fn full_url(dir_name: &str, rel_path: &str, fingerprint: &str) -> String {
    format!(
        "/api/media/{}/{}?v={}",
        dir_name,
        rel_path,
        version_token(fingerprint)
    )
}

pub async fn to_items(db: &Db, rows: Vec<PhotoRow>) -> Result<Vec<PhotoItem>> {
    let ids: Vec<i64> = rows.iter().map(|r| r.id).collect();
    let tags = tags_of(db, &ids).await.unwrap_or_default();
    Ok(rows
        .into_iter()
        .map(|r| {
            let item_tags = tags.get(&r.id).cloned().unwrap_or_default();
            from_row(r, item_tags)
        })
        .collect())
}

async fn to_item(db: &Db, row: PhotoRow) -> Result<PhotoItem> {
    let id = row.id;
    let mut tags = tags_of(db, &[id]).await.unwrap_or_default();
    Ok(from_row(row, tags.remove(&id).unwrap_or_default()))
}

fn from_row(r: PhotoRow, tags: Vec<PhotoTag>) -> PhotoItem {
    let taken_at = r.sort_time();
    let dir_name = r.dir_name.clone();
    let rel_path = r.rel_path.clone();
    let name = rel_path
        .rsplit('/')
        .next()
        .unwrap_or(&rel_path)
        .to_string();
    PhotoItem {
        file_hash: r.file_hash.clone(),
        pixel_hash: r.pixel_hash.clone(),
        thumb_url: thumb_url(&dir_name, &rel_path, &r.fingerprint),
        url: full_url(&dir_name, &rel_path, &r.fingerprint),
        id: r.id,
        dir_id: r.dir_id,
        dir_name,
        rel_path,
        name,
        taken_at,
        width: r.width,
        height: r.height,
        size: r.size,
        orientation: r.orientation,
        gps_lat: r.gps_lat,
        gps_lng: r.gps_lng,
        camera_make: r.camera_make,
        camera_model: r.camera_model,
        media_kind: r.media_kind,
        duration_ms: r.duration_ms,
        video_codec: r.video_codec,
        tags,
    }
}
