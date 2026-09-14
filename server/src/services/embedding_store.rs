//! In-memory multi-vector embedding index for semantic search.
//!
//! SQLite (`photo_embeddings`) is the persistence layer only. At startup (and
//! on every upsert from the ClipEmbed worker) vectors are mirrored here; queries
//! are a plain dot-product scan — at home-album scale (≤100k photos × 1024-d
//! ≈ 400MB, typically far less) this stays in the tens of milliseconds on a
//! single core without any vector-DB dependency.
//!
//! One photo may own several vectors (a video gets one embedding per detected
//! scene). Search aggregates the MAX cosine over all of a photo's vectors, so
//! a multi-scene clip is findable by any single scene.

use std::collections::HashMap;
use std::sync::RwLock;

/// Chinese-CLIP RN50 output dimension (matches the exported ONNX graph).
pub const EMBED_DIM: usize = 1024;
/// Model identifier persisted alongside every embedding row.
pub const MODEL_NAME: &str = "cn_clip_rn50";

/// All embeddings are L2-normalised at insert so a dot product is the cosine
/// similarity.
pub struct EmbeddingStore {
    inner: RwLock<Inner>,
}

struct Inner {
    dim: usize,
    /// Flat row-major matrix: row `r` occupies `[r*dim .. (r+1)*dim]`.
    data: Vec<f32>,
    /// Owning photo per row; 0 marks a recycled (tombstoned) row.
    row_photo: Vec<i64>,
    /// Live rows per photo, ordered by scene index.
    rows_of: HashMap<i64, Vec<usize>>,
    /// Row indices freed by removals, reused on later inserts.
    free: Vec<usize>,
}

impl EmbeddingStore {
    pub fn new(dim: usize) -> Self {
        Self {
            inner: RwLock::new(Inner {
                dim,
                data: Vec::new(),
                row_photo: Vec::new(),
                rows_of: HashMap::new(),
                free: Vec::new(),
            }),
        }
    }

    pub fn dim(&self) -> usize {
        self.inner.read().map(|i| i.dim).unwrap_or(0)
    }

