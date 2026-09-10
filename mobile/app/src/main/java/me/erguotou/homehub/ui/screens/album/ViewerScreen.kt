package me.erguotou.homehub.ui.screens.album

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import me.erguotou.homehub.R
import me.erguotou.homehub.data.PhotoItem
import me.erguotou.homehub.util.formatBytes
import me.erguotou.homehub.util.formatDateTime
import me.erguotou.homehub.util.formatDuration
import java.util.Locale

/**
 * Full screen viewer for photos AND videos, following the system gallery:
 * swipe between media, images pinch/double-tap zoom, videos play in place
 * with the Media3 controller. Rotation / flip (server rewrite) is photo-only.
 *
 * It is an in-place overlay rather than a Dialog. Root's Scaffold no longer
 * contributes the status-bar inset (`contentWindowInsets = WindowInsets(0)`)
 * and the bottom bar is hidden while the viewer is open, so this Box really
 * does span the whole screen — including behind the status bar, which is
 * flipped to white icons so the black backdrop reads as one surface.
 *
 * The media pager fills the screen and the chrome floats ON TOP of it: the
 * toolbar is pinned to the bottom edge (`Alignment.BottomCenter`) so it never
 * moves when the info / restore panels grow above it, and the image behind it
 * is never squeezed into a smaller box.
 */
@Composable
fun PhotoViewerScreen(
    photos: List<PhotoItem>,
    initialIndex: Int,
    urlResolver: (String) -> String,
    mediaUrlResolver: (Long) -> String,
    onRotate: (PhotoItem, Int, (Boolean) -> Unit) -> Unit,
    onFlip: (PhotoItem, Boolean, (Boolean) -> Unit) -> Unit,
    onRestore: (PhotoItem, (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { photos.size })
    var pageZoomed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val panels = remember { ViewerPanels() }

    // Auto-clear the toast like the web lightbox does.
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(2200)
            toast = null
        }
    }

    fun runEdit(action: ((Boolean) -> Unit) -> Unit, ok: String, fail: String) {
        if (busy) return
        busy = true
        action { success ->
            busy = false
            toast = if (success) ok else fail
        }
    }

    // Back closes the open panel first, then the viewer — never the activity.
    BackHandler {
        if (panels.anyOpen) panels.closeAll() else onDismiss()
    }

    val view = LocalView.current
    val darkTheme = isSystemInDarkTheme()
    DisposableEffect(view) {
        val controller = view.context.findActivity()?.window
            ?.let { WindowCompat.getInsetsController(it, view) }
        // White icons over the black backdrop.
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            // Hand the bars back to the app's own theme.
            controller?.isAppearanceLightStatusBars = !darkTheme
            controller?.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // Lock swiping while an image is zoomed in, like the system gallery.
            userScrollEnabled = !pageZoomed
        ) { page ->
            val photo = photos[page]
            if (photo.isVideo) {
                VideoPage(
                    photo = photo,
                    url = mediaUrlResolver(photo.id),
                    isCurrentPage = pagerState.currentPage == page
                )
            } else {
                ImagePage(
                    photo = photo,
                    url = urlResolver(photo.url),
                    onZoomChanged = { pageZoomed = it }
                )
            }
        }

        // Overlay lives OUTSIDE the pager: one instance over the current
        // page, so its buttons can never be clipped by a page transition.
        val currentPage = pagerState.currentPage
        val currentPhoto = photos[currentPage.coerceIn(0, photos.size - 1)]
        ViewerOverlay(
            photo = currentPhoto,
            page = currentPage,
            total = photos.size,
            busy = busy,
            toast = toast,
            panels = panels,
            onClose = onDismiss,
            onRotate = { angle, done ->
                runEdit(
                    { cb -> onRotate(currentPhoto, angle) { ok -> cb(ok); done(ok) } },
                    "已旋转",
                    "旋转失败"
                )
            },
            onFlip = { vertical, done ->
                runEdit(
                    { cb -> onFlip(currentPhoto, vertical) { ok -> cb(ok); done(ok) } },
                    if (vertical) "已垂直翻转" else "已水平翻转",
                    "翻转失败"
                )
            },
            onRestore = { done ->
                runEdit(
                    { cb -> onRestore(currentPhoto) { ok -> cb(ok); done(ok) } },
                    "已还原原图",
                    "还原失败"
                )
            }
        )
    }
}

/** Unwrap the hosting Activity from a Compose view's (possibly wrapped) context. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Panels the overlay can open; back closes these before leaving the viewer. */
private class ViewerPanels {
    var info by mutableStateOf(false)
    var restore by mutableStateOf(false)

    val anyOpen: Boolean get() = info || restore

    fun closeAll() {
        info = false
        restore = false
    }
}

/**
 * Zoomable still image.
 *
 * The gesture loop deliberately only consumes when it is actually zooming
 * (two fingers) or already zoomed in. A single-finger drag at 1x is left
 * unconsumed so the surrounding pager can page between photos — the previous
 * version attached both `transformable` and `detectTransformGestures` to the
 * full-size box, which swallowed every drag and fought each other, so paging
 * and pinch-zoom were both dead.
 */
