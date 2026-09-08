//! Directory registry: the single source of truth for every NAS location.
//!
//! Config YAML is authoritative; SQLite keeps a mirror so queries can join on
//! `dir_id`. Runtime CRUD through the admin API updates both.

use std::path::{Path, PathBuf};
use std::sync::{Arc, RwLock};

use crate::config::{default_ignore_rules, Config, DirConfig};
use crate::db::Db;
use crate::models::dir::{DirMark, DirRecord, DirStats};
use crate::services::paths::safe_join;

use super::paths::normalize_rel;

#[derive(Clone)]
pub struct DirRegistry {
    db: Db,
    dirs: Arc<RwLock<Vec<DirRecord>>>,
}

impl DirRegistry {
    /// Sync `config.yaml` into SQLite and load the registry into memory.
    pub async fn bootstrap(db: Db, config: &Config) -> anyhow::Result<Self> {
        for entry in &config.dirs {
            let marks = DirMark::to_csv(&entry.marks_set());
            let ignore = if entry.ignore.is_empty() {
                default_ignore_rules().join(",")
            } else {
                entry.ignore.join(",")
            };
            sqlx::query(
                "INSERT INTO dirs (name, path, marks, ignore_rules, enabled, created_at) \
                 VALUES (?, ?, ?, ?, ?, ?) \
                 ON CONFLICT(name) DO UPDATE SET path = excluded.path, \
                     marks = excluded.marks, ignore_rules = excluded.ignore_rules",
            )
            .bind(&entry.name)
            .bind(&entry.path)
            .bind(&marks)
            .bind(&ignore)
            .bind(if entry.enabled { 1 } else { 0 })
            .bind(crate::db::now())
            .execute(&db)
            .await?;
        }

        let registry = Self {
            db,
            dirs: Arc::new(RwLock::new(Vec::new())),
        };
        registry.reload().await?;
        Ok(registry)
    }

    pub async fn reload(&self) -> anyhow::Result<()> {
        let rows: Vec<DirRecord> = sqlx::query_as(
            "SELECT id, name, path, marks, ignore_rules, enabled, created_at FROM dirs ORDER BY name",
        )
        .fetch_all(&self.db)
        .await?;
        let mut guard = self.dirs.write().map_err(|e| anyhow::anyhow!("{e}"))?;
        *guard = rows;
        Ok(())
    }

    pub fn all(&self) -> Vec<DirRecord> {
        self.dirs.read().map(|g| g.clone()).unwrap_or_default()
    }

    pub fn enabled(&self) -> Vec<DirRecord> {
        self.all().into_iter().filter(|d| d.is_enabled()).collect()
    }

    pub fn with_mark(&self, mark: DirMark) -> Vec<DirRecord> {
        self.enabled()
            .into_iter()
            .filter(|d| d.has_mark(mark))
            .collect()
    }

    pub fn album_dirs(&self) -> Vec<DirRecord> {
        self.with_mark(DirMark::Album)
    }

    pub fn by_id(&self, id: i64) -> Option<DirRecord> {
        self.all().into_iter().find(|d| d.id == id)
    }

    pub fn by_name(&self, name: &str) -> Option<DirRecord> {
        self.all().into_iter().find(|d| d.name == name)
    }

    /// Resolve a `<dir-name>/<rel-path>` virtual path to a real filesystem path.
    pub fn resolve(&self, dir_name: &str, rel_path: &str) -> Option<(DirRecord, PathBuf)> {
        let dir = self.by_name(dir_name)?;
        let full = safe_join(Path::new(&dir.path), rel_path)?;
        Some((dir, full))
    }

    /// Same as [`DirRegistry::resolve`] but keyed by directory id.
    pub fn resolve_id(&self, dir_id: i64, rel_path: &str) -> Option<(DirRecord, PathBuf)> {
        let dir = self.by_id(dir_id)?;
        let full = safe_join(Path::new(&dir.path), rel_path)?;
        Some((dir, full))
    }

    /// True when a relative path hits one of the directory ignore rules.
    pub fn is_ignored(&self, dir: &DirRecord, rel_path: &str) -> bool {
        let rel = normalize_rel(rel_path);
        if rel.is_empty() {
            return false;
        }
        dir.ignore_list().iter().any(|rule| {
            let rule = rule.trim().trim_end_matches('/');
            if rule.is_empty() {
                return false;
            }
            rel == rule
                || rel.starts_with(&format!("{}/", rule))
                || rel.split('/').any(|part| part == rule)
        })
    }

