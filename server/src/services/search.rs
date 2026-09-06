//! FTS5 backed global search across photos and files.

use anyhow::Result;
use serde::Serialize;

use crate::db::Db;
use crate::models::photo::PhotoAsset;

/// Update the search entry for a photo (name + tags).
pub async fn index_photo(db: &Db, asset: &PhotoAsset, dir_name: &str) -> Result<()> {
    let name = asset
        .rel_path
        .rsplit('/')
        .next()
        .unwrap_or(&asset.rel_path)
        .to_string();
    let tags = crate::services::photos::search_text(db, asset.id).await?;
    remove(db, "photo", &asset.id.to_string()).await?;
    sqlx::query("INSERT INTO search_index (ftype, ref_id, title, body) VALUES ('photo', ?, ?, ?)")
        .bind(asset.id.to_string())
        .bind(&name)
        .bind(format!("{} {} {}", name, dir_name, tags))
        .execute(db)
        .await?;
    Ok(())
}

/// Update the search entry for an arbitrary file.
pub async fn index_file(
    db: &Db,
    dir_id: i64,
    dir_name: &str,
    rel_path: &str,
    name: &str,
) -> Result<()> {
    let ref_id = format!("{}:{}", dir_id, rel_path);
    remove(db, "file", &ref_id).await?;
    sqlx::query("INSERT INTO search_index (ftype, ref_id, title, body) VALUES ('file', ?, ?, ?)")
        .bind(&ref_id)
        .bind(name)
        .bind(format!("{} {} {}", name, dir_name, rel_path))
        .execute(db)
        .await?;
    Ok(())
}

pub async fn remove(db: &Db, ftype: &str, ref_id: &str) -> Result<()> {
    sqlx::query("DELETE FROM search_index WHERE ftype = ? AND ref_id = ?")
        .bind(ftype)
        .bind(ref_id)
        .execute(db)
        .await?;
    Ok(())
}

pub async fn remove_dir(db: &Db, dir_id: i64) -> Result<()> {
    sqlx::query("DELETE FROM search_index WHERE ftype = 'file' AND ref_id LIKE ?")
        .bind(format!("{}:%", dir_id))
        .execute(db)
        .await?;
    Ok(())
}

#[derive(Debug, Clone, Serialize)]
pub struct SearchHit {
    pub ftype: String,
    pub ref_id: String,
    pub title: String,
    pub snippet: Option<String>,
    /// Photo id when `ftype == "photo"`.
    pub photo_id: Option<i64>,
    /// `dir_id` when `ftype == "file"`.
    pub dir_id: Option<i64>,
    pub rel_path: Option<String>,
    pub thumb_url: Option<String>,
}

#[derive(Debug, Clone, Default)]
pub struct SearchQuery {
    pub q: String,
    pub ftype: Option<String>,
    pub from: Option<i64>,
    pub to: Option<i64>,
    pub limit: i64,
    pub offset: i64,
}

/// Build an FTS5 MATCH expression: every token quoted, last one prefix-matched.
fn match_expr(q: &str) -> String {
    let tokens: Vec<String> = q
        .split_whitespace()
        .map(|t| t.replace('"', ""))
        .filter(|t| !t.is_empty())
        .collect();
    if tokens.is_empty() {
        return String::new();
    }
    let mut out = String::new();
    for (i, t) in tokens.iter().enumerate() {
        if i > 0 {
            out.push_str(" AND ");
        }
        out.push('"');
        out.push_str(t);
        if i == tokens.len() - 1 {
            out.push('"');
            out.push('*');
        } else {
            out.push('"');
        }
    }
    out
}

pub async fn search(db: &Db, query: &SearchQuery) -> Result<Vec<SearchHit>> {
    let expr = match_expr(&query.q);
    if expr.is_empty() {
        return Ok(Vec::new());
    }
    let limit = if query.limit <= 0 { 100 } else { query.limit };

    let sql = "SELECT ftype, ref_id, title, snippet(search_index, 2, '<b>', '</b>', '…', 12) AS snip \
               FROM search_index WHERE search_index MATCH ? ORDER BY rank LIMIT ? OFFSET ?";
    let rows = sqlx::query(sql)
        .bind(&expr)
        .bind(limit)
        .bind(query.offset)
        .fetch_all(db)
        .await;

    let rows = match rows {
        Ok(r) => r,
        Err(e) => {
            tracing::warn!("fts query failed ({}), falling back to LIKE", e);
            return like_search(db, query).await;
        }
    };

    let mut hits = Vec::new();
    for row in rows {
        use sqlx::Row;
        let ftype: String = row.try_get("ftype")?;
        let ref_id: String = row.try_get("ref_id")?;
        let title: String = row.try_get("title")?;
        let snippet: Option<String> = row.try_get("snip").ok().flatten();
        hits.push(decorate(db, ftype, ref_id, title, snippet).await?);
    }

    let hits = apply_filters(db, hits, query).await?;
    if hits.is_empty() {
        return like_search(db, query).await;
    }
    Ok(hits)
}

