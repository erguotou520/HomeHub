-- 007 rebuilt `photo_embeddings` to add the `scene` column, but dropping the old
-- table also dropped `idx_photo_embeddings_model` (created by 005), and the
-- rebuilt table never got an index back. Without it the only read path —
-- `EmbeddingStore::preload` in services/embedding_store.rs — degrades to a full
-- table scan plus a temp B-tree sort:
--
--   SELECT photo_id, scene, embedding FROM photo_embeddings
--    WHERE model = ? AND dimension = ? AND dtype = 'f32'
--    ORDER BY photo_id, scene
--
-- Key order mirrors that query: the three equality predicates lead, and the
-- trailing (photo_id, scene) matches the ORDER BY exactly, so SQLite reads rows
-- already in order instead of building a temp B-tree. Leading with `model` also
-- keeps the WHERE prunable if a future model swap leaves several models
-- coexisting in one table (see 005's note on model/dimension/dtype metadata).
CREATE INDEX IF NOT EXISTS idx_photo_embeddings_model_scene
    ON photo_embeddings(model, dimension, dtype, photo_id, scene);
