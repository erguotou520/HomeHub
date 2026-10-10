//! Per-camera RTSP recording: one tokio task per camera, each wrapping a
//! long-lived ffmpeg child that writes 60s MP4 segments into `.cache/`.
//! A manager loop (re)syncs recorders with the `cameras` table every 30s,
//! so cameras added/changed/disabled via the admin API pick up without a
//! server restart.

use std::collections::HashMap;
use std::time::Duration;

use crate::services::nvr::{Camera, NvrState, RecorderState, CAMERA_COLUMNS};

pub fn sanitize_name(name: &str) -> String {
    name.chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' { c } else { '_' })
        .collect()
}

/// The DRM render node VAAPI needs. Probed, not hardcoded: on a host without
/// `/dev/dri` (or inside a container that lacks the device mapped) this is
/// `None` and the VAAPI branch is skipped entirely.
pub const VAAPI_DEVICE: &str = "/dev/dri/renderD128";

/// True when the VAAPI render node exists AND actually opens. Existence alone
/// is not enough: an LXC/container can expose the node via a bind mount while
/// the cgroup device filter still denies access, in which case `open()` fails
/// with EPERM and ffmpeg reports `No VA display found` / EIO.
pub fn vaapi_available() -> bool {
    if std::fs::metadata(VAAPI_DEVICE).is_err() {
        return false;
    }
    // Read/write open is what libva-drm does; render nodes are 0660 root:render
    // so a non-root server needs the render group too.
    std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open(VAAPI_DEVICE)
        .is_ok()
}

/// Encoder selection: `auto` prefers GPU encoders, but only when the GPU is
/// actually usable — otherwise libx264. Probing runs at most once per manager
/// round, not per restart.
///
/// Order matters: on a CPU-only box every candidate is probed and fails, so
/// the (cheap) existence checks come first and the expensive one-frame
/// encodes only run for encoders that are actually listed.
pub fn pick_encoder(want: &str) -> String {
    if want != "auto" {
        return want.to_string();
    }
    let ffmpeg_bin = std::env::var("PATH")
        .ok()
        .and_then(|p| {
            p.split(':')
                .map(|d| std::path::Path::new(d).join("ffmpeg"))
                .find(|f| f.is_file())
        })
        .map(|f| f.display().to_string())
        .unwrap_or_else(|| "ffmpeg".into());
    let Ok(out) = std::process::Command::new(&ffmpeg_bin)
        .args(["-hide_banner", "-encoders"])
        .output()
    else {
        return "libx264".into();
    };
    if !out.status.success() {
        return "libx264".into();
    }
    let list = String::from_utf8_lossy(&out.stdout);
    // The encoder list alone is NOT sufficient: a Debian ffmpeg ships
    // h264_nvenc compiled in, but in a container without the NVIDIA driver
    // (no libcuda) it only fails *when opening the encoder* — which for the
    // recorder means a silent, endless restart loop. So additionally probe
    // whether the GPU path actually works with a one-frame encode.
    // Prefer NVENC on x86-64 Linux boxes (docker GPU passthrough), then QSV
    // (Intel iGPU), then VAAPI (AMD/Intel iGPU render node), then macOS
    // videotoolbox, else soft x264.
    if list.contains("h264_nvenc") && probe_encoder(&ffmpeg_bin, "h264_nvenc", &[]) {
        return "h264_nvenc".into();
    }
    if list.contains("h264_qsv") && probe_encoder(&ffmpeg_bin, "h264_qsv", &[]) {
        return "h264_qsv".into();
    }
    // VAAPI needs the render node to be openable AND the Mesa VA driver to be
    // present. The image ships libva.so.2 but not radeonsi_drv_video.so, so
    // the render node can exist and open while libva still fails with EIO —
    // hence probe with a real encode rather than trusting the file check.
    if list.contains("h264_vaapi") && vaapi_available() && probe_encoder(&ffmpeg_bin, "h264_vaapi", &vaapi_input_args())
    {
        return "h264_vaapi".into();
    }
    if list.contains("h264_videotoolbox")
        && probe_encoder(&ffmpeg_bin, "h264_videotoolbox", &[])
    {
        return "h264_videotoolbox".into();
    }
    "libx264".into()
}

