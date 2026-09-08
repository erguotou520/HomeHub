//! Directory scanning: index files, enqueue downstream work.
//!
//! Incremental by default: a file is re-processed only when its
//! `mtime:size` fingerprint changed. File system events (inotify) enqueue
//! targeted scans; a full rescan runs on a configurable schedule.

use std::collections::HashSet;
use std::path::Path;

use anyhow::Result;
use walkdir::WalkDir;

use crate::db::Db;
use crate::models::dir::{DirMark, DirRecord};
use crate::services::dirs::DirRegistry;
use crate::services::paths::{ext_of, fingerprint, is_image, is_video, normalize_rel};
use crate::services::tasks::{TaskQueue, PRIORITY_NORMAL};

#[derive(Debug, Default)]
pub struct ScanStats {
    pub seen: u64,
    pub added: u64,
    pub updated: u64,
    pub removed: u64,
    pub enqueued: u64,
}

pub async fn scan_dir(
    db: &Db,
    registry: &DirRegistry,
    queue: &TaskQueue,
    dir: &DirRecord,
    full: bool,
) -> Result<ScanStats> {
    let root = Path::new(&dir.path);
    if !root.exists() {
        anyhow::bail!("directory {} does not exist", dir.path);
    }

    let album = dir.has_mark(DirMark::Album);
    let mut stats = ScanStats::default();
    let mut seen_paths: HashSet<String> = HashSet::new();
    let default_ignore = crate::config::default_ignore_rules();

    for entry in WalkDir::new(root)
        .min_depth(1)
        .follow_links(false)
        .into_iter()
        .filter_entry(|e| {
            if e.depth() == 0 {
                return true;
            }
            let name = e.file_name().to_string_lossy();
            if e.file_type().is_dir() && default_ignore.iter().any(|i| i == name.as_ref()) {
                return false;
            }
            true
        })
        .filter_map(|e| e.ok())
    {
        let Ok(rel_os) = entry.path().strip_prefix(root) else {
            continue;
        };
        let rel = normalize_rel(&rel_os.to_string_lossy());
        if rel.is_empty() || registry.is_ignored(dir, &rel) {
            continue;
        }

        let metadata = match entry.metadata() {
            Ok(m) => m,
            Err(_) => continue,
        };
        let is_dir = metadata.is_dir();
        let name = entry.file_name().to_string_lossy().to_string();
        let mtime = metadata
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        let size = if is_dir { 0 } else { metadata.len() };
        let fp = fingerprint(mtime, size);

        seen_paths.insert(rel.clone());

        let known: Option<(i64, String)> =
            sqlx::query_as("SELECT id, fingerprint FROM file_index WHERE dir_id = ? AND rel_path = ?")
                .bind(dir.id)
                .bind(&rel)
                .fetch_optional(db)
                .await?;

        let changed = match &known {
            Some((_, old_fp)) => *old_fp != fp,
            None => true,
        };
        if !changed && !full {
            continue;
        }

        if known.is_none() {
            stats.added += 1;
        } else {
            stats.updated += 1;
        }
        stats.seen += 1;

        sqlx::query(
            "INSERT INTO file_index (dir_id, rel_path, name, is_dir, size, mtime, fingerprint) \
             VALUES (?, ?, ?, ?, ?, ?, ?) \
             ON CONFLICT(dir_id, rel_path) DO UPDATE SET name = excluded.name, \
                 is_dir = excluded.is_dir, size = excluded.size, mtime = excluded.mtime, \
                 fingerprint = excluded.fingerprint",
        )
        .bind(dir.id)
        .bind(&rel)
        .bind(&name)
        .bind(if is_dir { 1 } else { 0 })
        .bind(size as i64)
        .bind(mtime)
        .bind(&fp)
        .execute(db)
        .await?;

        crate::services::search::index_file(db, dir.id, &dir.name, &rel, &name).await?;

        let ext = ext_of(&name);
        let media_kind = if is_image(&ext) {
            Some("photo")
        } else if is_video(&ext) {
            Some("video")
        } else {
            None
        };
        if is_dir || !album || media_kind.is_none() {
            continue;
        }
        let media_kind = media_kind.unwrap();

        // Photo asset metadata
        let full_path = entry.path().to_path_buf();
        let is_video_file = media_kind == "video";
        let meta = if is_video_file {
            // Video metadata (duration/codec) is filled in by the probe task;
            // EXIF parsers only understand still images.
            crate::services::exif::PhotoMeta::default()
        } else {
            crate::services::exif::read_metadata(&full_path).unwrap_or_default()
        };
        let file_hash = crate::services::hash::file_sha256(&full_path).unwrap_or_default();
        let pixel_hash = if is_video_file {
            None
        } else {
            crate::services::hash::pixel_hash(&full_path, meta.orientation)
        };

        let photo_id = crate::services::photos::upsert(
            db,
            dir,
            &rel,
            &fp,
            size as i64,
            mtime,
            &meta,
            &file_hash,
            pixel_hash.as_deref(),
            media_kind,
            None,
            None,
        )
        .await?;

        let asset = crate::services::photos::get(db, photo_id).await?;
        if let Some(asset) = asset {
            crate::services::search::index_photo(db, &asset, &dir.name).await?;
        }

        let priority = if changed { PRIORITY_NORMAL } else { PRIORITY_NORMAL };
        if is_video_file {
            stats.enqueued += queue
                .enqueue_video_pipeline(dir.id, &rel, priority)
                .await
                .unwrap_or(0);
        } else {
            stats.enqueued += queue
                .enqueue_photo_pipeline(dir.id, &rel, priority, full || changed)
                .await
                .unwrap_or(0);
        }
    }

    // Anything we know about but did not see has disappeared from disk.
    let known: Vec<(i64, String)> =
        sqlx::query_as("SELECT id, rel_path FROM file_index WHERE dir_id = ?")
            .bind(dir.id)
            .fetch_all(db)
            .await?;
    for (id, rel) in known {
        if seen_paths.contains(&rel) {
            continue;
        }
        sqlx::query("DELETE FROM file_index WHERE id = ?")
            .bind(id)
            .execute(db)
            .await?;
        crate::services::search::remove(db, "file", &format!("{}:{}", dir.id, rel)).await?;
        if album && (is_image(&ext_of(&rel)) || is_video(&ext_of(&rel))) {
            let photo = crate::services::photos::get_path(db, dir.id, &rel).await?;
            if let Some(photo) = photo {
                crate::services::search::remove(db, "photo", &photo.id.to_string()).await?;
                crate::services::photos::delete_photo(db, photo.id).await?;
            }
        }
        stats.removed += 1;
    }

    // A full rescan is the cheap moment to rebuild the FTS index from scratch.
    if full {
        crate::services::search::rebuild(db).await?;
    }

    Ok(stats)
}

