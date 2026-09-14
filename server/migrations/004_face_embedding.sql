-- Face embeddings from the recognition model (SFace / MobileFaceNet, 128-d f32).
-- NULL for faces detected before the embedder existed (re-detection backfills).
ALTER TABLE faces ADD COLUMN embedding BLOB;