/// Keep only hits whose timestamp falls inside the requested range.
async fn apply_filters(
    db: &Db,
    hits: Vec<SearchHit>,
    query: &SearchQuery,
) -> Result<Vec<SearchHit>> {
    let mut out = Vec::new();
    let limit = if query.limit <= 0 { 100 } else { query.limit } as usize;

    let photo_ids: Vec<i64> = hits
        .iter()
        .filter(|h| h.ftype == "photo")
        .filter_map(|h| h.photo_id)
        .collect();

    // One query for the whole page: which photos are inside the time range?
    let mut in_range: std::collections::HashSet<i64> = std::collections::HashSet::new();
    if query.from.is_some() || query.to.is_some() {
        let placeholders = photo_ids.iter().map(|_| "?").collect::<Vec<_>>().join(",");
        if !placeholders.is_empty() {
            let mut sql = format!(
                "SELECT id FROM photo_assets WHERE id IN ({})",
                placeholders
            );
            if let Some(from) = query.from {
                sql.push_str(&format!(" AND COALESCE(taken_at, mtime) >= {}", from));
            }
            if let Some(to) = query.to {
                sql.push_str(&format!(" AND COALESCE(taken_at, mtime) <= {}", to));
            }
            let mut q = sqlx::query_as::<_, (i64,)>(&sql);
            for id in &photo_ids {
                q = q.bind(id);
            }
            in_range = q
                .fetch_all(db)
                .await?
                .into_iter()
                .map(|r| r.0)
                .collect();
        }
    } else {
        in_range = photo_ids.into_iter().collect();
    }

    for hit in hits {
        if let Some(ftype) = &query.ftype {
            if &hit.ftype != ftype {
                continue;
            }
        }
        if hit.ftype == "photo" {
            match hit.photo_id {
                Some(id) if in_range.contains(&id) => {}
                _ => continue,
            }
        }
        out.push(hit);
        if out.len() >= limit {
            break;
        }
    }
    Ok(out)
}

/// Fallback for CJK / partial matches that the unicode61 tokenizer cannot split.
async fn like_search(db: &Db, query: &SearchQuery) -> Result<Vec<SearchHit>> {
    let pattern = format!("%{}%", query.q.replace('%', "").trim());
    if pattern.len() < 3 {
        return Ok(Vec::new());
    }
    let limit = if query.limit <= 0 { 100 } else { query.limit };

    let mut hits = Vec::new();

    if matches!(query.ftype.as_deref(), None | Some("photo")) {
        let rows: Vec<(i64, i64, String)> = sqlx::query_as(
            "SELECT p.id, p.dir_id, p.rel_path FROM photo_assets p \
             WHERE p.status = 'ok' AND p.rel_path LIKE ? ORDER BY p.mtime DESC LIMIT ?",
        )
        .bind(&pattern)
        .bind(limit)
        .fetch_all(db)
        .await?;
        for (id, dir_id, rel_path) in rows {
            hits.push(
                decorate_photo(
                    db,
                    id,
                    dir_id,
                    rel_path
                        .rsplit('/')
                        .next()
                        .unwrap_or(&rel_path)
                        .to_string(),
                    None,
                )
                .await?,
            );
        }
    }

    if matches!(query.ftype.as_deref(), None | Some("file")) {
        let rows: Vec<(i64, String)> = sqlx::query_as(
            "SELECT dir_id, rel_path FROM file_index WHERE rel_path LIKE ? LIMIT ?",
        )
        .bind(&pattern)
        .bind(limit)
        .fetch_all(db)
        .await?;
        for (dir_id, rel_path) in rows {
            hits.push(
                decorate(
                    db,
                    "file".into(),
                    format!("{}:{}", dir_id, rel_path),
                    rel_path.rsplit('/').next().unwrap_or(&rel_path).to_string(),
                    None,
                )
                .await?,
            );
        }
    }

    Ok(apply_filters(db, hits, query).await?)
}



