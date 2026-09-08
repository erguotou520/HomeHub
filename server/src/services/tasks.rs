//! Persistent task queue with resource control.
//!
//! * queue lives in SQLite, so it survives restarts (`running` -> `pending` on boot)
//! * per-kind concurrency: CPU bound = 1 by default, IO bound = 2
//! * global rate limit (files/second)
//! * work window: heavy tasks only run at full speed during off-peak hours,
//!   unless the task carries a high priority (freshly uploaded files)

use std::collections::HashMap;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;
use std::time::Duration;

use anyhow::Result;
use chrono::{Datelike, Timelike};
use tokio::sync::{Notify, Semaphore};

use crate::config::Config;
use crate::db::Db;
use crate::models::task::{FileTaskPayload, QueueStatus, ScanDirPayload, Task, TaskKind, TaskStatus};
use crate::services::dirs::DirRegistry;

/// Default priority for background work.
pub const PRIORITY_NORMAL: i32 = 0;
/// Freshly uploaded files: allowed to run outside the work window.
pub const PRIORITY_HIGH: i32 = 10;

#[derive(Clone)]
pub struct TaskQueue {
    db: Db,
    registry: DirRegistry,
    detector: Arc<dyn crate::services::ml::Detector>,
    store: Arc<crate::config::ConfigStore>,
    /// Per-kind concurrency limiters, created lazily.
    semaphores: Arc<std::sync::RwLock<HashMap<String, Arc<Semaphore>>>>,
    running: Arc<AtomicU32>,
    wake: Arc<Notify>,
}

impl TaskQueue {
    pub fn new(
        db: Db,
        registry: DirRegistry,
        store: Arc<crate::config::ConfigStore>,
        detector: Arc<dyn crate::services::ml::Detector>,
    ) -> Self {
        Self {
            db,
            registry,
            store,
            detector,
            semaphores: Arc::new(std::sync::RwLock::new(HashMap::new())),
            running: Arc::new(AtomicU32::new(0)),
            wake: Arc::new(Notify::new()),
        }
    }

    pub fn detector(&self) -> &Arc<dyn crate::services::ml::Detector> {
        &self.detector
    }

    /// Swap the detector after an ML settings change.
    pub fn set_detector(&mut self, detector: Arc<dyn crate::services::ml::Detector>) {
        self.detector = detector;
    }

    pub fn db(&self) -> &Db {
        &self.db
    }

    pub fn registry(&self) -> &DirRegistry {
        &self.registry
    }

    /// Live configuration snapshot (settings can be changed at runtime).
    pub fn config(&self) -> Arc<Config> {
        self.store.get()
    }

    fn semaphore(&self, kind: TaskKind) -> Arc<Semaphore> {
        let key = kind.as_str().to_string();
        if let Ok(guard) = self.semaphores.read() {
            if let Some(s) = guard.get(&key) {
                return s.clone();
            }
        }
        let limit = if kind.is_cpu_bound() {
            self.config().runtime.tasks.concurrency.cpu
        } else {
            self.config().runtime.tasks.concurrency.io
        };
        let sem = Arc::new(Semaphore::new(limit.max(1)));
        if let Ok(mut guard) = self.semaphores.write() {
            guard.insert(key, sem.clone());
        }
        sem
    }

    /// Recreate limiters after a settings change.
    pub fn reload_limits(&self) {
        if let Ok(mut guard) = self.semaphores.write() {
            guard.clear();
        }
    }

    // ───────────────────────────── enqueue ─────────────────────────────

    async fn enqueue(&self, kind: TaskKind, payload: &str, priority: i32) -> Result<()> {
        let now = crate::db::now();
        let res = sqlx::query(
            "INSERT OR IGNORE INTO tasks (kind, payload, priority, status, attempts, created_at, updated_at, scheduled_at) \
             VALUES (?, ?, ?, 'pending', 0, ?, ?, 0)",
        )
        .bind(kind.as_str())
        .bind(payload)
        .bind(priority)
        .bind(now)
        .bind(now)
        .execute(&self.db)
        .await?;
        if res.rows_affected() > 0 {
            self.wake.notify_one();
        }
        Ok(())
    }

    pub async fn enqueue_scan(&self, dir_id: i64, full: bool, priority: i32) -> Result<()> {
        let payload = serde_json::to_string(&ScanDirPayload { dir_id, full })?;
        self.enqueue(TaskKind::Scan, &payload, priority).await
    }

