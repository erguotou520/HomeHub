-- NVR: motion detection metadata for recorded segments (Frigate-style
-- event timeline). `motion` 0 = unknown (legacy segments), 1 = still scene,
-- 2 = activity detected. `motion_score` is the fraction of frames flagged
-- as scenes by the scdet filter (0.0..1.0).
ALTER TABLE nvr_segments ADD COLUMN motion INTEGER NOT NULL DEFAULT 0;
ALTER TABLE nvr_segments ADD COLUMN motion_score REAL NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_nvr_seg_motion ON nvr_segments(camera_id, motion, start_time);
