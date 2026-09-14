//! Face clustering: group detected faces into `person_groups`.
//!
//! Primary path: recognition embeddings (128-d cosine similarity against the
//! group representative, `match-threshold`). Fallback when a face has no
//! embedding (recognizer absent): 16x16 perceptual hash with a hamming
//! distance bound (see PRD open question #2).

use anyhow::Result;
use sqlx::Row;

use crate::config::MlFaceConfig;
use crate::db::Db;
use crate::services::hash::hamming_distance_hex;

/// A face's clustering signals: phash and/or embedding.
#[derive(Debug, Clone)]
pub struct FaceSignals {
    pub phash: Option<String>,
    /// f32 little-endian blob as stored in `faces.embedding`.
    pub embedding: Option<Vec<u8>>,
}

impl FaceSignals {
    fn embedding_vec(&self) -> Option<Vec<f32>> {
        let blob = self.embedding.as_ref()?;
        if blob.len() % 4 != 0 {
            return None;
        }
        Some(
            blob.chunks_exact(4)
                .map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]]))
                .collect(),
        )
    }
}

/// Cosine similarity of two vectors; None if either is empty.
fn cosine(a: &[f32], b: &[f32]) -> Option<f32> {
    if a.is_empty() || b.is_empty() || a.len() != b.len() {
        return None;
    }
    let mut dot = 0f32;
    let mut na = 0f32;
    let mut nb = 0f32;
    for i in 0..a.len() {
        dot += a[i] * b[i];
        na += a[i] * a[i];
        nb += b[i] * b[i];
    }
    if na <= 0.0 || nb <= 0.0 {
        return None;
    }
    Some(dot / (na.sqrt() * nb.sqrt()))
}

/// Assign a face to a person group, creating one when needed.
///
/// Dual gate (embedding path): max similarity vs ANY member must reach
/// `match_threshold` (loose) AND vs one of the group's dispersed prototypes
/// `prototype_threshold` (strict) — max-member alone degenerates into
/// single-linkage chaining where A≈B≈C merges faces A and C would never match.
pub async fn assign_group(db: &Db, face_id: i64, signals: &FaceSignals, cfg: &MlFaceConfig) -> Result<i64> {
    if let Some(group_id) = find_group(db, signals, cfg).await? {
        sqlx::query("UPDATE faces SET group_id = ? WHERE id = ?")
            .bind(group_id)
            .bind(face_id)
            .execute(db)
            .await?;
        maintain_prototypes(db, group_id, face_id, signals, cfg).await?;
        return Ok(group_id);
    }

    let now = crate::db::now();
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO person_groups (name, representative_face_id, created_at) VALUES (NULL, ?, ?) RETURNING id",
    )
    .bind(face_id)
    .bind(now)
    .fetch_one(db)
    .await?;
    sqlx::query("UPDATE faces SET group_id = ? WHERE id = ?")
        .bind(id)
        .bind(face_id)
        .execute(db)
        .await?;
    // The founding face is also prototype #1.
    if signals.embedding.is_some() {
        sqlx::query("INSERT OR IGNORE INTO person_group_prototypes (group_id, face_id) VALUES (?, ?)")
            .bind(id)
            .bind(face_id)
            .execute(db)
            .await?;
    }
    Ok(id)
}

/// Keep the group's prototype set dispersed: a new face becomes a prototype
/// only when it looks different enough (cosine sim <= 1 - `prototype_spread`)
/// from every existing prototype, capped at `prototype_count`. This yields
/// 5–10 "canonical looks" (ages, angles) without any averaging.
async fn maintain_prototypes(
    db: &Db,
    group_id: i64,
    face_id: i64,
    signals: &FaceSignals,
    cfg: &MlFaceConfig,
) -> Result<()> {
    let Some(incoming) = signals.embedding_vec() else {
        return Ok(());
    };
    let rows: Vec<Vec<u8>> = sqlx::query_scalar(
        "SELECT f.embedding FROM person_group_prototypes p \
         JOIN faces f ON f.id = p.face_id WHERE p.group_id = ? AND f.embedding IS NOT NULL",
    )
    .bind(group_id)
    .fetch_all(db)
    .await?;
    if rows.len() >= cfg.prototype_count {
        return Ok(());
    }
    for blob in &rows {
        let proto = FaceSignals { phash: None, embedding: Some(blob.clone()) };
        let Some(pv) = proto.embedding_vec() else { continue };
        match cosine(&incoming, &pv) {
            // Too close to an existing prototype = same look, not a new one.
            Some(s) if s > 1.0 - cfg.prototype_spread => return Ok(()),
            _ => continue,
        }
    }
    sqlx::query("INSERT OR IGNORE INTO person_group_prototypes (group_id, face_id) VALUES (?, ?)")
        .bind(group_id)
        .bind(face_id)
        .execute(db)
        .await?;
    Ok(())
}

