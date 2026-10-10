//! Maintenance loop: pick completed segments out of `.cache/`, ffprobe them,
//! rename into `recordings/YYYY-MM-DD/HH/{cam}/MM.SS.mp4` and register the
//! row. Idempotent — a segment already in the DB is skipped.

use crate::services::nvr::{Camera, NvrState, CAMERA_COLUMNS};

/// A completed cache file whose name we could parse.
struct Pending {
    file: std::path::PathBuf,
    cam_name: String,
    start_ts: i64,
}

/// Parse `home-20260930143059.mp4` (strftime local time, no `%f`).
/// Returns (camera_name, unix start seconds).
pub fn parse_cache_name(file_name: &str, tz: chrono_tz::Tz) -> Option<(String, i64)> {
    let (stem, ext) = file_name
        .rsplit_once('.')
        .unwrap_or((file_name, ""));
    if ext != "mp4" {
        return None;
    }
    // Pattern: {cam}-{YYYYmmddHHMMSS}
    let Some((cam, ts)) = stem.rsplit_once('-') else {
        return None;
    };
    if cam.is_empty() || ts.len() != 14 || !ts.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }
    let y: i32 = ts[0..4].parse().ok()?;
    let mo: u32 = ts[4..6].parse().ok()?;
    let d: u32 = ts[6..8].parse().ok()?;
    let h: u32 = ts[8..10].parse().ok()?;
    let mi: u32 = ts[10..12].parse().ok()?;
    let s: u32 = ts[12..14].parse().ok()?;
    let naive = chrono::NaiveDate::from_ymd_opt(y, mo, d)?.and_hms_opt(h, mi, s)?;
    let ts_unix = naive.and_local_timezone(tz).single()?.timestamp();
    Some((cam.to_string(), ts_unix))
}

/// Target path: `recordings/{YYYY-MM-DD}/{HH}/{cam}/{MM}.{SS}.mp4` (local tz).
pub fn target_rel_path(start_ts: i64, tz: chrono_tz::Tz, cam: &str) -> Option<String> {
    let dt = chrono::DateTime::from_timestamp(start_ts, 0)?;
    let n = dt.with_timezone(&tz).naive_local();
    Some(format!(
        "recordings/{}/{}/{}/{:02}.{:02}.mp4",
        n.format("%Y-%m-%d"),
        n.format("%H"),
        cam,
        n.format("%M"),
        n.format("%S")
    ))
}

async fn collect_pending(state: &NvrState, tz: chrono_tz::Tz) -> Vec<Pending> {
    let Ok(entries) = std::fs::read_dir(&state.cache_dir) else {
        return Vec::new();
    };
    let now = chrono::Utc::now().timestamp();
    let mut out = Vec::new();
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        let Some((cam, start_ts)) = parse_cache_name(&name, tz) else {
            continue;
        };
        // Only touch files that finished writing: ffmpeg stops writing a
        // segment when it rolls over, so "older than one segment period"
        // means complete. Also skip files younger than 2s (still open).
        let age = now
            - entry
                .metadata()
                .and_then(|m| m.modified())
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_secs() as i64)
                .unwrap_or(i64::MAX);
        if age < 3 {
            continue;
        }
        out.push(Pending {
            file: entry.path(),
            cam_name: cam,
            start_ts,
        });
    }
    out
}

