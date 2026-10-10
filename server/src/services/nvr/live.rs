//! On-demand RTSP→HLS live preview (one ffmpeg per viewed camera).
//!
//! The admin UI plays these with hls.js. Segments are 2s fMP4 with an IDR
//! every 2s, and ffmpeg keeps a rolling 6-segment window (`hls_list_size 6`,
//! ~12s) on disk — it prunes old fragments itself, so the playlist is
//! served verbatim. A reaper task stops sessions idle for `IDLE_TTL` and
//! drops the stream directory.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

use tokio::sync::RwLock;

use super::recorder::{is_hwaccel, is_vaapi, parse_scale, pick_encoder, vaapi_input_args};
use super::NvrState;
use crate::services::nvr::Camera;

const IDLE_TTL: Duration = Duration::from_secs(180);
const HLS_SECONDS: u32 = 2;
const LIST_SIZE: u32 = 6; // ~12s rolling window on disk
const REAP_INTERVAL: Duration = Duration::from_secs(15);

/// Target bitrate for the VAAPI live encoder. `h264_vaapi` accepts but
/// silently ignores `-crf`/`-preset` (measured — see `recorder.rs`), and with
/// no `-b:v` at all it falls back to ffmpeg's default 200 kbps, which is
/// unusable at 720p. `-b:v` is therefore the only quality knob on this path.
const LIVE_VAAPI_BITRATE: &str = "2000k";
const LIVE_VAAPI_BUF: &str = "4000k";

/// One live session = one ffmpeg child + activity clock.
pub struct LiveSession {
    pub cam_id: i64,
    pub cam_name: String,
    /// Which RTSP stream this session pulls: `"main"` or `"sub"`. A request
    /// for the other one tears the session down and respawns (see `ensure`).
    pub stream: String,
    pub child: Option<tokio::process::Child>,
    /// Last time anything (playlist or segment) was served from this stream.
    pub last_hit: Instant,
    /// `true` while a spawn is in flight: concurrent `ensure()` calls for
    /// the same camera must NOT each spawn an ffmpeg — two writers would
    /// interleave on the same m3u8 and hls.js gets an unparseable manifest.
    pub starting: bool,
}

pub struct LiveHub {
    pub live_dir: PathBuf,
    sessions: RwLock<HashMap<i64, LiveSession>>,
}

impl LiveHub {
    pub fn new(live_dir: PathBuf) -> Self {
        Self {
            live_dir,
            sessions: RwLock::new(HashMap::new()),
        }
    }

    /// Stream dir for a camera, named by id (stable across renames).
    pub fn stream_dir(&self, cam_id: i64) -> PathBuf {
        self.live_dir.join(format!("{cam_id}"))
    }

