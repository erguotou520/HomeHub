-- HomeHub initial schema (SQLite)
-- Replaces the legacy PostgreSQL users/shares schema.

-- ─── Directory registry (mirror of config.yaml, kept for fast joins) ────────
CREATE TABLE IF NOT EXISTS dirs (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    name         TEXT    NOT NULL UNIQUE,
    path         TEXT    NOT NULL,
    marks        TEXT    NOT NULL DEFAULT '',  -- csv: album|video|music|document|none
    ignore_rules TEXT    NOT NULL DEFAULT '',  -- csv of relative path patterns
    enabled      INTEGER NOT NULL DEFAULT 1,
    created_at   INTEGER NOT NULL
);

-- ─── Photo assets ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS photo_assets (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    dir_id       INTEGER NOT NULL REFERENCES dirs(id) ON DELETE CASCADE,
    rel_path     TEXT    NOT NULL,
    fingerprint  TEXT    NOT NULL DEFAULT '',   -- "mtime:size" change detector
    file_hash    TEXT,                          -- SHA-256 of the source bytes
    pixel_hash   TEXT,                          -- perceptual hash of decoded pixels
    size         INTEGER NOT NULL DEFAULT 0,
    mtime        INTEGER NOT NULL DEFAULT 0,
    taken_at     INTEGER,
    width        INTEGER,
    height       INTEGER,
    orientation  INTEGER NOT NULL DEFAULT 1,
    gps_lat      REAL,
    gps_lng      REAL,
    camera_make  TEXT,
    camera_model TEXT,
    status       TEXT    NOT NULL DEFAULT 'ok', -- ok | processing | trashed
    compressed   INTEGER NOT NULL DEFAULT 0,
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    UNIQUE (dir_id, rel_path)
);
CREATE INDEX IF NOT EXISTS idx_photos_dir      ON photo_assets(dir_id);
CREATE INDEX IF NOT EXISTS idx_photos_taken    ON photo_assets(taken_at);
CREATE INDEX IF NOT EXISTS idx_photos_status   ON photo_assets(status);
CREATE INDEX IF NOT EXISTS idx_photos_filehash ON photo_assets(file_hash);
CREATE INDEX IF NOT EXISTS idx_photos_pixelhash ON photo_assets(pixel_hash);

-- ─── Tags (object + scene) ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS photo_tags (
    photo_id   INTEGER NOT NULL REFERENCES photo_assets(id) ON DELETE CASCADE,
    tag        TEXT    NOT NULL,
    kind       TEXT    NOT NULL,   -- object | scene
    confidence REAL    NOT NULL DEFAULT 0,
    PRIMARY KEY (photo_id, kind, tag)
);
CREATE INDEX IF NOT EXISTS idx_tags_tag ON photo_tags(tag, kind);

-- ─── Faces / person groups ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS faces (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    photo_id INTEGER NOT NULL REFERENCES photo_assets(id) ON DELETE CASCADE,
    box_x    REAL NOT NULL,
    box_y    REAL NOT NULL,
    box_w    REAL NOT NULL,
    box_h    REAL NOT NULL,
    phash    TEXT,
    group_id INTEGER REFERENCES person_groups(id) ON DELETE SET NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_faces_photo ON faces(photo_id);
CREATE INDEX IF NOT EXISTS idx_faces_group ON faces(group_id);

CREATE TABLE IF NOT EXISTS person_groups (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    name                   TEXT,
    representative_face_id INTEGER,
    created_at             INTEGER NOT NULL
);

-- ─── Persistent task queue ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tasks (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    kind         TEXT    NOT NULL,
    payload      TEXT    NOT NULL,
    priority     INTEGER NOT NULL DEFAULT 0,
    status       TEXT    NOT NULL DEFAULT 'pending', -- pending|running|done|failed
    attempts     INTEGER NOT NULL DEFAULT 0,
    error        TEXT,
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    scheduled_at INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status, priority DESC, id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_tasks_dedup
    ON tasks(kind, payload) WHERE status IN ('pending', 'running');

-- ─── Trash (soft delete) ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS trash_entries (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    dir_id      INTEGER,
    dir_name    TEXT NOT NULL DEFAULT '',
    rel_path    TEXT NOT NULL,
    trash_path  TEXT NOT NULL,
    is_dir      INTEGER NOT NULL DEFAULT 0,
    size        INTEGER NOT NULL DEFAULT 0,
    trashed_at  INTEGER NOT NULL,
    purged_due  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_trash_due ON trash_entries(purged_due);

-- ─── WireGuard identity + audit ───────────────────────────────────────────
CREATE TABLE IF NOT EXISTS wg_peers (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    public_key TEXT UNIQUE,
    name       TEXT NOT NULL,
    tunnel_ip  TEXT NOT NULL,
    first_seen INTEGER NOT NULL,
    last_seen  INTEGER NOT NULL,
    enabled    INTEGER NOT NULL DEFAULT 1,
    source     TEXT NOT NULL DEFAULT 'manual'   -- manual | wg-show
);
CREATE INDEX IF NOT EXISTS idx_peers_ip ON wg_peers(tunnel_ip);

CREATE TABLE IF NOT EXISTS audit_logs (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    peer_id    INTEGER,
    peer_ip    TEXT,
    method     TEXT NOT NULL,
    path       TEXT NOT NULL,
    status     INTEGER NOT NULL,
    bytes      INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_peer ON audit_logs(peer_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_logs(created_at);

-- ─── Alerts ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS alerts (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    level       TEXT NOT NULL,          -- info | warn | error
    kind        TEXT NOT NULL,          -- disk | task | sqlite | originals | trash
    message     TEXT NOT NULL,
    created_at  INTEGER NOT NULL,
    resolved_at INTEGER,
    notified    INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_alerts_kind ON alerts(kind, created_at);

-- ─── File index (for FTS search across every registered dir) ──────────────
CREATE TABLE IF NOT EXISTS file_index (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    dir_id    INTEGER NOT NULL REFERENCES dirs(id) ON DELETE CASCADE,
    rel_path  TEXT NOT NULL,
    name      TEXT NOT NULL,
    is_dir    INTEGER NOT NULL DEFAULT 0,
    size      INTEGER NOT NULL DEFAULT 0,
    mtime     INTEGER NOT NULL DEFAULT 0,
    fingerprint TEXT NOT NULL DEFAULT '',
    UNIQUE (dir_id, rel_path)
);
CREATE INDEX IF NOT EXISTS idx_fileindex_dir ON file_index(dir_id);

-- ─── FTS5 search index (app maintained) ───────────────────────────────────
CREATE VIRTUAL TABLE IF NOT EXISTS search_index USING fts5(
    ftype  UNINDEXED,
    ref_id UNINDEXED,
    title,
    body,
    tokenize = 'unicode61 remove_diacritics 2'
);

-- ─── Runtime settings (task/ml/compression/... overrides) ─────────────────
CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
