-- Scene-aware embeddings: one row per detected scene of a video (photos
-- always use scene 0). Search aggregates max cosine over all scenes of a
-- photo/video, so a multi-scene clip is findable by any of its scenes.
--
-- SQLite cannot DROP a PRIMARY KEY, and the 005 schema's PK (photo_id, model)
-- caps every photo at one row — so rebuild the table with the composite
-- (photo_id, model, scene) PK instead of altering it.
CREATE TABLE IF NOT EXISTS photo_embeddings_v2 (
    photo_id  INTEGER NOT NULL REFERENCES photo_assets(id) ON DELETE CASCADE,
    model     TEXT    NOT NULL,
    dimension INTEGER NOT NULL,
    dtype     TEXT    NOT NULL DEFAULT 'f32',
    embedding BLOB    NOT NULL,
    scene     INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (photo_id, model, scene)
);
-- Existing rows predate scene detection and are photos, so they backfill as scene 0.
INSERT INTO photo_embeddings_v2 SELECT photo_id, model, dimension, dtype, embedding, 0
  FROM photo_embeddings;
DROP TABLE photo_embeddings;
ALTER TABLE photo_embeddings_v2 RENAME TO photo_embeddings;