    /// Number of indexed photos/videos (not vectors).
    pub fn len(&self) -> usize {
        self.inner.read().map(|i| i.rows_of.len()).unwrap_or(0)
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Warm-start the store from SQLite at boot. SQLite is the persistence
    /// layer; the hot search path only ever touches this in-memory mirror.
    pub async fn preload(&self, db: &crate::db::Db) -> anyhow::Result<usize> {
        let rows: Vec<(i64, i64, Vec<u8>)> = sqlx::query_as(
            "SELECT photo_id, scene, embedding FROM photo_embeddings \
             WHERE model = ? AND dimension = ? AND dtype = 'f32' \
             ORDER BY photo_id, scene",
        )
        .bind(MODEL_NAME)
        .bind(self.dim() as i64)
        .fetch_all(db)
        .await?;
        let mut loaded = 0usize;
        for (photo_id, _scene, blob) in rows {
            let emb: Vec<f32> = blob
                .chunks_exact(4)
                .map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]]))
                .collect();
            match self.push_scene(photo_id, &emb) {
                Ok(true) => loaded += 1,
                Ok(false) => {} // replaced an existing scene slot
                Err(e) => {
                    tracing::warn!("preload skipped photo {photo_id}: {e}");
                }
            }
        }
        Ok(loaded)
    }

    /// Insert/replace the embedding of scene `scene` for `photo_id`.
    /// Scenes must be written in order (0, 1, 2, …) — appending out of order
    /// is rejected. Returns Ok(true) when a new row was created.
    pub fn upsert(&self, photo_id: i64, scene: usize, embedding: &[f32]) -> Result<bool, String> {
        if embedding.len() != self.dim() {
            return Err(format!(
                "embedding dim {} != store dim {}",
                embedding.len(),
                self.dim()
            ));
        }
        let norm = embedding.iter().map(|x| x * x).sum::<f32>().sqrt();
        if norm <= 0.0 {
            return Err("zero-norm embedding".into());
        }
        let mut inner = self.inner.write().map_err(|e| e.to_string())?;
        let dim = inner.dim;
        // Avoid holding the `rows_of` borrow across `data`/`free` mutations.
        let existing = inner.rows_of.get(&photo_id).map_or(0, |r| r.len());
        if scene > existing {
            return Err(format!(
                "scene {scene} out of order (photo {photo_id} has {existing} scenes)"
            ));
        }
        if scene < existing {
            let row = inner.rows_of[&photo_id][scene];
            let start = row * dim;
            inner.data[start..start + dim].copy_from_slice(embedding);
            for v in &mut inner.data[start..start + dim] {
                *v /= norm;
            }
            Ok(false)
        } else {
            let row = match inner.free.pop() {
                Some(r) => {
                    let start = r * dim;
                    inner.data[start..start + dim]
                        .iter_mut()
                        .zip(embedding)
                        .for_each(|(v, e)| *v = e / norm);
                    inner.row_photo[r] = photo_id;
                    r
                }
                None => {
                    let row = inner.row_photo.len();
                    inner.data.extend(embedding.iter().map(|v| v / norm));
                    inner.row_photo.push(photo_id);
                    row
                }
            };
            inner.rows_of.entry(photo_id).or_default().push(row);
            Ok(true)
        }
    }

    /// Append-style helper used by preload (no ordering guarantee needed).
    fn push_scene(&self, photo_id: i64, embedding: &[f32]) -> Result<bool, String> {
        let next = {
            let inner = self.inner.read().map_err(|e| e.to_string())?;
            inner.rows_of.get(&photo_id).map(|r| r.len()).unwrap_or(0)
        };
        self.upsert(photo_id, next, embedding)
    }

    /// Drop ALL vectors of a photo (call before re-embedding a video whose
    /// scene count may have changed).
    pub fn remove_photo(&self, photo_id: i64) -> Result<(), String> {
        let mut inner = self.inner.write().map_err(|e| e.to_string())?;
        if let Some(rows) = inner.rows_of.remove(&photo_id) {
            let dim = inner.dim;
            for row in rows {
                // Blank the data so a stale row can never match a query.
                let start = row * dim;
                inner.data[start..start + dim].fill(0.0);
                inner.row_photo[row] = 0;
                inner.free.push(row);
            }
        }
        Ok(())
    }

    /// Top-K photos by MAX cosine over each photo's scene vectors.
    /// `allowed` filters candidates (e.g. photos passing the EXIF/year filter);
    /// `None` scans everything.
    pub fn search(
        &self,
        query: &[f32],
        top_k: usize,
        allowed: Option<&HashMap<i64, ()>>,
    ) -> Result<Vec<(i64, f32)>, String> {
        let inner = self.inner.read().map_err(|e| e.to_string())?;
        if query.len() != inner.dim {
            return Err(format!(
                "query dim {} != store dim {}",
                query.len(),
                inner.dim
            ));
        }
        let qnorm = query.iter().map(|x| x * x).sum::<f32>().sqrt();
        if qnorm <= 0.0 {
            return Err("zero-norm query".into());
        }
        let dim = inner.dim;
        // photo -> best (max) similarity over its scene vectors.
        let mut best_of: HashMap<i64, f32> = HashMap::with_capacity(inner.rows_of.len());
        for (row, &photo_id) in inner.row_photo.iter().enumerate() {
            if photo_id == 0 {
                continue; // recycled row
            }
            if let Some(allowed) = allowed {
                if !allowed.contains_key(&photo_id) {
                    continue;
                }
            }
            let start = row * dim;
            let dot = inner.data[start..start + dim]
                .iter()
                .zip(query)
                .map(|(a, b)| a * b)
                .sum::<f32>()
                / qnorm;
            let entry = best_of.entry(photo_id).or_insert(f32::MIN);
            if dot > *entry {
                *entry = dot;
            }
        }
        let mut scored: Vec<(i64, f32)> = best_of.into_iter().collect();
        scored.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
        scored.truncate(top_k);
        Ok(scored)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn upsert_normalize_and_search() {
        let store = EmbeddingStore::new(3);
        store.upsert(1, 0, &[1.0, 0.0, 0.0]).unwrap();
        store.upsert(2, 0, &[0.0, 5.0, 0.0]).unwrap(); // unnormalised on purpose
        store.upsert(3, 0, &[0.0, 0.0, 2.0]).unwrap();

        let hits = store.search(&[1.0, 0.0, 0.0], 3, None).unwrap();
        assert_eq!(hits[0], (1, 1.0));
        // photos 2 & 3 tie at 0.0 — order between equal scores is unspecified.
        assert!(hits[1..].iter().all(|(_, s)| s.abs() < 1e-6));

        // replace rewrites the row; photo 2 ties at 1.0 after this, so check
        // membership rather than ordering.
        store.upsert(1, 0, &[0.0, 1.0, 0.0]).unwrap();
        let hits = store.search(&[0.0, 1.0, 0.0], 2, None).unwrap();
        assert!(hits
            .iter()
            .any(|(id, s)| *id == 1 && (*s - 1.0).abs() < 1e-6));
    }

    #[test]
    fn multi_scene_aggregates_max() {
        let store = EmbeddingStore::new(2);
        // video 7: scene 0 is sky, scene 1 is a face.
        store.upsert(7, 0, &[1.0, 0.0]).unwrap();
        store.upsert(7, 1, &[0.0, 1.0]).unwrap();
        // photo 8 only matches "sky" weakly.
        store.upsert(8, 0, &[0.9, 0.1]).unwrap();

        // Query "face": video found via its best scene, ranking above photo 8.
        let hits = store.search(&[0.0, 1.0], 5, None).unwrap();
        assert_eq!(hits[0].0, 7);
        assert!((hits[0].1 - 1.0).abs() < 1e-6);
        assert_eq!(hits[1].0, 8);

        // Query "sky": same video still found via the other scene.
        let hits = store.search(&[1.0, 0.0], 5, None).unwrap();
        assert_eq!(hits[0].0, 7);
    }

    #[test]
    fn remove_photo_and_scene_ordering() {
        let store = EmbeddingStore::new(2);
        store.upsert(5, 0, &[1.0, 0.0]).unwrap();
        store.upsert(5, 1, &[0.0, 1.0]).unwrap();
        assert_eq!(store.len(), 1);
        store.remove_photo(5).unwrap();
        assert_eq!(store.len(), 0);
        assert!(store.search(&[1.0, 0.0], 5, None).unwrap().is_empty());

        // out-of-order scene rejected
        assert!(store.upsert(9, 2, &[1.0, 0.0]).is_err());
        // rows are recycled after removal
        store.upsert(9, 0, &[1.0, 0.0]).unwrap();
        assert_eq!(store.search(&[1.0, 0.0], 5, None).unwrap().len(), 1);
    }

    #[test]
    fn dim_mismatch_rejected() {
        let store = EmbeddingStore::new(4);
        assert!(store.upsert(1, 0, &[1.0, 0.0, 0.0]).is_err());
        assert!(store.search(&[1.0], 1, None).is_err());
    }

    #[test]
    fn allowed_filter_narrows_candidates() {
        let store = EmbeddingStore::new(2);
        store.upsert(1, 0, &[1.0, 0.0]).unwrap();
        store.upsert(2, 0, &[0.9, 0.1]).unwrap();
        let mut allowed = HashMap::new();
        allowed.insert(2, ());
        let hits = store.search(&[1.0, 0.0], 5, Some(&allowed)).unwrap();
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].0, 2);
    }
}