pub async fn round(state: &NvrState) {
    let cfg = state.config.get().runtime.nvr.clone();
    let tz: chrono_tz::Tz = cfg.timezone.parse().unwrap_or(chrono_tz::Tz::Asia__Shanghai);
    let pending = collect_pending(state, tz).await;
    if pending.is_empty() {
        return;
    }

    // Cam name -> id for the cameras currently in the DB.
    //
    // ⚠️ 必须用 `CAMERA_COLUMNS`，不能手写列清单：`Camera` 是 `query_as` 映射的，
    // 少任何一列都会在**运行时**报 `no column found for name: <col>`（编译期查不出来），
    // 而这个错误会让整轮 round() 提前 return —— 所有待入库片段全部积压，录制看起来
    // 正常、实际上一个都没登记。2026-10-09 就因为这里漏了 `rtsp_sub_url` 踩过一次。
    let cams = match sqlx::query_as::<_, Camera>(&format!("SELECT {CAMERA_COLUMNS} FROM cameras"))
        .fetch_all(&state.db)
        .await
    {
        Ok(c) => c,
        Err(e) => {
            tracing::warn!("nvr maintainer: camera query failed: {e}");
            return;
        }
    };
    let cam_by_name: std::collections::HashMap<&str, i64> =
        cams.iter().map(|c| (c.name.as_str(), c.id)).collect();

    for p in pending {
        if let Err(e) = handle_one(state, tz, &p, &cam_by_name).await {
            tracing::warn!("nvr maintainer: {}: {e}", p.file.display());
        }
    }
}