/// Input-side options that put ffmpeg into VAAPI hardware-decode mode.
/// These MUST precede `-i`; ffmpeg rejects them as "input option applied to
/// output url" otherwise.
pub fn vaapi_input_args() -> Vec<String> {
    vec![
        "-hwaccel".into(),
        "vaapi".into(),
        "-hwaccel_device".into(),
        VAAPI_DEVICE.into(),
        "-hwaccel_output_format".into(),
        "vaapi".into(),
    ]
}

/// Is this encoder a VAAPI one? Needs the hardware filter chain, not the
/// software `scale`/`pad` one.
pub fn is_vaapi(encoder: &str) -> bool {
    encoder.contains("vaapi")
}

/// One-frame encode probe: does this encoder actually open on this box?
/// `extra` holds input-side options (VAAPI decode flags); they must go
/// before the synthetic `-i`.
fn probe_encoder(ffmpeg_bin: &str, encoder: &str, extra: &[String]) -> bool {
    let mut args: Vec<String> = vec!["-hide_banner".into(), "-loglevel".into(), "error".into()];
    args.extend_from_slice(extra);
    args.extend([
        "-f".into(),
        "lavfi".into(),
        "-i".into(),
        "color=c=black:s=128x128:d=0.1".into(),
        "-c:v".into(),
        encoder.into(),
    ]);
    // VAAPI encoders reject `-f null`; give them a real MP4 sink.
    if is_vaapi(encoder) {
        args.extend([
            "-vf".into(),
            "format=nv12,hwupload".into(),
            "-f".into(),
            "mp4".into(),
            "-y".into(),
            std::env::temp_dir()
                .join("homehub-vaapi-probe.mp4")
                .display()
                .to_string(),
        ]);
    } else {
        args.extend(["-f".into(), "null".into(), "-".into()]);
    }
    let Ok(out) = std::process::Command::new(ffmpeg_bin).args(&args).output() else {
        return false;
    };
    let ok = out.status.success();
    if is_vaapi(encoder) {
        let _ = std::fs::remove_file(std::env::temp_dir().join("homehub-vaapi-probe.mp4"));
    }
    ok
}

pub fn is_hwaccel(encoder: &str) -> bool {
    encoder.contains("nvenc") || encoder.contains("qsv") || encoder.contains("videotoolbox")
}

/// Build the full ffmpeg argv for one camera.
///
/// Thin wrapper so the argv builder stays a pure function of (config, cache
/// dir) — unit tests then need no Db pool, no Tokio runtime, no live hub.
pub fn build_ffmpeg_args(state: &NvrState, cam: &Camera, encoder: &str) -> Vec<String> {
    let nvr = state.config.get().runtime.nvr.clone();
    build_args_from(&nvr, &state.cache_dir, cam, encoder)
}