/// File system watcher: enqueue a targeted scan whenever something changes.
pub fn spawn_watcher(registry: DirRegistry, queue: TaskQueue, debounce_secs: u64) -> Result<()> {
    use notify::{Config as NotifyConfig, EventKind, RecommendedWatcher, RecursiveMode, Watcher};
    use std::collections::HashMap;
    use std::sync::mpsc as std_mpsc;
    use std::time::{Duration, Instant};

    let (ntx, nrx) = std_mpsc::channel::<notify::Result<notify::Event>>();
    let mut watcher = RecommendedWatcher::new(
        move |res: notify::Result<notify::Event>| {
            let _ = ntx.send(res);
        },
        NotifyConfig::default().with_poll_interval(Duration::from_secs(30)),
    )?;

    let mut watched: Vec<(std::path::PathBuf, i64)> = Vec::new();
    for dir in registry.enabled() {
        let path = Path::new(&dir.path);
        if path.exists() {
            match watcher.watch(path, RecursiveMode::Recursive) {
                Ok(_) => watched.push((path.to_path_buf(), dir.id)),
                Err(e) => tracing::warn!("cannot watch {}: {}", dir.path, e),
            }
        }
    }
    if watched.is_empty() {
        return Ok(());
    }
    tracing::info!("watching {} directories for changes", watched.len());

    let debounce = Duration::from_secs(debounce_secs.max(5));
    tokio::spawn(async move {
        let mut last: HashMap<i64, Instant> = HashMap::new();
        loop {
            let events: Vec<notify::Event> = tokio::task::block_in_place(|| {
                let mut out = Vec::new();
                match nrx.recv() {
                    Ok(Ok(event)) => out.push(event),
                    Ok(Err(e)) => tracing::debug!("watch error: {}", e),
                    Err(_) => return out,
                }
                while let Ok(Ok(event)) = nrx.try_recv() {
                    out.push(event);
                }
                out
            });

            if events.is_empty() {
                break;
            }

            for event in events {
                let relevant = matches!(
                    event.kind,
                    EventKind::Create(_) | EventKind::Modify(_) | EventKind::Remove(_)
                );
                if !relevant {
                    continue;
                }
                let Some(path) = event.paths.first() else {
                    continue;
                };
                let Some((_, dir_id)) = watched
                    .iter()
                    .find(|(root, _)| path.starts_with(root))
                else {
                    continue;
                };
                let dir_id = *dir_id;

                let now = Instant::now();
                if let Some(prev) = last.get(&dir_id) {
                    if now.duration_since(*prev) < debounce {
                        continue;
                    }
                }
                last.insert(dir_id, now);
                if let Err(e) = queue.enqueue_scan(dir_id, false, PRIORITY_NORMAL).await {
                    tracing::debug!("cannot enqueue scan: {}", e);
                }
            }
        }
    });

    // Keep the watcher alive for the lifetime of the process.
    std::mem::forget(watcher);
    Ok(())
}
