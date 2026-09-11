package me.erguotou.homehub.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import me.erguotou.homehub.R

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
 * Audio page: the same Media3 engine without a video surface. The controller
 * strip (play/pause + seek) sits under a title block so a bare MP3 does not
 * look like a broken video page.
 */
@Composable
fun AudioPage(
    key: Any,
    url: String,
    title: String,
    subtitle: String?,
    isCurrentPage: Boolean
) {
    val context = LocalContext.current
    var playbackError by remember(key) { mutableStateOf<String?>(null) }

    val player = remember(key) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            playWhenReady = true
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

    // Swiping to the next file pauses this one — otherwise a song would keep
    // playing under the next photo.
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage && player.isPlaying) player.pause()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.height(72.dp)
            )
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 16.dp)
            )
            subtitle?.let {
                Text(
                    it,
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            AndroidView(
                factory = { ctx ->
                    LayoutInflater.from(ctx).inflate(R.layout.hh_player_view, null) as PlayerView
                },
                update = { view -> view.player = player },
                modifier = Modifier.fillMaxWidth().height(96.dp).padding(top = 12.dp)
            )
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