/// The real argv builder. Split out for testability: everything it needs comes
/// in as arguments, so no server state has to be constructed to exercise it.
pub fn build_args_from(
    nvr: &crate::config::NvrConfig,
    cache_dir: &std::path::Path,
    cam: &Camera,
    encoder: &str,
) -> Vec<String> {
    let video = nvr.video.clone();
    let seg = nvr.segment_secs.max(10) as i64;
    let cache = cache_dir.join(format!("{}-%Y%m%d%H%M%S.mp4", sanitize_name(&cam.name)));
    // Note: the output template IS the segment pattern (ffmpeg has no
    // -segment_filename option); strftime expands at each segment rollover.
    let mut args: Vec<String> = vec![
        "-hide_banner".into(),
        "-loglevel".into(),
        "error".into(),
        "-rtsp_transport".into(),
        "tcp".into(),
        // Global socket I/O timeout (µs). (`-stimeout` was removed from the
        // global option list in modern ffmpeg — it only ever worked on the
        // RTSP protocol; the container ships ffmpeg 7.x where it is rejected.)
        "-timeout".into(),
        "5000000".into(),
    ];

    let vaapi = is_vaapi(encoder);
    if vaapi {
        // Hardware decode of the incoming HEVC stream. These are INPUT options
        // and must precede `-i`, or ffmpeg errors with "you are trying to
        // apply an input option to an output file".
        args.extend(vaapi_input_args());
    }
    args.extend([
        "-i".into(),
        // Honour `record_stream` (1 = main, 2 = sub). Until 011 this field was
        // stored but never read, so every camera recorded the main stream no
        // matter what the admin picked.
        cam.url_for_stream(cam.record_stream),
        // Output-only option (ffmpeg 7.x): must come after `-i`.
        "-max_muxing_queue_size".into(),
        "1024".into(),
    ]);

    // Scale to target (1920x1080 default), never upscale, black-pad to the
    // exact target so all segments of a camera share one resolution.
    let (w, h) = parse_scale(&video.scale);
    if vaapi {
        // Frames are already VAAPI surfaces here, so `scale_vaapi` keeps them
        // on the GPU. A software `scale` would force a hwdownload and give
        // back the CPU cost this path exists to avoid.
        args.push("-vf".into());
        args.push(format!("scale_vaapi=w={w}:h={h}"));
    } else {
        args.push("-vf".into());
        args.push(format!(
            "scale={w}:{h}:force_original_aspect_ratio=decrease,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color=black"
        ));
    }
    if video.fps > 0 {
        args.push("-r".into());
        args.push(video.fps.to_string());
    }

    args.push("-c:v".into());
    args.push(encoder.to_string());
    if vaapi {
        // `-crf` and `-preset` are ACCEPTED BUT SILENTLY IGNORED by
        // h264_vaapi: measured on this box, `-b:v 2M` with and without
        // `-crf 28 -preset veryfast` produced byte-identical output (same
        // md5), while changing `-b:v` did change it. So bitrate is the only
        // knob that actually works here — pass the configured maxrate as the
        // target bitrate instead of pretending CRF is in effect.
        args.push("-b:v".into());
        args.push(video.maxrate.clone());
        args.push("-maxrate".into());
        args.push(video.maxrate.clone());
        args.push("-bufsize".into());
        args.push(video.bufsize.clone());
    } else {
        if !is_hwaccel(encoder) {
            args.push("-preset".into());
            args.push(video.preset.clone());
        }
        args.push("-crf".into());
        args.push(video.crf.to_string());
        args.push("-maxrate".into());
        args.push(video.maxrate.clone());
        args.push("-bufsize".into());
        args.push(video.bufsize.clone());
    }
    // Force an IDR at every segment boundary so each MP4 starts on a key
    // frame (independently playable, HlsMediaSource-safe).
    args.push("-force_key_frames".into());
    args.push(format!("expr:gte(t,n_forced*{seg})"));

    // Audio: MP4 cannot hold G711, so "on" means re-encode to AAC 32kbps.
    if cam.record_audio {
        args.extend([
            "-c:a".into(),
            "aac".into(),
            "-ar".into(),
            "8000".into(),
            "-ac".into(),
            "1".into(),
            "-b:a".into(),
            "32k".into(),
        ]);
    } else {
        args.push("-an".into());
    }

    args.extend([
        "-avoid_negative_ts".into(),
        "make_zero".into(),
        "-f".into(),
        "segment".into(),
        "-strftime".into(),
        "1".into(),
        "-segment_time".into(),
        seg.to_string(),
        "-reset_timestamps".into(),
        "1".into(),
    ]);
    args.push(cache.display().to_string());
    args
}

pub fn parse_scale(s: &str) -> (i64, i64) {
    let mut parts = s.split('x');
    let w = parts.next().and_then(|v| v.trim().parse().ok()).unwrap_or(1920);
    let h = parts.next().and_then(|v| v.trim().parse().ok()).unwrap_or(1080);
    (w, h)
}