    /// Reschedule a scan even if an identical one is already queued.
    pub async fn force_scan(&self, dir_id: i64, full: bool) -> Result<()> {
        let payload = serde_json::to_string(&ScanDirPayload { dir_id, full })?;
        sqlx::query("DELETE FROM tasks WHERE kind = 'scan' AND payload = ? AND status = 'pending'")
            .bind(&payload)
            .execute(&self.db)
            .await?;
        self.enqueue(TaskKind::Scan, &payload, PRIORITY_HIGH).await
    }

    /// Enqueue thumb + detection + compression for one photo.
    /// Returns the number of tasks created.
    pub async fn enqueue_photo_pipeline(
        &self,
        dir_id: i64,
        rel_path: &str,
        priority: i32,
        changed: bool,
    ) -> Result<u64> {
        let payload = serde_json::to_string(&FileTaskPayload {
            dir_id,
            rel_path: rel_path.to_string(),
            priority,
        })?;

        let mut n = 0;
        for kind in [
            TaskKind::Thumb,
            TaskKind::Geo,
            TaskKind::DetectObject,
            TaskKind::DetectScene,
            TaskKind::DetectFace,
            TaskKind::Compress,
        ] {
            // Detection only makes sense for content that actually changed.
            if !changed
                && matches!(
                    kind,
                    TaskKind::DetectObject | TaskKind::DetectScene | TaskKind::DetectFace
                )
            {
                continue;
            }
            if !self.config().runtime.compression.enabled && kind == TaskKind::Compress {
                continue;
            }
            if !self.config().runtime.ml.enabled
                && matches!(
                    kind,
                    TaskKind::DetectObject | TaskKind::DetectScene | TaskKind::DetectFace
                )
            {
                continue;
            }
            self.enqueue(kind, &payload, priority).await?;
            n += 1;
        }
        Ok(n)
    }

    /// Enqueue thumb + probe for one video (no detection/compression — the
    /// YOLO models only understand still images).
    pub async fn enqueue_video_pipeline(
        &self,
        dir_id: i64,
        rel_path: &str,
        priority: i32,
    ) -> Result<u64> {
        let payload = serde_json::to_string(&FileTaskPayload {
            dir_id,
            rel_path: rel_path.to_string(),
            priority,
        })?;
        let mut n = 0;
        for kind in [TaskKind::Thumb, TaskKind::Probe] {
            self.enqueue(kind, &payload, priority).await?;
            n += 1;
        }
        Ok(n)
    }

    pub async fn enqueue_kind(&self, kind: TaskKind, payload: &str, priority: i32) -> Result<()> {
        self.enqueue(kind, payload, priority).await
    }

    // ──────────────────────────── scheduling ───────────────────────────

    /// Reset tasks interrupted by a restart and start the worker loops.
    pub async fn start(&self) -> Result<()> {
        sqlx::query("UPDATE tasks SET status = 'pending', updated_at = ? WHERE status = 'running'")
            .bind(crate::db::now())
            .execute(&self.db)
            .await?;

        // Kick off an initial scan of every enabled directory.
        for dir in self.registry.enabled() {
            self.enqueue_scan(dir.id, false, PRIORITY_NORMAL).await?;
        }
        self.enqueue(TaskKind::PeerSync, "{}", PRIORITY_NORMAL).await?;

        let workers = self.config().runtime.tasks.max_workers.max(1);
        for i in 0..workers {
            let queue = self.clone();
            tokio::spawn(async move {
                tracing::debug!("task worker {} started", i);
                queue.worker_loop().await;
            });
        }

        // Periodic maintenance.
        let queue = self.clone();
        tokio::spawn(async move { queue.maintenance_loop().await });

        Ok(())
    }

    async fn worker_loop(&self) {
        loop {
            if let Err(e) = self.run_once().await {
                tracing::error!("worker iteration failed: {}", e);
                tokio::time::sleep(Duration::from_secs(5)).await;
            }
        }
    }

