-- Reverse-geocode cache: map cluster clicks resolve a place name through an
-- external provider; results are cached so repeat lookups never re-hit the
-- API. Keys are coordinates rounded to 1e-4 degrees (~11 m), which matches
-- the aggregation precision well enough for photo locations.

CREATE TABLE IF NOT EXISTS geo_places (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    lat_key INTEGER NOT NULL,
    lng_key INTEGER NOT NULL,
    label TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE (lat_key, lng_key)
);

CREATE INDEX IF NOT EXISTS idx_geo_places_key ON geo_places (lat_key, lng_key);
