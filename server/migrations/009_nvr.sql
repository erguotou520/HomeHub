-- NVR: camera registry + recorded segments (docs/design/nvr.md §3)

CREATE TABLE IF NOT EXISTS cameras (
  id            INTEGER PRIMARY KEY,
  name          TEXT NOT NULL UNIQUE,          -- 'home' (= recordings directory segment)
  label         TEXT,                          -- display name, e.g. '客厅'
  rtsp_url      TEXT NOT NULL,                 -- rtsp://user:pass@host/stream1
  enabled       INTEGER NOT NULL DEFAULT 1,
  record_stream INTEGER NOT NULL DEFAULT 1,    -- 1=main 2=sub
  record_audio  INTEGER NOT NULL DEFAULT 0,    -- 0=off (-an) 1=AAC re-encode 32kbps (MP4 can't hold G711)
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS nvr_segments (
  id           INTEGER PRIMARY KEY,
  camera_id    INTEGER NOT NULL REFERENCES cameras(id) ON DELETE CASCADE,
  path         TEXT NOT NULL UNIQUE,           -- relative to data_dir: recordings/2026-09-30/14/home/30.59.mp4
  start_time   INTEGER NOT NULL,               -- unix seconds
  end_time     INTEGER NOT NULL,
  duration     REAL NOT NULL,                  -- seconds
  size_bytes   INTEGER NOT NULL,
  video_codec  TEXT,
  audio_codec  TEXT,
  audio_rate   INTEGER,
  has_audio    INTEGER,
  width        INTEGER,
  height       INTEGER,
  created_at   INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_nvr_seg_cam_time ON nvr_segments(camera_id, start_time);
CREATE INDEX IF NOT EXISTS idx_nvr_seg_end_time ON nvr_segments(end_time);
