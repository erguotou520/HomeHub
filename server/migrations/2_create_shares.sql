CREATE TABLE IF NOT EXISTS shares (
    id              SERIAL PRIMARY KEY,
    user_id         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    file_path       TEXT NOT NULL,
    app_type        VARCHAR(32) NOT NULL,
    token           VARCHAR(64) NOT NULL UNIQUE,
    expires_at      TIMESTAMPTZ,
    burn_after_read BOOLEAN NOT NULL DEFAULT FALSE,
    max_views       INTEGER,
    view_count      INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_shares_token ON shares(token);
CREATE INDEX IF NOT EXISTS idx_shares_user_id ON shares(user_id);