    /// Ensure a live session exists for `cam` (spawn ffmpeg on demand).
    /// Returns the playlist path, or None when ffmpeg is unavailable or
    /// spawn failed.
    ///
    /// [stream] is `"main"` or `"sub"`. The session directory is keyed by
    /// camera id only (the fragment route resolves it that way), so asking
    /// for a different stream than the running session pulls tears it down
    /// and respawns — a second ffmpeg writing the same `cam.m3u8` would
    /// interleave two manifests.
    pub async fn ensure(
        &self,
        state: &Arc<NvrState>,
        cam: &Camera,
        stream: &str,
    ) -> Option<PathBuf> {
        let stream = if stream == "main" { "main" } else { "sub" };
        let list = self.stream_dir(cam.id).join("cam.m3u8");
        let bin = state.ffmpeg.clone()?;
        // Fast path / dedup: a session is either running, or a spawn is
        // already in flight for this camera. In the in-flight case we do NOT
        // spawn a second ffmpeg — two writers to the same m3u8 would
        // interleave and hls.js gets an unparseable manifest. The caller
        // gets `Some(list)` and its wait-for-playlist retry covers the few
        // seconds the first spawn needs.
        //
        // A *zombie* session (child already exited, e.g. RTSP died and the
        // recorder-side exit was the only thing that cleaned it up) is
        // detected here via `try_wait()` and replaced on the spot, so the
        // UI recovers on the next refresh instead of 502ing forever.
        {
            let mut sessions = self.sessions.write().await;
            match sessions.get_mut(&cam.id) {
                Some(s) if s.starting => return Some(list),
                Some(s) => match &mut s.child {
                    Some(c) => {
                        match c.try_wait() {
                            Ok(Some(status)) => {
                                // Already dead. Tear down and respawn below.
                                tracing::warn!(
                                    "live: ffmpeg for {} already exited ({status:?}); respawning",
                                    cam.name
                                );
                            }
                            Ok(None) => {
                                // Still alive — but is it the stream we were
                                // asked for? Switching main↔sub must restart,
                                // otherwise the caller keeps getting the old
                                // quality with no way to tell.
                                if s.stream == stream {
                                    s.last_hit = Instant::now();
                                    return Some(list);
                                }
                                tracing::info!(
                                    "live: {} switching stream {} → {}; restarting",
                                    cam.name,
                                    s.stream,
                                    stream
                                );
                                let _ = c.start_kill();
                            }
                            Err(e) => {
                                tracing::warn!("live: try_wait failed for {}: {e}", cam.name);
                                s.last_hit = Instant::now();
                                return Some(list);
                            }
                        }
                    }
                    None => {
                        // Placeholder without a child (shouldn't happen — the
                        // in-flight marker covers that case). Treat as dead.
                        tracing::warn!("live: empty session for {}; respawning", cam.name);
                    }
                },
                None => {}
            }
            // Insert (or replace) an in-flight placeholder; the real session
            // is inserted again after spawn.
            sessions.insert(
                cam.id,
                LiveSession {
                    cam_id: cam.id,
                    cam_name: cam.name.clone(),
                    stream: stream.to_string(),
                    child: None,
                    last_hit: Instant::now(),
                    starting: true,
                },
            );
        }
        let encoder = pick_encoder(&state.config.get().runtime.nvr.video.encoder);
        let dir = self.stream_dir(cam.id);
        if let Err(e) = tokio::fs::create_dir_all(&dir).await {
            tracing::warn!("live: mkdir {dir:?} failed: {e}");
        }
        // A restart must not leave the previous stream's playlist around —
        // the client would read a manifest whose fragments point at a
        // session that is being replaced.
        let _ = tokio::fs::remove_file(&list).await;
        let mut child = match tokio::process::Command::new(&bin)
            .args(build_live_args(cam, &encoder, &dir, stream))
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::piped())
            .kill_on_drop(true)
            .spawn()
        {
            Ok(c) => c,
            Err(e) => {
                // Roll the in-flight placeholder back so the next request
                // can try again.
                if let Ok(mut s) = self.sessions.try_write() {
                    if let Some(sess) = s.get_mut(&cam.id) {
                        sess.starting = false;
                    }
                }
                tracing::warn!("live: spawn failed for {}: {e}", cam.name);
                return None;
            }
        };
        if let Some(stderr) = child.stderr.take() {
            let cam_name = cam.name.clone();
            tokio::spawn(async move {
                use tokio::io::AsyncBufReadExt;
                let mut lines = tokio::io::BufReader::new(stderr).lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    tracing::warn!("[live:{cam_name}] ffmpeg: {line}");
                }
            });
        }
        self.sessions.write().await.insert(
            cam.id,
            LiveSession {
                cam_id: cam.id,
                cam_name: cam.name.clone(),
                stream: stream.to_string(),
                child: Some(child),
                last_hit: Instant::now(),
                starting: false,
            },
        );
        tracing::info!("live: started RTSP→HLS for {} ({stream})", cam.name);
        Some(list)
    }

    /// Mark activity (playlist or segment served) for a camera.
    pub async fn touch(&self, cam_id: i64) {
        if let Ok(mut s) = self.sessions.try_write() {
            if let Some(sess) = s.get_mut(&cam_id) {
                sess.last_hit = Instant::now();
            }
        }
    }

    /// Stop a session (kill ffmpeg) and remove its stream directory.
    pub async fn stop(&self, cam_id: i64) {
        let child = self.sessions.write().await.remove(&cam_id).and_then(|s| s.child);
        if let Some(mut c) = child {
            let _ = c.kill().await;
            let _ = c.wait().await;
        }
        let dir = self.stream_dir(cam_id);
        let _ = tokio::fs::remove_dir_all(&dir).await;
    }

    pub async fn stop_all(&self) {
        let ids: Vec<i64> = self.sessions.read().await.keys().copied().collect();
        for id in ids {
            self.stop(id).await;
        }
    }

    async fn reap(&self) {
        let stale: Vec<(i64, String)> = {
            let sessions = self.sessions.read().await;
            sessions
                .iter()
                .filter(|(_, s)| s.last_hit.elapsed() > IDLE_TTL)
                .map(|(id, s)| (*id, s.cam_name.clone()))
                .collect()
        };
        for (id, name) in stale {
            tracing::info!("live: idle session for {name} expired, stopping");
            self.stop(id).await;
        }
    }
}

