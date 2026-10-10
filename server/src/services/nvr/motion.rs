//! Motion (scene-change) scoring for recorded segments, run by the
//! maintainer in parallel with ffprobe. Uses a separate ffmpeg pass with the
//! `scdet` filter: every frame is classified as a "scene" (i.e. a sudden
//! change of the picture) and the flags are parsed from `info`-level log
//! lines. A segment whose flagged-frame fraction exceeds
//! `still_threshold` is "still" (static scene) and may be dropped from
//! storage when `drop_still` is on.
//!
//! The pass is cheap on purpose: frames are downscaled to ≤96px height
//! before scdet, so a 1080p 60s segment is probed in a few hundred ms.

use std::path::Path;
use std::time::Duration;

use crate::config::NvrConfig;

/// 0 = unknown (legacy / not scored), 1 = still, 2 = active.
pub const MOTION_UNKNOWN: i64 = 0;
pub const MOTION_STILL: i64 = 1;
pub const MOTION_ACTIVE: i64 = 2;

/// Score a segment's picture change. `None` = probe failed (caller treats
/// it as UNKNOWN and keeps the segment). Bounded to ~60s so a wedged
/// ffmpeg can't stall the maintenance round.
///
/// The pass is cheap on purpose: frames are downscaled to ≤96px height and
/// rate-limited to 5 fps before scdet, so a 1080p 60s segment is probed in
/// a few seconds. scdet (ffmpeg 7.x) prints one line per *scene* frame:
/// `[scdet @ …] lavfi.scd.score: 38.4, lavfi.scd.time: 1.6`; the total
/// frame count comes from the final `frame=N` progress line.
pub async fn score_motion(ffmpeg: &str, file: &Path, cfg: &NvrConfig) -> Option<(i64, f64)> {
    let mut cmd = tokio::process::Command::new(ffmpeg);
    cmd.args([
        "-hide_banner",
        "-loglevel",
        "info",
        "-nostdin",
        "-i",
        file.to_str().unwrap_or_default(),
        "-an",
        "-vf",
        "scale=-2:96,fps=5,scdet=threshold=0.05",
        "-f",
        "null",
        "-",
    ])
    .stdin(std::process::Stdio::null())
    .stdout(std::process::Stdio::null())
    .stderr(std::process::Stdio::piped())
    .kill_on_drop(true);

    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            tracing::debug!("motion score: spawn failed for {}: {e}", file.display());
            return None;
        }
    };
    let stderr = child.stderr.as_mut()?;
    use tokio::io::AsyncBufReadExt;
    let mut lines = tokio::io::BufReader::new(stderr).lines();

    let mut flagged: u64 = 0;
    let mut total: u64 = 0;
    let res: Result<(), tokio::time::error::Elapsed> =
        tokio::time::timeout(Duration::from_secs(60), async {
            while let Ok(Some(line)) = lines.next_line().await {
                let l: &str = line.trim();
                if l.contains("lavfi.scd.score") {
                    flagged += 1;
                    continue;
                }
                // Final progress line: `frame=  113 fps=7.4 … time=…`
                if let Some(pos) = l.find("frame=") {
                    if let Some(rest) = l.get(pos + 6..) {
                        if let Some(num) = rest.split_whitespace().next() {
                            if let Ok(n) = num.parse::<u64>() {
                                total = n;
                            }
                        }
                    }
                }
            }
        })
        .await;

    match res {
        Ok(()) => {
            let _ = child.wait().await;
        }
        Err(_) => {
            // Timed out — kill_on_drop will reap it; treat as unknown.
            return None;
        }
    }

    if total < 10 {
        // Too few frames to judge (very short / corrupt segment).
        return None;
    }
    let score = flagged as f64 / total as f64;
    let motion = if score > cfg.still_threshold {
        MOTION_ACTIVE
    } else {
        MOTION_STILL
    };
    tracing::debug!(
        "motion score {}: {} scenes / {} frames = {:.4}",
        file.file_name().unwrap_or_default().to_string_lossy(),
        flagged,
        total,
        score
    );
    Some((motion, score))
}