    // ───────────────────────────── CRUD ────────────────────────────────

    pub async fn create(&self, cfg: &DirConfig) -> anyhow::Result<DirRecord> {
        if self.by_name(&cfg.name).is_some() {
            anyhow::bail!("directory '{}' already exists", cfg.name);
        }
        let marks = DirMark::to_csv(&cfg.marks_set());
        let ignore = if cfg.ignore.is_empty() {
            default_ignore_rules().join(",")
        } else {
            cfg.ignore.join(",")
        };
        // RETURNING keeps id retrieval on the same connection — a separate
        // last_insert_rowid() via the pool can read a different connection's value.
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO dirs (name, path, marks, ignore_rules, enabled, created_at) \
             VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
        )
        .bind(&cfg.name)
        .bind(&cfg.path)
        .bind(marks)
        .bind(ignore)
        .bind(if cfg.enabled { 1 } else { 0 })
        .bind(crate::db::now())
        .fetch_one(&self.db)
        .await?;
        self.reload().await?;
        self.by_id(id)
            .ok_or_else(|| anyhow::anyhow!("directory vanished after insert"))
    }

    pub async fn update(&self, id: i64, cfg: &DirConfig) -> anyhow::Result<DirRecord> {
        let marks = DirMark::to_csv(&cfg.marks_set());
        let ignore = cfg.ignore.join(",");
        let affected = sqlx::query(
            "UPDATE dirs SET name = ?, path = ?, marks = ?, ignore_rules = ?, enabled = ? WHERE id = ?",
        )
        .bind(&cfg.name)
        .bind(&cfg.path)
        .bind(marks)
        .bind(ignore)
        .bind(if cfg.enabled { 1 } else { 0 })
        .bind(id)
        .execute(&self.db)
        .await?;
        if affected.rows_affected() == 0 {
            anyhow::bail!("directory {} not found", id);
        }
        self.reload().await?;
        self.by_id(id)
            .ok_or_else(|| anyhow::anyhow!("directory vanished after update"))
    }

    pub async fn set_enabled(&self, id: i64, enabled: bool) -> anyhow::Result<()> {
        sqlx::query("UPDATE dirs SET enabled = ? WHERE id = ?")
            .bind(if enabled { 1 } else { 0 })
            .bind(id)
            .execute(&self.db)
            .await?;
        self.reload().await
    }

    /// Remove a directory from the registry. Its indexed rows go away as well
    /// (the files themselves are untouched).
    pub async fn delete(&self, id: i64) -> anyhow::Result<()> {
        sqlx::query("DELETE FROM dirs WHERE id = ?")
            .bind(id)
            .execute(&self.db)
            .await?;
        self.reload().await
    }

    /// Registry rows enriched with per-directory statistics.
    pub async fn stats(&self) -> anyhow::Result<Vec<DirStats>> {
        let rows: Vec<(i64, i64, i64, i64, i64)> = sqlx::query_as(
            "SELECT d.id, \
                    (SELECT COUNT(*) FROM file_index f WHERE f.dir_id = d.id), \
                    (SELECT COUNT(*) FROM photo_assets p WHERE p.dir_id = d.id AND p.status != 'trashed'), \
                    (SELECT COUNT(DISTINCT t.photo_id) FROM photo_tags t \
                        JOIN photo_assets p2 ON p2.id = t.photo_id WHERE p2.dir_id = d.id), \
                    (SELECT COALESCE(SUM(p3.size), 0) FROM photo_assets p3 WHERE p3.dir_id = d.id) \
             FROM dirs d ORDER BY d.name",
        )
        .fetch_all(&self.db)
        .await?;

        let mut stats = Vec::new();
        for dir in self.all() {
            let (file_count, photo_count, tagged_count, total_bytes) = rows
                .iter()
                .find(|r| r.0 == dir.id)
                .map(|r| (r.1, r.2, r.3, r.4))
                .unwrap_or((0, 0, 0, 0));
            stats.push(DirStats {
                id: dir.id,
                name: dir.name.clone(),
                path: dir.path.clone(),
                marks: dir.marks_vec(),
                ignore_rules: dir.ignore_list(),
                enabled: dir.is_enabled(),
                file_count,
                photo_count,
                tagged_count,
                bytes_saved: 0,
                total_bytes,
            });
        }
        Ok(stats)
    }
}