/// Spawn (or respawn) ffmpeg for `cam`, replacing any existing child.
async fn start_recorder(state: &std::sync::Arc<NvrState>, cam: &Camera, encoder: &str) -> bool {
    let Some(bin) = &state.ffmpeg else {
        return false;
    };
    let args = build_ffmpeg_args(state, cam, encoder);
    let tz = state.config.get().runtime.nvr.timezone.clone();
    let mut child = match tokio::process::Command::new(bin)
        .args(&args)
        // ffmpeg's `-strftime` expands `%Y%m%d%H%M%S` in the process's LOCAL
        // time. The container runs in UTC, so without this the segment is named
        // in UTC while `maintainer::parse_cache_name` reads that name back as
        // `runtime.nvr.timezone` — a silent 8-hour shift on every timestamp and
        // directory path. Pin the child to the configured zone so the write and
        // the read agree.
        .env("TZ", &tz)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::piped())
        .kill_on_drop(true)
        .spawn()
    {
        Ok(c) => c,
        Err(e) => {
            mark(state, cam.id, "down", format!("spawn: {e}")).await;
            return false;
        }
    };
    // Drain stderr into tracing (ffmpeg logs errors here).
    if let Some(stderr) = child.stderr.take() {
        let cam_name = cam.name.clone();
        tokio::spawn(async move {
            use tokio::io::AsyncBufReadExt;
            let mut lines = tokio::io::BufReader::new(stderr).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                tracing::warn!("[nvr:{cam_name}] ffmpeg: {line}");
            }
        });
    }

    let mut recorders = state.recorders.write().await;
    let entry = recorders.entry(cam.id).or_insert_with(RecorderState::default);
    entry.child = Some(child);
    entry.status = "recording".into();
    entry.last_error = None;
    true
}

async fn mark(state: &NvrState, id: i64, status: &str, err: String) {
    let mut recorders = state.recorders.write().await;
    let entry = recorders.entry(id).or_insert_with(RecorderState::default);
    entry.status = status.into();
    entry.last_error = Some(err);
}

async fn stop_recorder(state: &NvrState, id: i64) {
    let child = {
        let mut recorders = state.recorders.write().await;
        recorders.get_mut(&id).and_then(|r| r.child.take())
    };
    if let Some(mut c) = child {
        // Give ffmpeg ~10s to finalize the in-flight segment; then kill.
        let _ = tokio::time::timeout(Duration::from_secs(10), c.wait()).await;
        let _ = c.kill().await;
    }
    let mut recorders = state.recorders.write().await;
    if let Some(entry) = recorders.get_mut(&id) {
        entry.status = "disabled".into();
    }
}

async fn restart_recorder(state: &std::sync::Arc<NvrState>, id: i64) {
    stop_recorder(state, id).await;
    // The manager loop will pick the camera back up on its next round; the
    // explicit restart is only a "nudge now" — but do it inline for latency.
    let Ok(cam) = super::get_camera(&state.db, id).await else {
        return;
    };
    if cam.enabled {
        let cfg = state.config.get().runtime.nvr.clone();
        let encoder = pick_encoder(&cfg.video.encoder);
        if start_recorder(state, &cam, &encoder).await {
            tracing::info!("nvr: restarted recorder for {}", cam.name);
        }
    }
}