async fn handle_one(
    state: &NvrState,
    tz: chrono_tz::Tz,
    p: &Pending,
    cam_by_name: &std::collections::HashMap<&str, i64>,
) -> anyhow::Result<()> {
    // Camera must exist in the DB — otherwise the file is an orphan from a
    // deleted camera; leave it (startup sweep / cleanup owns orphan pruning).
    let Some(&cam_id) = cam_by_name.get(p.cam_name.as_str()) else {
        // Unknown camera: delete the cache file — no DB row can ever be
        // created for it, and it will clog the cache forever.
        let _ = std::fs::remove_file(&p.file);
        tracing::info!(
            "nvr maintainer: dropped segment for unknown camera '{}' ({})",
            p.cam_name,
            p.file.display()
        );
        return Ok(());
    };

    // Idempotency: target path already registered?
    let rel = match target_rel_path(p.start_ts, tz, &p.cam_name) {
        Some(r) => r,
        None => {
            anyhow::bail!("cannot map start_time to local date");
        }
    };
    let exists: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM nvr_segments WHERE path = ?")
        .bind(&rel)
        .fetch_one(&state.db)
        .await?;
    if exists.0 > 0 {
        // Already registered — the cache file is a duplicate/rewrite; drop it.
        let _ = std::fs::remove_file(&p.file);
        return Ok(());
    }

    let Some(ffprobe) = &state.ffprobe else {
        anyhow::bail!("ffprobe unavailable");
    };
    // A file whose ffprobe keeps failing must NOT be retried forever. Without
    // this, a single corrupt/unreadable segment stays in `.cache/`, gets
    // re-collected by `collect_pending` on EVERY round, and each attempt burns
    // an ffprobe subprocess — observed repeating for hours on one file.
    // A brand-new segment can legitimately fail once (ffmpeg may still be
    // finalizing its moov atom), so allow a couple of tries before giving up.
    const PROBE_RETRIES: u32 = 3;
    let key = format!("probe_fail:{}", p.file.display());
    // Scoped so the lock is NOT held across the probe (which is async and can
    // take a while); the value is copied out, the guard dropped, then re-taken.
    let fails = {
        let mut m = state.probe_failures.lock().await;
        *m.entry(key.clone()).or_insert(0)
    };
    if fails >= PROBE_RETRIES {
        let _ = std::fs::remove_file(&p.file);
        state.probe_failures.lock().await.remove(&key);
        tracing::warn!(
            "nvr maintainer: dropped unprobeable segment {} after {PROBE_RETRIES} attempts",
            p.file.display()
        );
        return Ok(());
    }
    state.probe_failures.lock().await.insert(key.clone(), fails + 1);
    let meta = match crate::services::probe::probe(&p.file, ffprobe).await {
        Ok(m) => m,
        Err(e) => {
            // Keep the file for the next attempt; the counter above eventually
            // gives up and deletes it so it can't clog the cache forever.
            return Err(anyhow::anyhow!("{e}"));
        }
    };
    let duration_ms = meta.duration_ms.unwrap_or(0);
    if duration_ms < 1000 {
        // Too short to be a real segment (partial write); drop.
        let _ = std::fs::remove_file(&p.file);
        tracing::info!(
            "nvr maintainer: dropped short segment {} ({}ms)",
            p.file.display(),
            duration_ms
        );
        return Ok(());
    }
    // Probe succeeded — clear the retry state for this file.
    state.probe_failures.lock().await.remove(&key);

    let duration = (duration_ms as f64 / 1000.0).round() as i64;
    let end_ts = p.start_ts + duration;
    let size = std::fs::metadata(&p.file)
        .ok()
        .and_then(|m| m.len().try_into().ok())
        .unwrap_or(0);

    let abs = state.segment_abs_path(&rel);
    if let Some(parent) = abs.parent() {
        std::fs::create_dir_all(parent)?;
    }
    // Cross-filesystem rename fallback (recordings root may be on a different
    // mount than the cache, e.g. NAS): copy + delete.
    match std::fs::rename(&p.file, &abs) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::InvalidInput => {
            std::fs::copy(&p.file, &abs)?;
            std::fs::remove_file(&p.file)?;
        }
        Err(e) => return Err(anyhow::anyhow!("rename: {e}")),
    }

    // Score motion on the FINAL file, after the move. The scdet probe needs the
    // file to exist; scoring on the cache path races the rename — `tokio::spawn`
    // only *enqueues* the task, so ffmpeg opens the path only after the move has
    // already renamed / copied+deleted it, and the probe finds nothing (every
    // segment ended up motion=unknown). Run it inline on `abs` now.
    let nvr_cfg = state.config.get().runtime.nvr.clone();
    let motion_result: Option<(i64, f64)> = if nvr_cfg.motion_detect {
        let ffmpeg = state.ffmpeg.clone().unwrap_or_default();
        if ffmpeg.is_empty() {
            None
        } else {
            super::motion::score_motion(&ffmpeg, &abs, &nvr_cfg).await
        }
    } else {
        None
    };
    let (motion, motion_score) = match motion_result {
        Some((m, s)) => (m, s),
        None => (super::motion::MOTION_UNKNOWN, 0.0),
    };

    // "Still" segment + drop_still on: remove the file and skip the row —
    // the timeline only keeps activity.
    if motion == super::motion::MOTION_STILL && nvr_cfg.drop_still {
        let _ = std::fs::remove_file(&abs);
        tracing::info!(
            "nvr maintainer: dropped still segment {rel} (score {motion_score:.4})"
        );
        return Ok(());
    }

    let now = crate::db::now();
    let res = sqlx::query(
        "INSERT INTO nvr_segments \
         (camera_id, path, start_time, end_time, duration, size_bytes, video_codec, width, height, motion, motion_score, created_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(cam_id)
    .bind(&rel)
    .bind(p.start_ts)
    .bind(end_ts)
    .bind(duration_ms as f64 / 1000.0)
    .bind(size)
    .bind(&meta.codec)
    .bind(meta.width)
    .bind(meta.height)
    .bind(motion)
    .bind(motion_score)
    .bind(now)
    .execute(&state.db)
    .await;
    match res {
        Ok(_) => {
            tracing::info!("nvr maintainer: registered {rel} ({}s)", duration);
            Ok(())
        }
        Err(sqlx::Error::Database(db_err)) if db_err.message().contains("UNIQUE") => {
            // Lost a race with another maintainer round; file is already in
            // place — fine.
            Ok(())
        }
        Err(e) => {
            // Row insert failed but file was moved: move it back so the next
            // round can retry.
            let _ = std::fs::rename(&abs, &p.file);
            Err(anyhow::anyhow!("insert: {e}"))
        }
    }
}