    /// Claim and execute a single runnable task, or wait for work.
    async fn run_once(&self) -> Result<()> {
        let Some(task) = self.claim().await? else {
            tokio::select! {
                _ = self.wake.notified() => {}
                _ = tokio::time::sleep(Duration::from_secs(5)) => {}
            }
            return Ok(());
        };

        let kind = TaskKind::parse(&task.kind);
        let Some(kind) = kind else {
            self.finish(&task, Err(anyhow::anyhow!("unknown task kind"))).await?;
            return Ok(());
        };

        // Rate limiting (files per second across all workers).
        let limit = self.config().runtime.tasks.rate_limit_per_sec;
        if limit > 0 && !kind.is_maintenance() {
            let sleep_ms = 1000u64 / limit as u64;
            if sleep_ms > 0 {
                tokio::time::sleep(Duration::from_millis(sleep_ms)).await;
            }
        }

        let sem = self.semaphore(kind);
        let permit = match sem.try_acquire() {
            Ok(p) => p,
            Err(_) => {
                // Release the claim and retry later.
                self.requeue(&task).await?;
                tokio::time::sleep(Duration::from_millis(500)).await;
                return Ok(());
            }
        };

        self.running.fetch_add(1, Ordering::SeqCst);
        let result = self.execute(&task, kind).await;
        self.running.fetch_sub(1, Ordering::SeqCst);
        drop(permit);

        self.finish(&task, result).await?;
        Ok(())
    }

    /// Pick the next runnable task respecting the work window.
    async fn claim(&self) -> Result<Option<Task>> {
        let cfg = &self.config().runtime.tasks;
        let hour = chrono::Local::now().hour();
        let inside_window = cfg.work_window.contains_hour(hour);

        let rows: Vec<Task> = sqlx::query_as::<_, Task>(
            "SELECT id, kind, payload, priority, status, attempts, error, created_at, updated_at, scheduled_at \
             FROM tasks WHERE status = 'pending' AND scheduled_at <= ? \
             ORDER BY priority DESC, id ASC LIMIT 50",
        )
        .bind(crate::db::now())
        .fetch_all(&self.db)
        .await?;

        for task in rows {
            let Some(kind) = TaskKind::parse(&task.kind) else {
                continue;
            };
            if !inside_window && kind.is_cpu_bound() && task.priority < PRIORITY_HIGH {
                continue;
            }
            if task.scheduled_at > crate::db::now() {
                continue;
            }
            let res = sqlx::query("UPDATE tasks SET status = 'running', updated_at = ? WHERE id = ? AND status = 'pending'")
                .bind(crate::db::now())
                .bind(task.id)
                .execute(&self.db)
                .await?;
            if res.rows_affected() > 0 {
                return Ok(Some(task));
            }
        }
        Ok(None)
    }

    async fn requeue(&self, task: &Task) -> Result<()> {
        sqlx::query("UPDATE tasks SET status = 'pending', scheduled_at = ?, updated_at = ? WHERE id = ?")
            .bind(crate::db::now() + 30)
            .bind(crate::db::now())
            .bind(task.id)
            .execute(&self.db)
            .await?;
        Ok(())
    }

    async fn finish(&self, task: &Task, result: Result<()>) -> Result<()> {
        let now = crate::db::now();
        match result {
            Ok(()) => {
                sqlx::query("DELETE FROM tasks WHERE id = ?")
                    .bind(task.id)
                    .execute(&self.db)
                    .await?;
            }
            Err(e) => {
                let attempts = task.attempts + 1;
                let max = self.config().runtime.tasks.max_attempts as i32;
                if attempts >= max {
                    sqlx::query(
                        "UPDATE tasks SET status = 'failed', attempts = ?, error = ?, updated_at = ? WHERE id = ?",
                    )
                    .bind(attempts)
                    .bind(e.to_string())
                    .bind(now)
                    .bind(task.id)
                    .execute(&self.db)
                    .await?;
                    tracing::warn!("task {} ({}) failed permanently: {}", task.id, task.kind, e);
                } else {
                    sqlx::query(
                        "UPDATE tasks SET status = 'pending', attempts = ?, error = ?, scheduled_at = ?, updated_at = ? WHERE id = ?",
                    )
                    .bind(attempts)
                    .bind(e.to_string())
                    .bind(now + 60i64 * attempts as i64)
                    .bind(now)
                    .bind(task.id)
                    .execute(&self.db)
                    .await?;
                    tracing::debug!("task {} ({}) retry {}: {}", task.id, task.kind, attempts, e);
                }
            }
        }
        Ok(())
    }

    async fn execute(&self, task: &Task, kind: TaskKind) -> Result<()> {
        let timeout = Duration::from_secs(self.config().runtime.tasks.file_timeout_secs.max(5));
        let fut = self.dispatch(task, kind);
        match tokio::time::timeout(timeout, fut).await {
            Ok(r) => r,
            Err(_) => Err(anyhow::anyhow!("task timed out after {:?}", timeout)),
        }
    }