/// Best matching group for the incoming face.
///
/// Embedding path (dual gate): `member_best >= match_threshold` AND
/// (`proto_best >= prototype_threshold` or the group has no prototypes yet —
/// legacy groups, which the next join backfills). phash fallback keeps the
/// representative-only comparison.
async fn find_group(db: &Db, signals: &FaceSignals, cfg: &MlFaceConfig) -> Result<Option<i64>> {
    let incoming = signals.embedding_vec();
    let rows = sqlx::query(
        "SELECT f.group_id AS gid, f.phash AS phash, f.embedding AS embedding, \
         (f.id IN (SELECT face_id FROM person_group_prototypes p WHERE p.group_id = f.group_id)) AS is_proto, \
         (f.id = (SELECT representative_face_id FROM person_groups g WHERE g.id = f.group_id)) AS is_rep \
         FROM faces f WHERE f.group_id IS NOT NULL",
    )
    .fetch_all(db)
    .await?;

    if incoming.is_some() {
        let mut member_best: std::collections::HashMap<i64, f32> = std::collections::HashMap::new();
        let mut proto_best: std::collections::HashMap<i64, f32> = std::collections::HashMap::new();

        for row in &rows {
            let gid: i64 = row.try_get("gid")?;
            let is_proto: bool = row.try_get("is_proto").unwrap_or(false);
            let emb: Option<Vec<u8>> = row.try_get("embedding").ok().flatten();
            let Some(blob) = emb else { continue };
            let proto = FaceSignals { phash: None, embedding: Some(blob) };
            let Some(pv) = proto.embedding_vec() else { continue };
            let Some(a) = incoming.as_ref() else { continue };
            let Some(s) = cosine(a, &pv) else { continue };
            let entry = member_best.entry(gid).or_insert(f32::MIN);
            if s > *entry {
                *entry = s;
            }
            if is_proto {
                let entry = proto_best.entry(gid).or_insert(f32::MIN);
                if s > *entry {
                    *entry = s;
                }
            }
        }

        let mut best: Option<(i64, f32)> = None;
        for (gid, member) in &member_best {
            if *member < cfg.match_threshold {
                continue; // loose gate failed
            }
            let proto_ok = match proto_best.get(gid) {
                Some(p) => *p >= cfg.prototype_threshold, // strict gate
                None => true, // no prototypes recorded yet (legacy group)
            };
            if !proto_ok {
                continue;
            }
            if best.map(|(_, s)| *member > s).unwrap_or(true) {
                best = Some((*gid, *member));
            }
        }
        return Ok(best.map(|(gid, _)| gid));
    }

    // phash fallback: representatives only, hamming bound.
    let mut best: Option<(i64, f32)> = None;
    for row in &rows {
        let gid: i64 = row.try_get("gid")?;
        let is_rep: bool = row.try_get("is_rep").unwrap_or(false);
        if !is_rep {
            continue;
        }
        let rep_hash: Option<String> = row.try_get("phash").ok().flatten();
        let Some(h) = &signals.phash else { continue };
        let Some(rep) = rep_hash else { continue };
        if let Some(score) = hamming_distance_hex(&rep, h)
            .filter(|d| *d <= cfg.cluster_threshold)
            .map(|d| 1.0 - d as f32 / 256.0)
        {
            if best.map(|(_, s)| score > s).unwrap_or(true) {
                best = Some((gid, score));
            }
        }
    }
    Ok(best.map(|(gid, _)| gid))
}

/// Recompute every group assignment (used after a model upgrade or a
/// threshold change).
pub async fn recluster_all(db: &Db, cfg: &MlFaceConfig) -> Result<u64> {
    sqlx::query("DELETE FROM person_groups").execute(db).await?;
    sqlx::query("UPDATE faces SET group_id = NULL")
        .execute(db)
        .await?;

    let faces: Vec<(i64, Option<String>, Option<Vec<u8>>)> = sqlx::query_as(
        "SELECT id, phash, embedding FROM faces WHERE phash IS NOT NULL OR embedding IS NOT NULL ORDER BY created_at",
    )
    .fetch_all(db)
    .await?;
    let mut n = 0u64;
    for (face_id, phash, embedding) in faces {
        let signals = FaceSignals { phash, embedding };
        assign_group(db, face_id, &signals, cfg).await?;
        n += 1;
    }
    Ok(n)
}

pub async fn rename_group(db: &Db, id: i64, name: &str) -> Result<()> {
    sqlx::query("UPDATE person_groups SET name = ? WHERE id = ?")
        .bind(name)
        .bind(id)
        .execute(db)
        .await?;
    Ok(())
}

/// Merge group `source` into group `target`.
///
/// The source's dispersed prototypes migrate too (capped at the configured
/// prototype count) — dropping them would leave the target's prototype set
/// blind to the looks the source used to cover, and the strict gate would
/// start rejecting faces that clearly belong to the merged person.
pub async fn merge_groups(db: &Db, source: i64, target: i64, cfg: &MlFaceConfig) -> Result<u64> {
    let res = sqlx::query("UPDATE faces SET group_id = ? WHERE group_id = ?")
        .bind(target)
        .bind(source)
        .execute(db)
        .await?;

    // Carry prototypes over before the group row (and its cascade) disappears.
    sqlx::query(
        "INSERT OR IGNORE INTO person_group_prototypes (group_id, face_id) \
         SELECT ?, face_id FROM person_group_prototypes WHERE group_id = ?",
    )
    .bind(target)
    .bind(source)
    .execute(db)
    .await?;
    // Keep the target's prototype set within bounds: keep the earliest ones.
    sqlx::query(
        "DELETE FROM person_group_prototypes WHERE group_id = ? AND face_id NOT IN \
         (SELECT face_id FROM person_group_prototypes WHERE group_id = ? LIMIT ?)",
    )
    .bind(target)
    .bind(target)
    .bind(cfg.prototype_count as i64)
    .execute(db)
    .await?;

    sqlx::query("DELETE FROM person_groups WHERE id = ?")
        .bind(source)
        .execute(db)
        .await?;
    Ok(res.rows_affected())
}

/// Detach a single face from its group (it opens its own group on the next pass).
pub async fn unassign_face(db: &Db, face_id: i64) -> Result<()> {
    sqlx::query("UPDATE faces SET group_id = NULL WHERE id = ?")
        .bind(face_id)
        .execute(db)
        .await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hamming_threshold_is_sane() {
        assert_eq!(hamming_distance_hex("00", "01"), Some(1));
    }
}
