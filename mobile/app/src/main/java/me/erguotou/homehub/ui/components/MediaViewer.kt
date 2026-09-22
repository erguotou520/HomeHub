package me.erguotou.homehub.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import me.erguotou.homehub.R
import me.erguotou.homehub.util.formatDuration

/**
 * Full-screen media surfaces shared by the two viewers.
 *
 * The album viewer (`screens/album/ViewerScreen.kt`) and the 文件 tab's viewer
 * (`screens/files/FileViewerScreen.kt`) render the same kind of content from
 * two different sources — `/api/photos/:id/raw` versus `/api/files/:dir/:path`
 * — so the actual "viewing program" (pinch/double-tap zoom, the Media3
 * controller, the info panel) lives here and both call into it. Only the URL
 * builder and the surrounding chrome differ.
 */

/**
 * Height the Media3 controller's bottom section occupies (seek bar + time row
 * + settings button), measured on the realme at 474dpi. Video pages have no
 * bottom toolbar of their own, so anything floating above them has to clear
 * this strip.
 */
val VideoControllerInset = 64.dp

/** Unwrap the hosting Activity from a Compose view's (possibly wrapped) context. */
tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

/** Touch target height of the audio seek bar (the bar itself is ~4dp). */
private val SeekTouchHeight = 40.dp

/**
 * Tonearm geometry, as fractions of the turntable stage's shorter side.
 *
 * The pivot sits just inside the stage's top-right corner rather than on the
 * record, which is what makes the arm read as a real tonearm and — more
 * importantly — what gives the parked position somewhere to swing to. The
 * swivel is also why the stage keeps a ring of bare table around the disc
 * instead of hugging it: a stylus that never leaves the platter does not look
 * parked, it looks stuck.
 */
private const val TonearmPivotX = 0.93f
private const val TonearmPivotY = 0.075f

/** Angle from the pivot to the stylus, in screen degrees (y grows downwards). */
private const val TonearmEngagedDegrees = 134f

/**
 * How far the arm swings away from the engaged position when paused. Tuned so
 * the whole headshell clears the record's edge by a visible margin: measured
 * on device, at 44° the stylus was off the disc but the headshell still grazed
 * the groove band, and much past 48° the arm starts to read as flung rather
 * than lifted. (The engaged angle points almost straight at the spindle, so
 * any swing at all moves the stylus away from the centre; 48° is where the arm
 * is finally clear of the disc, which is what "parked" has to look like.)
 */
private const val TonearmParkedSwing = 48f

/** Pivot → stylus distance, as a fraction of the stage's shorter side. */
private const val TonearmReach = 0.345f

/**
 * Zoomable still image.
 *
 * The gesture loop deliberately only consumes when it is actually zooming
 * (two fingers) or already zoomed in. A single-finger drag at 1x is left
 * unconsumed so the surrounding pager can page between items — attaching both
 * `transformable` and `detectTransformGestures` to the full-size box made them
 * fight each other and killed both paging and pinch-zoom.
 */
@Composable
fun ImagePage(
    key: Any,
    url: String,
    contentDescription: String?,
    onZoomChanged: (Boolean) -> Unit
) {
    var scale by remember(key) { mutableFloatStateOf(1f) }
    var offsetX by remember(key) { mutableFloatStateOf(0f) }
    var offsetY by remember(key) { mutableFloatStateOf(0f) }

    DisposableEffect(key) {
        onDispose { onZoomChanged(false) }
    }
    LaunchedEffect(scale) { onZoomChanged(scale > 1.05f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(key) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        if (scale > 1.05f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2.5f
                            offsetX = (size.width / 2f - tapOffset.x) * (scale - 1f) / scale
                            offsetY = (size.height / 2f - tapOffset.y) * (scale - 1f) / scale
                        }
                    }
                )
            }
            .pointerInput(key) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var multiTouch = false
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1) multiTouch = true
                        // Consume only when we are really transforming, so the
                        // pager still receives plain single-finger swipes.
                        if (multiTouch || scale > 1.01f) {
                            val next = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            if (next <= 1.01f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = next
                                val pan = event.calculatePan()
                                val maxX = size.width * (scale - 1f) / 2f
                                val maxY = size.height * (scale - 1f) / 2f
                                offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        )
    }
}

