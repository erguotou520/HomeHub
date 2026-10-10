-- NVR: per-camera sub-stream RTSP URL.
--
-- Until now only `rtsp_url` (the main stream) was stored, and everything —
-- recording, live preview, mobile stream switching — guessed the sub stream by
-- rewriting `/stream1` → `/stream2` in that single URL. That works for
-- Hikvision-style paths but leaves the loop open: the admin can neither see
-- nor set what the sub stream actually is, and `cameras.record_stream` was
-- stored but never consulted.
--
-- NULL / empty means "derive from rtsp_url" (the old rewrite), so existing
-- rows keep working unchanged.
ALTER TABLE cameras ADD COLUMN rtsp_sub_url TEXT;

-- The stream the live preview relay pulls from: 'sub' by default (cheaper,
-- what a phone preview wants), 'main' when the user asks for full quality.
ALTER TABLE cameras ADD COLUMN preview_stream TEXT NOT NULL DEFAULT 'sub';