/// The long-running manager: every 30s, diff `cameras` table against live
/// recorders — start new, stop removed/disabled, restart changed.
pub async fn manage_loop(state: std::sync::Arc<NvrState>) {
    // Cache BOTH the configured value and the probe result. Comparing the
    // result against the config (`libx264` vs `auto`) never matches, which
    // re-ran the full probe — several ffmpeg subprocesses — every 30 s.
    let mut encoder_cache: Option<(String, String)> = None;
    loop {
        tokio::time::sleep(Duration::from_secs(30)).await;
        let Ok(rows) = sqlx::query_as::<_, Camera>(&format!(
            "SELECT {CAMERA_COLUMNS} FROM cameras"
        ))
        .fetch_all(&state.db)
        .await
        else {
            continue;
        };

        let cfg = state.config.get().runtime.nvr.clone();
        let want = cfg.video.encoder.clone();
        let encoder = match &encoder_cache {
            Some((cached_want, enc)) if *cached_want == want => enc.clone(),
            _ => {
                let e = pick_encoder(&want);
                tracing::info!("nvr: using encoder {e} (configured: {want})");
                encoder_cache = Some((want, e.clone()));
                e
            }
        };

        let mut recorders = state.recorders.write().await;
        let want: HashMap<i64, &Camera> = rows.iter().map(|c| (c.id, c)).collect();

        // Stop recorders whose camera vanished or was disabled.
        let dead: Vec<i64> = recorders
            .keys()
            .copied()
            .filter(|id| !want.get(id).map(|c| c.enabled).unwrap_or(false))
            .collect();
        drop(recorders);
        for id in dead {
            stop_recorder(&state, id).await;
            state.recorders.write().await.remove(&id);
        }

        // Zombie check: a child that exited on its own still leaves
        // `status="recording"` behind, and the sync below would trust it and
        // never respawn ffmpeg — recording dies silently for good. Reap such
        // children here so the normal (re)start path picks them up this round.
        {
            let mut recorders = state.recorders.write().await;
            let cam_ids: Vec<i64> = recorders.keys().copied().collect();
            for id in cam_ids {
                let Some(entry) = recorders.get_mut(&id) else {
                    continue;
                };
                if entry.status != "recording" || entry.child.is_none() {
                    continue;
                }
                let mut child = match entry.child.take() {
                    Some(c) => c,
                    None => continue,
                };
                match child.try_wait() {
                    // `try_wait` polls once and never suspends, so holding the
                    // lock across it is safe.
                    Ok(Some(_)) => {
                        tracing::info!("nvr: recorder for {} exited, will restart", id);
                        entry.status = "down".into();
                        entry.last_error = Some("ffmpeg exited".into());
                    }
                    Ok(None) => entry.child = Some(child),
                    Err(e) => {
                        entry.status = "down".into();
                        entry.last_error = Some(format!("reap: {e}"));
                    }
                }
            }
        }

        // Re-read after stop (lock was released).
        let current = {
            let guard = state.recorders.read().await;
            let mut m = std::collections::HashMap::new();
            for (k, v) in guard.iter() {
                m.insert(*k, v.status.clone());
            }
            m
        };
        for cam in rows.iter().filter(|c| c.enabled) {
            let running = current.get(&cam.id).map(|s| s == "recording").unwrap_or(false);
            if running {
                continue;
            }
            // (Re)start with backoff; a camera that keeps dying restarts at
            // most once per manager round (30s) — good enough for v1.
            if start_recorder(&state, cam, &encoder).await {
                let mut recorders = state.recorders.write().await;
                if let Some(e) = recorders.get_mut(&cam.id) {
                    e.restart_count += 1;
                }
                tracing::info!("nvr: started recorder for {}", cam.name);
            }
        }
        // Mark disabled-but-kept entries.
        let mut recorders = state.recorders.write().await;
        for (id, entry) in recorders.iter_mut() {
            if !want.get(id).map(|c| c.enabled).unwrap_or(false) && entry.status != "disabled" {
                entry.status = "disabled".into();
            }
        }
    }
}

pub async fn restart_camera(state: &std::sync::Arc<NvrState>, id: i64) -> bool {
    restart_recorder(state, id).await;
    true
}