/// Spawn the reaper task (runs for process lifetime) and return the hub.
pub fn spawn_hub(data_dir: &Path) -> Arc<LiveHub> {
    let live_dir = data_dir.join("live");
    std::fs::create_dir_all(&live_dir).ok();
    let hub = Arc::new(LiveHub::new(live_dir));
    let reaper = hub.clone();
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(REAP_INTERVAL);
        tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            tick.tick().await;
            reaper.reap().await;
        }
    });
    hub
}

/// Build the ffmpeg argv for a live HLS stream.
///
/// Output: `{cam_dir}/cam.m3u8` (rolling, relative segment URIs) +
/// `seg%06d.m4s` fragments. An IDR every 2s keeps fragments short;
/// `hls_list_size` makes ffmpeg drop old fragments from both the playlist
/// and disk, so the served playlist is always a bounded live window.
///
/// [stream] picks the source: `"sub"` (default for preview) or `"main"`.
pub fn build_live_args(
    cam: &Camera,
    encoder: &str,
    cam_dir: &Path,
    stream: &str,
) -> Vec<String> {
    let list = cam_dir.join("cam.m3u8");
    let seg = cam_dir.join("seg%06d.m4s");
    // Preview at 720p — live is interactive, not archival.
    let (w, h) = parse_scale("1280x720");
    // VAAPI needs the hardware *decode* flags before `-i` AND the hardware
    // filter chain after it. Mixing the software `scale`/`pad` chain with
    // `h264_vaapi` fails at filter reinit:
    //   "Impossible to convert between the formats supported by the filter
    //    'Parsed_pad_1' and the filter 'auto_scale_0'"  → -22 Invalid argument
    //   → "Could not open encoder before EOF" → nothing is ever written.
    // That is what made `/api/nvr/live/:cam` answer 500 unconditionally on
    // any box where `pick_encoder()` selects h264_vaapi. Mirrors
    // `recorder::build_args_from`, which was fixed the same way.
    let vaapi = is_vaapi(encoder);
    let mut args: Vec<String> = vec![
        "-hide_banner".into(),
        "-loglevel".into(),
        "error".into(),
        "-rtsp_transport".into(),
        "tcp".into(),
        // Global socket I/O timeout (µs); `-stimeout` no longer exists in
        // ffmpeg 7.x (container runtime) and aborts the whole arg list.
        "-timeout".into(),
        "5000000".into(),
    ];
    if vaapi {
        // INPUT options — must precede `-i`, or ffmpeg errors with
        // "you are trying to apply an input option to an output file".
        args.extend(vaapi_input_args());
    }
    args.extend([
        "-i".into(),
        // Honour the requested stream (main/sub) — the source URL is the
        // only thing that differs between the two previews.
        cam.url_for_stream(if stream == "main" { 1 } else { 2 }),
        // Output-only option (ffmpeg 7.x): must come after `-i`.
        "-max_muxing_queue_size".into(),
        "1024".into(),
    ]);
    if vaapi {
        // Frames are already VAAPI surfaces, so `scale_vaapi` keeps them on
        // the GPU; a software `scale` would force a hwdownload and give back
        // the CPU cost this path exists to avoid. The camera is 16:9, so the
        // plain (non-padding) scale does not distort.
        args.push("-vf".into());
        args.push(format!("scale_vaapi=w={w}:h={h}"));
    } else {
        args.push("-vf".into());
        args.push(format!(
            "scale={w}:{h}:force_original_aspect_ratio=decrease,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color=black"
        ));
    }
    args.push("-an".into());
    args.push("-c:v".into());
    args.push(encoder.to_string());
    if vaapi {
        args.extend([
            "-b:v".into(),
            LIVE_VAAPI_BITRATE.into(),
            "-maxrate".into(),
            LIVE_VAAPI_BITRATE.into(),
            "-bufsize".into(),
            LIVE_VAAPI_BUF.into(),
        ]);
    } else if !is_hwaccel(encoder) {
        args.extend(["-preset".into(), "ultrafast".into()]);
    }
    args.extend([
        "-force_key_frames".into(),
        format!("expr:gte(t,n_forced*{HLS_SECONDS})"),
        "-hls_time".into(),
        HLS_SECONDS.to_string(),
        "-hls_list_size".into(),
        LIST_SIZE.to_string(),
        "-hls_flags".into(),
        "independent_segments+append_list".into(),
        // This ffmpeg 7.1.5 build rejects `+fmp4` in hls_flags ("Undefined
        // constant") — the documented way to get fMP4 `.m4s` segments is the
        // dedicated segment-type option. Without it the segments are
        // MPEG-TS-in-disguise and hls.js rejects them.
        "-hls_segment_type".into(),
        "fmp4".into(),
        "-hls_segment_filename".into(),
        seg.display().to_string(),
        "-f".into(),
        "hls".into(),
        list.display().to_string(),
    ]);
    args
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::services::nvr::Camera;

    fn fake_cam() -> Camera {
        Camera {
            id: 7,
            name: "cam1".into(),
            label: None,
            rtsp_url: "rtsp://user:pw@192.168.1.9/stream1".into(),
            rtsp_sub_url: None,
            enabled: true,
            record_stream: 1,
            record_audio: false,
            preview_stream: "sub".into(),
            created_at: 0,
            updated_at: 0,
        }
    }

    #[test]
    fn live_args_shape() {
        let dir = PathBuf::from("/tmp/live/7");
        let args = build_live_args(&fake_cam(), "h264_nvenc", &dir, "sub");
        let s: Vec<&str> = args.iter().map(|a| a.as_str()).collect();
        assert!(s.contains(&"-rtsp_transport") && s.contains(&"tcp"));
        assert!(s.contains(&"-hls_time") && s.contains(&"2"));
        assert!(s.contains(&"-hls_list_size") && s.contains(&"6"));
        assert!(s.contains(&"-an"));
        assert!(!s.contains(&"-preset")); // nvenc: no preset
        assert!(s.last().unwrap().ends_with("cam.m3u8"));
    }

    #[test]
    fn live_args_soft_encoder_presets_ultrafast() {
        let dir = PathBuf::from("/tmp/live/7");
        let args = build_live_args(&fake_cam(), "libx264", &dir, "sub");
        let s: Vec<&str> = args.iter().map(|a| a.as_str()).collect();
        let preset_idx = s.iter().position(|a| *a == "-preset").unwrap();
        assert_eq!(s[preset_idx + 1], "ultrafast");
    }

    #[test]
    fn stream_dir_uses_cam_id() {
        let hub = LiveHub::new(PathBuf::from("/data/live"));
        assert_eq!(hub.stream_dir(42), PathBuf::from("/data/live/42"));
    }

    /// Regression: `h264_vaapi` + the software `scale`/`pad` chain made
    /// `/api/nvr/live/:cam` answer 500 on every box where VAAPI wins
    /// `pick_encoder()`. VAAPI must get hardware decode flags *before* `-i`
    /// and `scale_vaapi` *after* it, never the software filter.
    #[test]
    fn live_args_vaapi_uses_hardware_filters() {
        let dir = PathBuf::from("/tmp/live/7");
        let args = build_live_args(&fake_cam(), "h264_vaapi", &dir, "sub");
        let s: Vec<&str> = args.iter().map(|a| a.as_str()).collect();

        // Hardware decode flags are input options → strictly before `-i`.
        let i = s.iter().position(|a| *a == "-i").unwrap();
        let hw = s.iter().position(|a| *a == "-hwaccel").unwrap();
        assert!(hw < i, "-hwaccel must precede -i");
        assert_eq!(s[hw + 1], "vaapi");
        assert!(s.contains(&"-hwaccel_output_format"));
        assert!(s.iter().position(|a| *a == "-hwaccel_device").unwrap() < i);

        // Hardware filter chain, no software `scale`/`pad`.
        let vf = s.iter().position(|a| *a == "-vf").unwrap();
        assert_eq!(s[vf + 1], "scale_vaapi=w=1280:h=720");
        assert!(vf > i, "-vf is an output option → after -i");
        assert!(!s.iter().any(|a| a.starts_with("scale=")));
        assert!(!s.iter().any(|a| a.contains("pad=")));

        // `-b:v` is the only real quality knob on VAAPI; `-preset` is noise.
        assert!(s.contains(&"-b:v"));
        assert!(!s.contains(&"-preset"));
    }

    /// The non-VAAPI path must keep the old software filter so aspect-ratio
    /// padding still works for hw encoders whose frames are real frames.
    #[test]
    fn live_args_software_filter_unchanged_for_other_encoders() {
        let dir = PathBuf::from("/tmp/live/7");
        for enc in ["h264_nvenc", "libx264", "h264_videotoolbox"] {
            let args = build_live_args(&fake_cam(), enc, &dir, "sub");
            let s: Vec<&str> = args.iter().map(|a| a.as_str()).collect();
            let vf = s.iter().position(|a| *a == "-vf").unwrap();
            assert!(s[vf + 1].starts_with("scale=1280:720"), "{enc}");
            assert!(s[vf + 1].contains("pad=1280:720"), "{enc}");
            assert!(!s.contains(&"-hwaccel"), "{enc}");
            assert!(!s.contains(&"-b:v"), "{enc}");
        }
    }
}