@Composable
private fun ImagePage(
    photo: PhotoItem,
    url: String,
    onZoomChanged: (Boolean) -> Unit
) {
    var scale by remember(photo.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(photo.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(photo.id) { mutableFloatStateOf(0f) }

    DisposableEffect(photo.id) {
        onDispose { onZoomChanged(false) }
    }
    LaunchedEffect(scale) { onZoomChanged(scale > 1.05f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(photo.id) {
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
            .pointerInput(photo.id) {
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
            contentDescription = photo.name,
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
 * There is deliberately NO Compose gesture layer stacked over the player.
 * A full-size `pointerInput` Box beats an `AndroidView` in the hit test, so
 * the interop view underneath never sees a touch: the controller rendered
 * fine but every tap (play, seek bar, settings) was swallowed, which made
 * videos look unplayable. Videos keep the platform's own controls; pinch
 * zoom stays a photo-only affordance.
 */
@Composable
private fun VideoPage(
    photo: PhotoItem,
    url: String,
    isCurrentPage: Boolean
) {
    val context = LocalContext.current
    var playbackError by remember(photo.id) { mutableStateOf<String?>(null) }

    val player = remember(photo.id) {
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

    DisposableEffect(photo.id) {
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
                    "该视频格式暂不支持在线播放（$err）",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

/**
 * Floating chrome over the media: a close button + counter pinned to the top,
 * and a translucent toolbar pinned to the BOTTOM edge of the screen. Panels
 * and the toast stack upwards from just above the toolbar, so the toolbar
 * itself never shifts — it always sits fixed at the bottom, over the image.
 */
@Composable
private fun ViewerOverlay(
    photo: PhotoItem,
    page: Int,
    total: Int,
    busy: Boolean,
    toast: String?,
    panels: ViewerPanels,
    onClose: () -> Unit,
    onRotate: (Int, (Boolean) -> Unit) -> Unit,
    onFlip: (Boolean, (Boolean) -> Unit) -> Unit,
    onRestore: ((Boolean) -> Unit) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { panels.info = !panels.info }) {
                    Icon(Icons.Outlined.Info, contentDescription = "信息", tint = Color.White)
                }
                Text(
                    "${page + 1} / $total",
                    color = Color.White,
                    modifier = Modifier.padding(start = 4.dp, end = 12.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            if (panels.info) {
                Surface(color = Color.Black.copy(alpha = 0.55f)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        InfoLine("文件", photo.name)
                        InfoLine("路径", "${photo.dirName}/${photo.relPath}")
                        InfoLine("时间", formatDateTime(photo.takenAt))
                        if (photo.isVideo) {
                            val dims = if (photo.width != null && photo.height != null) {
                                "${photo.width} × ${photo.height} · "
                            } else ""
                            InfoLine(
                                "视频",
                                dims + formatDuration(photo.durationMs).ifEmpty { "时长未知" } +
                                    (photo.videoCodec?.let { " · $it" } ?: "")
                            )
                        } else {
                            InfoLine(
                                "尺寸",
                                "${photo.width ?: "-"} × ${photo.height ?: "-"} · ${formatBytes(photo.size)}"
                            )
                        }
                        photo.cameraMake?.let { InfoLine("相机", listOfNotNull(it, photo.cameraModel).joinToString(" ")) }
                        photo.gpsLat?.let { lat ->
                            InfoLine("位置", String.format(Locale.US, "%.5f, %.5f", lat, photo.gpsLng ?: 0.0))
                        }
                        if (photo.tags.isNotEmpty()) {
                            InfoLine("标签", photo.tags.joinToString("、") { it.tag })
                        }
                    }
                }
            }

            if (panels.restore) {
                Surface(color = Color.Black.copy(alpha = 0.75f)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "确认还原到最近一次编辑前的状态？当前编辑将丢弃。",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(onClick = { panels.restore = false }) { Text("取消") }
                            Box(modifier = Modifier.padding(start = 8.dp)) {
                                Button(
                                    onClick = {
                                        panels.restore = false
                                        onRestore { }
                                    },
                                    enabled = !busy
                                ) { Text("确认还原") }
                            }
                        }
                    }
                }
            }

            // Transient feedback, mirroring the web lightbox toast.
            toast?.let {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.7f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            it,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // Photos get the edit toolbar pinned to the bottom edge. Videos get
            // no bottom chrome at all: their only useful action (信息) now lives
            // in the top bar, and the Media3 controller owns the bottom edge —
            // a floating bar there sat right on top of its seek bar.
            if (!photo.isVideo) {
                Surface(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { onRotate(270) { } },
                            enabled = !busy
                        ) {
                            Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = "左转", tint = Color.White)
                        }
                        IconButton(
                            onClick = { onRotate(90) { } },
                            enabled = !busy
                        ) {
                            Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "右转", tint = Color.White)
                        }
                        IconButton(
                            onClick = { onFlip(false) { } },
                            enabled = !busy
                        ) {
                            Icon(Icons.Filled.Flip, contentDescription = "水平翻转", tint = Color.White)
                        }
                        IconButton(
                            onClick = { onFlip(true) { } },
                            enabled = !busy
                        ) {
                            Icon(Icons.Filled.SwapVert, contentDescription = "垂直翻转", tint = Color.White)
                        }
                        IconButton(
                            onClick = { panels.restore = true },
                            enabled = !busy
                        ) {
                            Icon(Icons.Filled.Restore, contentDescription = "还原原图", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
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
