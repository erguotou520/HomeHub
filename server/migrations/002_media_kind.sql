-- Album v2: support video assets next to photos.

ALTER TABLE photo_assets ADD COLUMN media_kind TEXT NOT NULL DEFAULT 'photo'; -- photo | video
ALTER TABLE photo_assets ADD COLUMN duration_ms INTEGER;
ALTER TABLE photo_assets ADD COLUMN video_codec TEXT;

CREATE INDEX IF NOT EXISTS idx_photos_kind ON photo_assets(media_kind, status);