/// Cache files older than 1h are considered corrupt (ffmpeg should never
/// leave them). Called from the cleanup loop.
pub async fn prune_stale_cache(state: &NvrState) {
    let Ok(entries) = std::fs::read_dir(&state.cache_dir) else {
        return;
    };
    let now = chrono::Utc::now().timestamp();
    for entry in entries.flatten() {
        let meta = match entry.metadata() {
            Ok(m) => m,
            Err(_) => continue,
        };
        let age = meta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| now - d.as_secs() as i64)
            .unwrap_or(0);
        if age > 3600 {
            let _ = std::fs::remove_file(entry.path());
            tracing::info!(
                "nvr maintainer: pruned stale cache file {}",
                entry.path().display()
            );
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_name_utc_offset() {
        // Use Asia/Shanghai (+08:00) so the epoch math is non-trivial.
        let tz = chrono_tz::Tz::Asia__Shanghai;
        let (cam, ts) = parse_cache_name("home-20260930143059.mp4", tz).unwrap();
        assert_eq!(cam, "home");
        // 2026-09-30 14:30:59 +08:00 = 2026-09-30 06:30:59 UTC
        assert_eq!(
            ts,
            chrono::NaiveDate::from_ymd_opt(2026, 9, 30)
                .unwrap()
                .and_hms_opt(6, 30, 59)
                .unwrap()
                .and_utc()
                .timestamp()
        );
    }

    #[test]
    fn parse_name_rejects_junk() {
        let tz = chrono_tz::Tz::UTC;
        assert!(parse_cache_name("no-separator.mp4", tz).is_none());
        assert!(parse_cache_name("home-2026.mp4", tz).is_none());
        assert!(parse_cache_name("home-20260930143059.txt", tz).is_none());
        assert!(parse_cache_name("home-20260231143059.mp4", tz).is_none()); // invalid date
    }

    /// The invariant the recorder's `TZ` env var exists to preserve: a filename
    /// ffmpeg writes under zone Z must parse back to the SAME instant when read
    /// with zone Z. Round-tripping every zone the config can name.
    #[test]
    fn strftime_name_round_trips_under_same_zone() {
        for tz in [
            chrono_tz::Tz::Asia__Shanghai,
            chrono_tz::Tz::UTC,
            chrono_tz::Tz::America__New_York,
        ] {
            let now = chrono::Utc::now();
            // What ffmpeg's `-strftime %Y%m%d%H%M%S` prints with TZ=<tz>.
            let written = now
                .with_timezone(&tz)
                .format("home-%Y%m%d%H%M%S.mp4")
                .to_string();
            let (cam, back) = parse_cache_name(&written, tz).expect("must parse");
            assert_eq!(cam, "home");
            // Sub-second truncation is expected; anything larger is a real bug.
            assert!(
                (back - now.timestamp()).abs() <= 1,
                "round trip drifted {}s in {tz}",
                back - now.timestamp()
            );
        }
    }

    /// Regression guard for the shipped bug: ffmpeg ran without `TZ` (so it
    /// named files in UTC) while `parse_cache_name` decoded them as
    /// Asia/Shanghai — shifting every timestamp and directory by 8 hours.
    /// This pins the size of that error so the mismatch can't come back
    /// unnoticed.
    #[test]
    fn zone_mismatch_shifts_by_utc_offset() {
        let shanghai = chrono_tz::Tz::Asia__Shanghai;
        // 17:01:22 Beijing == 09:01:22 UTC — the exact case reported.
        let (_, correct) = parse_cache_name("home-20261008170122.mp4", shanghai).unwrap();
        let (_, wrong) = parse_cache_name("home-20261008090122.mp4", shanghai).unwrap();
        assert_eq!(correct - wrong, 8 * 3600, "mismatch must be exactly +8h");
    }

    #[test]
    fn target_path_layout() {
        let tz = chrono_tz::Tz::Asia__Shanghai;
        let ts = chrono::NaiveDate::from_ymd_opt(2026, 9, 30)
            .unwrap()
            .and_hms_opt(6, 30, 59)
            .unwrap()
            .and_utc()
            .timestamp();
        assert_eq!(
            target_rel_path(ts, tz, "home").unwrap(),
            "recordings/2026-09-30/14/home/30.59.mp4"
        );
    }
}