/**
 * Inline video player (Media3/ExoPlayer). The PlayerView controller offers
 * play/pause, seek bar and time; playback pauses when swiped away and the
 * player is released when the page leaves composition.
 *
 * There is deliberately NO Compose gesture layer stacked over the player:
 * a full-size `pointerInput` Box beats an `AndroidView` in the hit test, so
 * the interop view underneath never sees a touch and every tap (play, seek
 * bar, settings) gets swallowed. Videos keep the platform's own controls.
 */
@Composable
fun VideoPage(
    key: Any,
    url: String,
    isCurrentPage: Boolean
) {
    val context = LocalContext.current
    var playbackError by remember(key) { mutableStateOf<String?>(null) }

    val player = remember(key) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            playWhenReady = false
            addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    playbackError = error.errorCodeName
                }
            })
            prepare()
        }
    }

    DisposableEffect(key) {
        onDispose { player.release() }
    }

    // Swipe away pauses; coming back keeps the position (doesn't auto-resume,
    // mirroring the system gallery's manual-play behaviour).
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage && player.isPlaying) player.pause()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                LayoutInflater.from(ctx).inflate(R.layout.hh_player_view, null) as PlayerView
            },
            update = { view -> view.player = player },
            modifier = Modifier.fillMaxSize()
        )
        playbackError?.let { err ->
            Surface(color = Color.Black.copy(alpha = 0.7f)) {
                Text(
                    "该格式暂不支持在线播放（$err）",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

/**
 * What the viewer remembers about a track that is no longer on screen.
 *
 * A pager page dies the moment it scrolls out of view and takes its ExoPlayer
 * with it, so without somewhere to keep the position, stepping away from a song
 * and coming back re-opened the file at 0:00. The viewer keeps one of these for
 * as long as it is open: leaving a track parks it exactly where it was — paused,
 * so it cannot be heard from under the next file — and returning carries on from
 * that point.
 *
 * Only an *interrupted* track (one that was playing when the user stepped away)
 * resumes on its own. A track the user paused by hand stays paused where they
 * left it, and a track that has never played still opens silently — the "no
 * autoplay" rule survives.
 */
class AudioPlaybackMemory {
    private val positions = mutableMapOf<Any, Long>()
    private var interrupted: Any? = null

    /** Where [key] was last parked, or null when it has never been open. */
    fun positionOf(key: Any): Long? = positions[key]

    /**
     * Note where [key] is and whether the user expects to hear it, then stop it.
     * Called both when the page stops being the visible one and when it is torn
     * down, so it has to stay repeatable: pausing twice must not forget that the
     * track was playing.
     */
    fun park(key: Any, player: ExoPlayer) {
        if (player.playbackState == Player.STATE_ENDED) {
            // Ran to the end: the next visit should start over rather than sit
            // on the last millisecond pretending it could carry on.
            positions[key] = 0L
            if (interrupted == key) interrupted = null
        } else {
            positions[key] = player.currentPosition.coerceAtLeast(0L)
            if (player.isPlaying || player.playWhenReady) interrupted = key
        }
        player.pause()
    }

    /** Whether [key] is coming back mid-song. Consumes the flag. */
    fun consumeResume(key: Any): Boolean {
        if (interrupted != key) return false
        interrupted = null
        return true
    }
}

/**
 * Audio page: the same Media3 engine as [VideoPage], but rendered as a music
 * surface instead of a bare video window — a spinning disc with the track's
 * cover (embedded art → a sibling `cover.jpg`/`folder.jpg` → a built-in
 * default), title/artist/album from the file's own tags, a seek bar that is
 * *always* on screen and a transport row.
 *
 * Three things this deliberately does not do anymore:
 *
 *  * it no longer hands the page over to `PlayerView`. That interop view is a
 *    plain legacy View, so a horizontal drag on its seek bar was never
 *    consumed at the Compose level and the surrounding `HorizontalPager`
 *    happily stole the gesture — scrubbing a track flipped to the next file.
 *    Every control here is a Compose control, so the slider consumes its own
 *    drags; [onScrubbingChanged] additionally freezes the pager for the
 *    duration of the drag as a belt-and-braces guard.
 *  * it has no auto-hiding controller, so position and duration are polled and
 *    shown permanently.
 *  * it does not start on its own. A track is silent until it is asked for, and
 *    stepping away pauses it rather than letting it play on under the next
 *    file — [memory] hands the position back on the return trip.
 *
 * [onPrev]/[onNext] may be null (first/last page) which greys the button out.
 */
@Composable
fun AudioPage(
    key: Any,
    url: String,
    title: String,
    subtitle: String?,
    isCurrentPage: Boolean,
    memory: AudioPlaybackMemory,
    coverUrl: String? = null,
    onScrubbingChanged: (Boolean) -> Unit = {},
    onPrev: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var playbackError by remember(key) { mutableStateOf<String?>(null) }
    var isPlaying by remember(key) { mutableStateOf(false) }
    // Seeded from the memory so a track the user comes back to shows the right
    // clock immediately instead of flashing 0:00 until the player catches up.
    var positionMs by remember(key) { mutableLongStateOf(memory.positionOf(key) ?: 0L) }
    var durationMs by remember(key) { mutableLongStateOf(0L) }
    var scrubbing by remember(key) { mutableStateOf(false) }
    var scrubMs by remember(key) { mutableLongStateOf(0L) }
    /**
     * Set on release for as long as the player has not caught up with the
     * seek. ExoPlayer keeps reporting the *old* position for a moment after
     * `seekTo` — without this the thumb snapped back to where the track had
     * been, which is what made scrubbing feel broken. A track that opens on a
     * remembered position starts out pending for the same reason.
     */
    var pendingSeekMs by remember(key) {
        mutableStateOf(memory.positionOf(key)?.takeIf { it > 0L })
    }
    var art by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    var tagTitle by remember(key) { mutableStateOf<String?>(null) }
    var tagArtist by remember(key) { mutableStateOf<String?>(null) }
    var tagAlbum by remember(key) { mutableStateOf<String?>(null) }

    val player = remember(key) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            // No autoplay: opening a folder of tracks should not start
            // shouting at the user, and the page is only ever one swipe away
            // from someone else's audio. [memory] decides separately whether
            // this particular track is coming back mid-song.
            playWhenReady = false
            // Queued before `prepare()` on purpose: a fresh player reports 0
            // until it is ready, and starting at 0:00 for a track the user is
            // returning to is the bug this whole dance exists to fix.
            memory.positionOf(key)?.let { if (it > 0L) seekTo(it) }
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackError = error.errorCodeName
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                /**
                 * The extractor hands the file's own tags back as metadata —
                 * including embedded cover art (ID3 APIC, FLAC pictures, MP4
                 * `covr`) — so the disc gets a real cover with no server help.
                 */
                override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                    tagTitle = metadata.title?.toString()?.takeIf { it.isNotBlank() }
                    tagArtist = metadata.artist?.toString()?.takeIf { it.isNotBlank() }
                    tagAlbum = metadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }
                    art = metadata.artworkData?.let { bytes ->
                        runCatching {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        }.getOrNull()
                    }
                }
            })
            prepare()
        }
    }

    // The page is going away for good: note where the track got to before the
    // player — and with it the position — is thrown out.
    DisposableEffect(key) {
        onDispose {
            memory.park(key, player)
            player.release()
            // The page can be disposed mid-drag (swiped away, viewer closed);
            // releasing the lock here keeps the pager from freezing forever.
            onScrubbingChanged(false)
        }
    }

    // Leaving the page pauses the track — a song must never be heard from under
    // the next file — and remembers where it was. Coming back picks that
    // position up again, and plays on by itself only when the user was in the
    // middle of listening.
    //
    // Parking lives here rather than only in the disposal above because the
    // pager composes the neighbour during a drag, and a page that is built but
    // never becomes the visible one has to be parked too (and has to keep what
    // the memory already knew about it).
    LaunchedEffect(key, isCurrentPage) {
        if (isCurrentPage) {
            if (memory.consumeResume(key)) player.play()
        } else {
            memory.park(key, player)
        }
    }

    // No controller to hide, so drive the two time labels from a poll. While
    // the thumb is held the user's value wins, and for a moment after the
    // release the seek target wins — otherwise the poll would fight the drag
    // and snap the thumb back to a position the user did not ask for.
    LaunchedEffect(key) {
        var settleTicks = 0
        while (true) {
            val d = player.duration
            durationMs = if (d == C.TIME_UNSET || d < 0L) 0L else d
            if (!scrubbing) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                val target = pendingSeekMs
                if (target == null) {
                    positionMs = pos
                    settleTicks = 0
                } else {
                    settleTicks++
                    // Either the player landed on the target, or it never
                    // will (a stream that refuses to seek) — don't keep the
                    // label pinned forever.
                    if (kotlin.math.abs(pos - target) < SeekSettleSlackMs || settleTicks > 12) {
                        pendingSeekMs = null
                        positionMs = pos
                        settleTicks = 0
                    }
                }
            }
            delay(250)
        }
    }

    // The disc turns while playing and freezes in place when paused: the
    // Animatable keeps its current angle when this effect is cancelled.
    val angle = remember(key) { Animatable(0f) }
    LaunchedEffect(key, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            angle.animateTo(
                targetValue = angle.value + 360f,
                animationSpec = tween(durationMillis = 16000, easing = LinearEasing)
            )
        }
    }

    val displayTitle = tagTitle ?: title
    val metaLine = listOfNotNull(tagArtist, tagAlbum).joinToString(" · ")

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // The stage is the whole turntable, not just the record: the disc
            // sits in the middle of it and the tonearm pivots over the ring
            // around it. So `discSize` is a fraction of the stage rather than
            // of the screen, and comes out a shade smaller than it used to be.
            val stageSize = minOf(maxWidth - 56.dp, maxHeight * 0.48f).coerceAtLeast(168.dp)
            val discSize = stageSize * 0.76f
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, top = 64.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier.size(stageSize),
                        contentAlignment = Alignment.Center
                    ) {
                        Disc(
                            discSize = discSize,
                            angle = angle.value,
                            art = art,
                            coverUrl = coverUrl
                        )
                        Tonearm(engaged = isPlaying)
                    }
                }

                Text(
                    displayTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp)
                )
                Text(
                    metaLine.ifBlank { subtitle ?: "" },
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp)
                )

                // Always-visible seek bar. Hand-rolled rather than a Material
                // slider on purpose: every position change is consumed here,
                // so the surrounding pager can never steal the drag, and a
                // touch anywhere on the track jumps straight to that spot
                // instead of only working when the thumb is caught exactly.
                SeekBar(
                    positionMs = if (scrubbing) scrubMs else positionMs,
                    durationMs = durationMs,
                    scrubbing = scrubbing,
                    onScrubStart = {
                        scrubbing = true
                        scrubMs = positionMs
                        onScrubbingChanged(true)
                    },
                    onScrubTo = { ms ->
                        scrubMs = ms
                        positionMs = ms
                    },
                    onScrubEnd = {
                        player.seekTo(scrubMs)
                        positionMs = scrubMs
                        pendingSeekMs = scrubMs
                        scrubbing = false
                        onScrubbingChanged(false)
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        clock(if (scrubbing) scrubMs else positionMs),
                        color = Color.LightGray,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        if (durationMs > 0L) clock(durationMs) else "--:--",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onPrev?.invoke() },
                        enabled = onPrev != null,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = "上一个文件",
                            tint = Color.White.copy(alpha = if (onPrev != null) 0.9f else 0.28f),
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Surface(
                        onClick = {
                            when {
                                player.playbackState == Player.STATE_ENDED -> {
                                    player.seekTo(0)
                                    player.play()
                                }
                                player.isPlaying -> player.pause()
                                else -> player.play()
                            }
                        },
                        shape = CircleShape,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 20.dp).size(68.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "暂停" else "播放",
                                tint = Color(0xFF101013),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = { onNext?.invoke() },
                        enabled = onNext != null,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = "下一个文件",
                            tint = Color.White.copy(alpha = if (onNext != null) 0.9f else 0.28f),
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }
        }
        playbackError?.let { err ->
            Surface(color = Color.Black.copy(alpha = 0.7f)) {
                Text(
                    "该音频暂不支持在线播放（$err）",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

/** "0:00"-style clock; [formatDuration] is blank for unknown lengths. */
private fun clock(ms: Long): String = formatDuration(ms).ifBlank { "0:00" }

/**
 * How close the reported position has to get to a seek target before we trust
 * the player again (see `pendingSeekMs` in [AudioPage]).
 */
private const val SeekSettleSlackMs = 1500L

/**
 * Seek bar for [AudioPage].
 *
 * Drawn and gestured by hand for two reasons the stock Material slider cannot
 * satisfy here:
 *
 *  * **the drag must not reach the pager.** All pointer changes are consumed
 *    in this node, so the surrounding `HorizontalPager` never sees a drag it
 *    could claim — the previous player surface let a scrub turn into "swipe to
 *    the next file".
 *  * **touching anywhere on the track seeks there.** A tap/drag jumps
 *    immediately instead of requiring the thumb to be hit first, which is what
 *    "drag it to any position" means on a phone.
 *
 * The touch target is [SeekTouchHeight] tall while the bar itself is only a
 * few dp, so it stays easy to grab without dominating the layout.
 */
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    scrubbing: Boolean,
    onScrubStart: () -> Unit,
    onScrubTo: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = durationMs > 0L
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SeekTouchHeight)
            .pointerInput(enabled, durationMs) {
                if (!enabled) return@pointerInput
                fun msAt(x: Float): Long =
                    ((x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) * durationMs).toLong()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onScrubStart()
                    try {
                        onScrubTo(msAt(down.position.x))
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                if (change.pressed) onScrubTo(msAt(change.position.x))
                                change.consume()
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                    } finally {
                        // Also runs when the gesture is cancelled mid-drag, so
                        // the pager lock can never be left on.
                        onScrubEnd()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(20.dp)) {
            val centerY = size.height / 2f
            val trackHeight = 4.dp.toPx()
            val radius = trackHeight / 2f
            val fraction = if (durationMs > 0L) {
                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else 0f
            val filled = size.width * fraction
            val thumbRadius = (if (scrubbing) 8.dp else 6.dp).toPx()

            drawRoundRect(
                color = Color.White.copy(alpha = if (enabled) 0.26f else 0.12f),
                topLeft = Offset(0f, centerY - radius),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(radius, radius)
            )
            if (filled > 0f) {
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(0f, centerY - radius),
                    size = Size(filled, trackHeight),
                    cornerRadius = CornerRadius(radius, radius)
                )
            }
            drawCircle(
                color = Color.White,
                radius = thumbRadius,
                center = Offset(
                    x = filled.coerceIn(thumbRadius, size.width - thumbRadius),
                    y = centerY
                )
            )
        }
    }
}

/**
 * The spinning disc: a vinyl body drawn with concentric grooves and a sweeping
 * sheen (that sheen is what makes the rotation visible — a plain circle looks
 * identical at every angle), a circular label carrying the cover, and the usual
 * spindle hole at the centre.
 */
@Composable
private fun Disc(
    discSize: Dp,
    angle: Float,
    art: ImageBitmap?,
    coverUrl: String?
) {
    Box(
        modifier = Modifier
            .size(discSize)
            .graphicsLayer { rotationZ = angle % 360f },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF33333A), Color(0xFF0D0D0F)),
                    center = center,
                    radius = radius
                ),
                radius = radius
            )
            // Grooves.
            for (i in 0 until 14) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.035f),
                    radius = radius * (0.40f + 0.042f * i),
                    style = Stroke(width = 1f)
                )
            }
            // Two opposed highlights sweeping round with the disc.
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.001f),
                        Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.001f),
                        Color.White.copy(alpha = 0.001f),
                        Color.White.copy(alpha = 0.14f),
                        Color.White.copy(alpha = 0.001f)
                    ),
                    center = center
                ),
                radius = radius
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.12f),
                radius = radius - 1.5f,
                style = Stroke(width = 1.5f)
            )
        }

        // Label: embedded art, then a cover image shipped next to the file,
        // then the built-in default.
        Box(
            modifier = Modifier.size(discSize * 0.64f).clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            when {
                art != null -> Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                coverUrl != null -> AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                else -> DefaultCover()
            }
        }
        Box(
            modifier = Modifier
                .size(discSize * 0.64f)
                .border(2.dp, Color.Black.copy(alpha = 0.35f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(discSize * 0.10f)
                .background(Color(0xFF101013), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
        )
    }
}

/**
 * The tonearm laid over the disc.
 *
 * Deliberately *not* part of [Disc]: the record spins, the arm does not, so it
 * has to be its own layer on top of a stationary pivot. Everything is drawn
 * around [TonearmPivotX]/[TonearmPivotY], and the whole layer is rotated with
 * a `transformOrigin` on that same point, which is what lets the arm swing
 * instead of slide.
 *
 * Playing puts the stylus down on the grooves; paused lifts it clear of the
 * record. The swing is animated either way so the state change reads as the
 * needle dropping or being lifted rather than as a control flipping.
 */
@Composable
private fun Tonearm(engaged: Boolean) {
    val swing by animateFloatAsState(
        targetValue = if (engaged) 0f else TonearmParkedSwing,
        // Settling onto the record is the slower half: the arm is light, the
        // cueing lever is what takes its time.
        animationSpec = tween(
            durationMillis = if (engaged) 620 else 480,
            easing = FastOutSlowInEasing
        ),
        label = "tonearm"
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                transformOrigin = TransformOrigin(TonearmPivotX, TonearmPivotY)
                rotationZ = swing
            }
    ) {
        val u = size.minDimension
        val pivot = Offset(size.width * TonearmPivotX, size.height * TonearmPivotY)
        val reach = u * TonearmReach

        // Everything but the bearing is drawn with the arm lying flat, pointing
        // left, and then rotated as a single piece. That is what keeps the
        // cylinder shading *across* the tube and the weight perpendicular to
        // the arm at any angle: shading a diagonal bar with a screen-space
        // gradient would run the highlight along its length instead of around
        // it, which is exactly what made the first version look like a stick.
        rotate(degrees = TonearmEngagedDegrees - 180f, pivot = pivot) {
            val tubeHalf = u * 0.0080f
            val tip = pivot.x - reach

            // The arm tube first, so the weight and the shell sit on top of it:
            // brushed metal, lit from above.
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF55555F), Color(0xFF9E9FAC),
                        Color(0xFFE7E8F0), Color(0xFF8A8A98),
                        Color(0xFF484852)
                    ),
                    startY = pivot.y - u * 0.011f,
                    endY = pivot.y + u * 0.011f
                ),
                topLeft = Offset(tip + u * 0.03f, pivot.y - tubeHalf),
                size = Size(reach - u * 0.03f + u * 0.050f, tubeHalf * 2f),
                cornerRadius = CornerRadius(tubeHalf)
            )

            // Counterweight, threaded on to the back end. Kept clear of the
            // housing — overlapping the two read as one lumpy ball.
            val weightLeft = pivot.x + u * 0.046f
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF61616F), Color(0xFF34343F),
                        Color(0xFF212129), Color(0xFF121216)
                    ),
                    startY = pivot.y - u * 0.026f,
                    endY = pivot.y + u * 0.026f
                ),
                topLeft = Offset(weightLeft, pivot.y - u * 0.026f),
                size = Size(u * 0.070f, u * 0.052f),
                cornerRadius = CornerRadius(u * 0.022f)
            )
            // Knurled ring, so it reads as machined rather than moulded.
            drawLine(
                color = Color.White.copy(alpha = 0.18f),
                start = Offset(weightLeft + u * 0.020f, pivot.y - u * 0.021f),
                end = Offset(weightLeft + u * 0.020f, pivot.y + u * 0.021f),
                strokeWidth = u * 0.0035f
            )

            // Headshell: a machined block with a chamfer, not a blob.
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF53535F), Color(0xFF31313B), Color(0xFF16161B)
                    ),
                    startY = pivot.y - u * 0.031f,
                    endY = pivot.y + u * 0.031f
                ),
                topLeft = Offset(tip - u * 0.005f, pivot.y - u * 0.031f),
                size = Size(u * 0.092f, u * 0.062f),
                cornerRadius = CornerRadius(u * 0.011f)
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.15f),
                topLeft = Offset(tip + u * 0.002f, pivot.y - u * 0.026f),
                size = Size(u * 0.078f, u * 0.010f),
                cornerRadius = CornerRadius(u * 0.005f)
            )

            // Cantilever and stylus, on top of the shell, reaching past its
            // nose — this is the bit that actually touches the record.
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFE2E3EB), Color(0xFF7C7C88)),
                    startY = pivot.y - u * 0.0045f,
                    endY = pivot.y + u * 0.0045f
                ),
                topLeft = Offset(tip - u * 0.012f, pivot.y - u * 0.0045f),
                size = Size(u * 0.030f, u * 0.009f),
                cornerRadius = CornerRadius(u * 0.0045f)
            )
            drawCircle(
                color = Color(0xFFF4F5F9),
                radius = u * 0.006f,
                center = Offset(tip - u * 0.012f, pivot.y)
            )
        }

        // Bearing housing last, so the arm and the weight tuck under it. Drawn
        // in screen space on purpose: its highlight has to sit under a fixed
        // light instead of spinning around with the arm. Kept deliberately
        // small — a big glossy dome here just looked like a bubble stuck to the
        // arm.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF6C6C7A), Color(0xFF3A3A45), Color(0xFF1A1A20)),
                center = pivot - Offset(u * 0.012f, u * 0.012f),
                radius = u * 0.062f
            ),
            radius = u * 0.043f,
            center = pivot
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.22f),
            radius = u * 0.043f,
            center = pivot,
            style = Stroke(width = u * 0.003f)
        )
        drawCircle(color = Color(0xFF54545F), radius = u * 0.015f, center = pivot)
        drawCircle(color = Color(0xFFC6C7D2), radius = u * 0.006f, center = pivot)
    }
}