async fn decorate(
    db: &Db,
    ftype: String,
    ref_id: String,
    title: String,
    snippet: Option<String>,
) -> Result<SearchHit> {
    if ftype == "photo" {
        let id: i64 = ref_id.parse().unwrap_or(0);
        if let Ok(Some((dir_id, rel_path))) =
            sqlx::query_as::<_, (i64, String)>("SELECT dir_id, rel_path FROM photo_assets WHERE id = ?")
                .bind(id)
                .fetch_optional(db)
                .await
        {
            return decorate_photo(db, id, dir_id, title, snippet).await.map(|mut h| {
                h.rel_path = Some(rel_path);
                h
            });
        }
    }
    let (dir_id, rel_path) = match ref_id.split_once(':') {
        Some((d, r)) => (d.parse::<i64>().ok(), r.to_string()),
        None => (None, String::new()),
    };
    Ok(SearchHit {
        ftype,
        ref_id,
        title,
        snippet,
        photo_id: None,
        dir_id,
        rel_path: Some(rel_path),
        thumb_url: None,
    })
}

async fn decorate_photo(
    db: &Db,
    id: i64,
    dir_id: i64,
    title: String,
    snippet: Option<String>,
) -> Result<SearchHit> {
    let dir_name: Option<(String,)> =
        sqlx::query_as("SELECT name FROM dirs WHERE id = ?")
            .bind(dir_id)
            .fetch_optional(db)
            .await?;
    let rel: Option<(String,)> =
        sqlx::query_as("SELECT rel_path FROM photo_assets WHERE id = ?")
            .bind(id)
            .fetch_optional(db)
            .await?;
    let dir_name = dir_name.map(|d| d.0).unwrap_or_default();
    let rel_path = rel.map(|r| r.0).unwrap_or_default();
    Ok(SearchHit {
        ftype: "photo".into(),
        ref_id: id.to_string(),
        title,
        snippet,
        photo_id: Some(id),
        dir_id: Some(dir_id),
        thumb_url: Some(crate::services::photos::thumb_url(&dir_name, &rel_path)),
        rel_path: Some(rel_path),
    })
}

/// Rebuild the whole index (used by a full rescan).
pub async fn rebuild(db: &Db) -> Result<usize> {
    sqlx::query("DELETE FROM search_index").execute(db).await?;
    let photos: Vec<(i64, i64, String)> =
        sqlx::query_as("SELECT id, dir_id, rel_path FROM photo_assets WHERE status = 'ok'")
            .fetch_all(db)
            .await?;
    let mut n = 0;
    for (id, dir_id, rel_path) in photos {
        let dir_name: Option<(String,)> = sqlx::query_as("SELECT name FROM dirs WHERE id = ?")
            .bind(dir_id)
            .fetch_optional(db)
            .await?;
        let name = rel_path.rsplit('/').next().unwrap_or(&rel_path).to_string();
        let tags = crate::services::photos::search_text(db, id).await?;
        let dir_name = dir_name.map(|d| d.0).unwrap_or_default();
        sqlx::query(
            "INSERT INTO search_index (ftype, ref_id, title, body) VALUES ('photo', ?, ?, ?)",
        )
        .bind(id.to_string())
        .bind(&name)
        .bind(format!("{} {} {}", name, dir_name, tags))
        .execute(db)
        .await?;
        n += 1;
    }

    let files: Vec<(i64, String, i64)> =
        sqlx::query_as("SELECT f.dir_id, f.rel_path, 0 FROM file_index f")
            .fetch_all(db)
            .await?;
    for (dir_id, rel_path, _) in files {
        let dir_name: Option<(String,)> = sqlx::query_as("SELECT name FROM dirs WHERE id = ?")
            .bind(dir_id)
            .fetch_optional(db)
            .await?;
        let name = rel_path.rsplit('/').next().unwrap_or(&rel_path).to_string();
        let dir_name = dir_name.map(|d| d.0).unwrap_or_default();
        sqlx::query(
            "INSERT INTO search_index (ftype, ref_id, title, body) VALUES ('file', ?, ?, ?)",
        )
        .bind(format!("{}:{}", dir_id, rel_path))
        .bind(&name)
        .bind(format!("{} {} {}", name, dir_name, rel_path))
        .execute(db)
        .await?;
        n += 1;
    }
    Ok(n)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_prefix_match_expression() {
        assert_eq!(match_expr("beach"), "\"beach\"*");
        assert_eq!(match_expr("beach sun"), "\"beach\" AND \"sun\"*");
        assert_eq!(match_expr("  "), "");
    }
}
