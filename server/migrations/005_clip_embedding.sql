-- Semantic image embeddings (Chinese-CLIP RN50, 1024-d) for natural-language
-- photo search. Standalone table with model/dimension/dtype metadata so a
-- future model swap needs no schema migration; SQLite is the persistence
-- layer only — the hot search path scans a contiguous in-memory store.
CREATE TABLE IF NOT EXISTS photo_embeddings (
    photo_id  INTEGER NOT NULL REFERENCES photo_assets(id) ON DELETE CASCADE,
    model     TEXT    NOT NULL,
    dimension INTEGER NOT NULL,
    dtype     TEXT    NOT NULL DEFAULT 'f32',
    embedding BLOB    NOT NULL,
    PRIMARY KEY (photo_id, model)
);
CREATE INDEX IF NOT EXISTS idx_photo_embeddings_model ON photo_embeddings(model);
