//! Face clustering: group detected faces into `person_groups`.
//!
//! Lightweight approach (see PRD open question #2): each face crop gets a
//! 16x16 perceptual hash; a new face joins the group whose representative is
//! within `cluster_threshold` hamming distance, otherwise it opens a new group.

use anyhow::Result;
use sqlx::Row;

use crate::config::MlFaceConfig;
use crate::db::Db;
use crate::services::hash::hamming_distance_hex;

/// Assign a face hash to a person group, creating one when needed.
pub async fn assign_group(db: &Db, face_id: i64, hash: &str, cfg: &MlFaceConfig) -> Result<i64> {
    if let Some(group_id) = find_group(db, hash, cfg).await? {
        sqlx::query("UPDATE faces SET group_id = ? WHERE id = ?")
            .bind(group_id)
            .bind(face_id)
            .execute(db)
            .await?;
        return Ok(group_id);
    }

    let now = crate::db::now();
    sqlx::query("INSERT INTO person_groups (name, representative_face_id, created_at) VALUES (NULL, ?, ?)")
        .bind(face_id)
        .bind(now)
        .execute(db)
        .await?;
    let id: (i64,) = sqlx::query_as("SELECT last_insert_rowid()")
        .fetch_one(db)
        .await?;
    sqlx::query("UPDATE faces SET group_id = ? WHERE id = ?")
        .bind(id.0)
        .bind(face_id)
        .execute(db)
        .await?;
    Ok(id.0)
}

async fn find_group(db: &Db, hash: &str, cfg: &MlFaceConfig) -> Result<Option<i64>> {
    let rows = sqlx::query(
        "SELECT g.id AS id, f.phash AS phash FROM person_groups g \
         LEFT JOIN faces f ON f.id = g.representative_face_id",
    )
    .fetch_all(db)
    .await?;

    let mut best: Option<(i64, u32)> = None;
    for row in rows {
        let gid: i64 = row.try_get("id")?;
        let rep: Option<String> = row.try_get("phash").ok().flatten();
        let Some(rep) = rep else { continue };
        let Some(dist) = hamming_distance_hex(&rep, hash) else {
            continue;
        };
        if dist <= cfg.cluster_threshold && best.map(|(_, d)| dist < d).unwrap_or(true) {
            best = Some((gid, dist));
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

    let faces: Vec<(i64, Option<String>)> =
        sqlx::query_as("SELECT id, phash FROM faces WHERE phash IS NOT NULL ORDER BY created_at")
            .fetch_all(db)
            .await?;
    let mut n = 0u64;
    for (face_id, hash) in faces {
        let Some(hash) = hash else { continue };
        assign_group(db, face_id, &hash, cfg).await?;
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
pub async fn merge_groups(db: &Db, source: i64, target: i64) -> Result<u64> {
    let res = sqlx::query("UPDATE faces SET group_id = ? WHERE group_id = ?")
        .bind(target)
        .bind(source)
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