/// Stop a camera's recorder and drop its status entry (used on delete).
pub async fn stop_camera(state: &std::sync::Arc<NvrState>, id: i64) {
    stop_recorder(state, id).await;
    state.recorders.write().await.remove(&id);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::NvrConfig;
    use std::path::PathBuf;

    /// Build argv straight from `build_args_from` — no `NvrState`, so no Db
    /// pool (which needs a Tokio runtime even when lazy), no live hub, no
    /// filesystem. Defaults mirror the shipped config.
    fn args(encoder: &str) -> Vec<String> {
        build_args_from(
            &NvrConfig::default(),
            PathBuf::from("/tmp/rec/.cache").as_path(),
            &test_camera(),
            encoder,
        )
    }

    fn test_camera() -> Camera {
        Camera {
            id: 1,
            name: "main".into(),
            label: None,
            rtsp_url: "rtsp://cam/stream1".into(),
            rtsp_sub_url: None,
            enabled: true,
            record_stream: 0,
            record_audio: false,
            preview_stream: "sub".into(),
            created_at: 0,
            updated_at: 0,
        }
    }

    #[test]
    fn sanitize_keeps_valid_chars() {
        assert_eq!(sanitize_name("home-1_"), "home-1_");
        assert_eq!(sanitize_name("客厅"), "__");
    }

    #[test]
    fn parse_scale_variants() {
        assert_eq!(parse_scale("1920x1080"), (1920, 1080));
        assert_eq!(parse_scale("1280x720"), (1280, 720));
        assert_eq!(parse_scale("bogus"), (1920, 1080));
    }

    #[test]
    fn hwaccel_detection() {
        assert!(is_hwaccel("h264_nvenc"));
        assert!(is_hwaccel("h264_qsv"));
        assert!(is_hwaccel("h264_videotoolbox"));
        assert!(!is_hwaccel("libx264"));
    }

    #[test]
    fn vaapi_detection() {
        assert!(is_vaapi("h264_vaapi"));
        assert!(!is_vaapi("h264_nvenc"));
        assert!(!is_vaapi("libx264"));
    }

    /// VAAPI decode flags are INPUT options: they must land before `-i`, or
    /// ffmpeg aborts with "input option applied to an output url".
    #[test]
    fn vaapi_input_args_precede_input() {
        let args = args("h264_vaapi");
        let pos_dev = args
            .iter()
            .position(|a| a == "-hwaccel_device")
            .expect("hwaccel_device present");
        let pos_i = args.iter().position(|a| a == "-i").expect("-i present");
        assert!(pos_dev < pos_i, "VAAPI device must be declared before -i");
        // And the device path must be the render node, not card0.
        let dev = &args[pos_dev + 1];
        assert!(dev.ends_with("renderD128"), "unexpected device: {dev}");
    }

    /// VAAPI frames are already GPU surfaces — a software `scale` would force
    /// a hwdownload and throw away the whole point of the path.
    #[test]
    fn vaapi_uses_hardware_scale_only() {
        let args = args("h264_vaapi");
        let vf = args.iter().position(|a| a == "-vf").expect("-vf present");
        let filter = &args[vf + 1];
        assert!(filter.starts_with("scale_vaapi="), "got: {filter}");
        assert!(!filter.contains("pad="), "pad is a software filter: {filter}");
    }

    /// Regression guard for the measured quirk: h264_vaapi accepts `-crf`
    /// and `-preset` but ignores them (byte-identical output with and
    /// without). Bitrate must therefore be passed explicitly, and the
    /// x264-only knobs must NOT be emitted.
    #[test]
    fn vaapi_uses_bitrate_not_crf() {
        let args = args("h264_vaapi");
        assert!(args.iter().any(|a| a == "-b:v"), "no -b:v: {args:?}");
        assert!(!args.iter().any(|a| a == "-crf"), "-crf ignored by vaapi");
        assert!(!args.iter().any(|a| a == "-preset"), "-preset ignored by vaapi");
    }

    /// The software path must keep its CRF/preset knobs — switching to
    /// bitrate-only would silently change quality and file size.
    #[test]
    fn software_path_keeps_crf_and_preset() {
        let args = args("libx264");
        let vf = args.iter().position(|a| a == "-vf").expect("-vf present");
        assert!(args[vf + 1].contains("pad="), "soft path should pad");
        assert!(args.iter().any(|a| a == "-crf"));
        assert!(args.iter().any(|a| a == "-preset"));
        assert!(!args.iter().any(|a| a == "vaapi"), "no vaapi flags: {args:?}");
    }

    /// Every segment must still open on a keyframe, or the player can't
    /// start mid-file. This is the one rate-control knob VAAPI honours.
    #[test]
    fn vaapi_forces_keyframe_at_segment_boundary() {
        let args = args("h264_vaapi");
        let pos = args
            .iter()
            .position(|a| a == "-force_key_frames")
            .expect("force_key_frames present");
        assert!(args[pos + 1].contains("n_forced"), "got: {}", args[pos + 1]);
    }
}