    async fn dispatch(&self, task: &Task, kind: TaskKind) -> Result<()> {
        match kind {
            TaskKind::Scan => {
                let payload: ScanDirPayload = serde_json::from_str(&task.payload)?;
                let dir = self
                    .registry
                    .by_id(payload.dir_id)
                    .ok_or_else(|| anyhow::anyhow!("dir {} not registered", payload.dir_id))?;
                let stats =
                    crate::services::scan::scan_dir(&self.db, &self.registry, self, &dir, payload.full)
                        .await?;
                tracing::info!(
                    "scanned {}: +{} ~{} -{} ({} tasks queued)",
                    dir.name,
                    stats.added,
                    stats.updated,
                    stats.removed,
                    stats.enqueued
                );
                Ok(())
            }
            TaskKind::Thumb => {
                let payload: FileTaskPayload = serde_json::from_str(&task.payload)?;
                crate::services::workers::make_thumbnail(self, &payload).await
            }
            TaskKind::Geo => {
                let payload: FileTaskPayload = serde_json::from_str(&task.payload)?;
                crate::services::workers::refresh_geo(self, &payload).await
            }
            TaskKind::Probe => {
                let payload: FileTaskPayload = serde_json::from_str(&task.payload)?;
                crate::services::workers::probe_video(self, &payload).await
            }
            TaskKind::DetectObject | TaskKind::DetectScene | TaskKind::DetectFace => {
                let payload: FileTaskPayload = serde_json::from_str(&task.payload)?;
                crate::services::workers::run_detection(self, kind, &payload).await
            }
            TaskKind::Compress => {
                let payload: FileTaskPayload = serde_json::from_str(&task.payload)?;
                crate::services::workers::run_compression(self, &payload).await
            }
            TaskKind::DedupScan => {
                let groups = crate::services::dedup::scan(&self.db, &self.registry).await?;
                tracing::info!("dedup scan found {} duplicate groups", groups.len());
                Ok(())
            }
            TaskKind::CleanTrash => {
                let (n, freed) = crate::services::trash::purge_expired(&self.db).await?;
                let (o, ofreed) = crate::services::originals::purge_expired(&self.config()).await?;
                tracing::info!(
                    "cleanup: {} trash entries ({} bytes), {} originals ({} bytes)",
                    n, freed, o, ofreed
                );
                Ok(())
            }
            TaskKind::AuditRetention => {
                let n = crate::services::audit::purge_expired(
                    &self.db,
                    self.config().runtime.audit.retention_days,
                )
                .await?;
                tracing::info!("purged {} audit rows", n);
                Ok(())
            }
            TaskKind::HealthCheck => {
                crate::services::alerts::check(&self.db, &self.config(), &self.registry).await
            }
            TaskKind::PeerSync => {
                let n = crate::services::audit::sync_peers(&self.db, &self.config()).await?;
                tracing::info!("wireguard peer sync: {} peers", n);
                Ok(())
            }
            TaskKind::Backup => {
                let config = self.config();
                match crate::services::backup::run(&self.db, &config).await {
                    Ok(path) => {
                        tracing::info!("sqlite backup written to {}", path.display());
                        Ok(())
                    }
                    Err(e) => {
                        let _ = crate::db::set_setting(
                            &self.db,
                            "last_backup_error",
                            &e.to_string(),
                        )
                        .await;
                        Err(e)
                    }
                }
            }
        }
    }

    // ─────────────────────────── maintenance ───────────────────────────