/**
 * The label of a record that carries no artwork.
 *
 * Deliberately carries no glyph. An earlier version stamped a music note here
 * and had to shove it off-centre to dodge the spindle hole — which read as a
 * mis-aligned note, not as a deliberate offset. A blank vinyl label (a shade
 * lighter than the disc, with its own finer, tighter grooves) instead looks
 * like a record that simply has no cover art, which is what it is.
 */
@Composable
private fun DefaultCover() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = size.minDimension / 2f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF35353F), Color(0xFF1D1D24)),
                center = center,
                radius = radius
            ),
            radius = radius
        )
        // Finer and tighter than the disc's own grooves: the label has to read
        // as a different surface from the record around it.
        var r = radius * 0.30f
        while (r < radius) {
            drawCircle(
                color = Color.White.copy(alpha = 0.030f),
                radius = r,
                style = Stroke(width = 1f)
            )
            r += radius * 0.028f
        }
        // Hairline rim where the label meets the disc body.
        drawCircle(
            color = Color.White.copy(alpha = 0.10f),
            radius = radius - 0.5f,
            style = Stroke(width = 1f)
        )
    }
}

/** One `label  value` row of the viewer's info panel. */
@Composable
fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            "$label  ",
            color = Color.LightGray,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(end = 4.dp)
        )
        Text(value, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}