    async fn maintenance_loop(&self) {
        let mut cleanup = tokio::time::interval(Duration::from_secs(3600));
        let mut health = tokio::time::interval(Duration::from_secs(1800));
        let mut peers = tokio::time::interval(Duration::from_secs(
            self.config().wireguard.sync_interval_secs.max(60) as u64,
        ));
        let mut dedup = tokio::time::interval(Duration::from_secs(24 * 3600));
        let mut rescan = tokio::time::interval(Duration::from_secs(3600));
        let mut backup = tokio::time::interval(Duration::from_secs(
            self.config().runtime.backup.interval_hours.max(1) as u64 * 3600,
        ));

        loop {
            tokio::select! {
                _ = cleanup.tick() => {
                    let _ = self.enqueue(TaskKind::CleanTrash, "{}", PRIORITY_NORMAL).await;
                    let _ = self.enqueue(TaskKind::AuditRetention, "{}", PRIORITY_NORMAL).await;
                }
                _ = health.tick() => {
                    let _ = self.enqueue(TaskKind::HealthCheck, "{}", PRIORITY_NORMAL).await;
                }
                _ = peers.tick() => {
                    let _ = self.enqueue(TaskKind::PeerSync, "{}", PRIORITY_NORMAL).await;
                }
                _ = dedup.tick() => {
                    if self.config().runtime.ml.enabled {
                        let _ = self.enqueue(TaskKind::DedupScan, "{}", PRIORITY_NORMAL).await;
                    }
                }
                _ = rescan.tick() => {
                    if self.scheduled_full_rescan() {
                        for dir in self.registry.enabled() {
                            let _ = self.enqueue_scan(dir.id, true, PRIORITY_NORMAL).await;
                        }
                    }
                }
                _ = backup.tick() => {
                    if self.config().runtime.backup.enabled {
                        let _ = self.enqueue(TaskKind::Backup, "{}", PRIORITY_NORMAL).await;
                    }
                }
            }
        }
    }

    fn scheduled_full_rescan(&self) -> bool {
        let cfg = &self.config().runtime.tasks;
        let hour = chrono::Local::now().hour();
        match cfg.full_rescan.as_str() {
            "off" => false,
            "daily" => hour == cfg.full_rescan_hour,
            _ => hour == cfg.full_rescan_hour && chrono::Local::now().weekday().number_from_monday() == 7,
        }
    }

    // ──────────────────────────── introspection ────────────────────────

    pub async fn queue_status(&self) -> Result<Vec<QueueStatus>> {
        let mut out = Vec::new();
        for kind in TaskKind::all() {
            let row: (i64, i64, i64, i64) = sqlx::query_as(
                "SELECT \
                    COALESCE(SUM(status = 'pending'), 0), \
                    COALESCE(SUM(status = 'running'), 0), \
                    COALESCE(SUM(status = 'failed'), 0), \
                    0 \
                 FROM tasks WHERE kind = ?",
            )
            .bind(kind.as_str())
            .fetch_one(&self.db)
            .await
            .unwrap_or((0, 0, 0, 0));
            out.push(QueueStatus {
                kind: kind.as_str().to_string(),
                pending: row.0,
                running: row.1,
                failed: row.2,
                done: row.3,
                concurrency: if kind.is_cpu_bound() {
                    self.config().runtime.tasks.concurrency.cpu
                } else {
                    self.config().runtime.tasks.concurrency.io
                },
            });
        }
        Ok(out)
    }

    /// Tasks finished in the last `secs` seconds (throughput gauge).
    pub async fn throughput(&self, secs: i64) -> Result<i64> {
        // Completed tasks are deleted, so we approximate with the remaining
        // queue plus rows touched recently.
        let since = crate::db::now() - secs;
        let row: (i64,) =
            sqlx::query_as("SELECT COUNT(*) FROM tasks WHERE updated_at >= ? AND status != 'pending'")
                .bind(since)
                .fetch_one(&self.db)
                .await
                .unwrap_or((0,));
        Ok(row.0)
    }

    pub async fn failed(&self, limit: i64) -> Result<Vec<Task>> {
        Ok(sqlx::query_as::<_, Task>(
            "SELECT id, kind, payload, priority, status, attempts, error, created_at, updated_at, scheduled_at \
             FROM tasks WHERE status = 'failed' ORDER BY updated_at DESC LIMIT ?",
        )
        .bind(limit)
        .fetch_all(&self.db)
        .await?)
    }

    /// Re-run every failed task.
    pub async fn retry_failed(&self) -> Result<u64> {
        let res = sqlx::query(
            "UPDATE tasks SET status = 'pending', attempts = 0, error = NULL, scheduled_at = 0 WHERE status = 'failed'",
        )
        .execute(&self.db)
        .await?;
        self.wake.notify_one();
        Ok(res.rows_affected())
    }

    pub async fn clear_failed(&self) -> Result<u64> {
        let res = sqlx::query("DELETE FROM tasks WHERE status = 'failed'")
            .execute(&self.db)
            .await?;
        Ok(res.rows_affected())
    }

    pub fn running_now(&self) -> u32 {
        self.running.load(Ordering::SeqCst)
    }

    pub fn status_counts(status: TaskStatus) -> &'static str {
        status.as_str()
    }
}
