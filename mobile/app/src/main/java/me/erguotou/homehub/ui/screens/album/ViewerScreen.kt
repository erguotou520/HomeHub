package me.erguotou.homehub.ui.screens.album

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import me.erguotou.homehub.data.PhotoItem
import me.erguotou.homehub.util.formatBytes
import me.erguotou.homehub.util.formatDateTime
import me.erguotou.homehub.util.formatDuration

/**
 * Full screen viewer for photos AND videos, following the system gallery:
 * swipe between media, images pinch/double-tap zoom, videos play in place
 * with the Media3 controller. Rotation (server rewrite) is photo-only.
 */
@Composable
fun PhotoViewerScreen(
    photos: List<PhotoItem>,
    initialIndex: Int,
    urlResolver: (String) -> String,
    mediaUrlResolver: (Long) -> String,
    onRotate: (PhotoItem, Int, (Boolean) -> Unit) -> Unit,
    onFlip: (PhotoItem, Boolean, (Boolean) -> Unit) -> Unit,
    onResize: (PhotoItem, Int, (Boolean) -> Unit) -> Unit,
    onRestore: (PhotoItem, (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { photos.size })
    var pageZoomed by remember { mutableStateOf(false) }
    // Bumped after an edit so Coil drops its cached bitmap for that photo.
    var revision by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

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
            if (success) {
                revision++
                toast = ok
            } else {
                toast = fail
            }
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
                    url = urlResolver(photo.url) + "&rev=$revision",
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
            onResize = { width, done ->
                runEdit(
                    { cb -> onResize(currentPhoto, width) { ok -> cb(ok); done(ok) } },
                    "已缩放到宽 $width px",
                    "缩放失败"
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

/** Zoomable still image — same gestures as before. */
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

    val state = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offsetX += panChange.x
        offsetY += panChange.y
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .transformable(state = state)
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
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, 6f)
                    if (next != 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                    scale = next
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
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                }
            },
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

@Composable
private fun ViewerOverlay(
    photo: PhotoItem,
    page: Int,
    total: Int,
    busy: Boolean,
    toast: String?,
    onClose: () -> Unit,
    onRotate: (Int, (Boolean) -> Unit) -> Unit,
    onFlip: (Boolean, (Boolean) -> Unit) -> Unit,
    onResize: (Int, (Boolean) -> Unit) -> Unit,
    onRestore: ((Boolean) -> Unit) -> Unit
) {
    var showInfo by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showResize by remember { mutableStateOf(false) }
    var widthText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Text(
                "${page + 1} / $total",
                color = Color.White,
                modifier = Modifier.padding(16.dp)
            )
        }

        Box(modifier = Modifier.weight(1f))

        // Transient feedback, mirroring the web lightbox toast.
        toast?.let {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
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

        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            if (showInfo) {
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
                            InfoLine("位置", String.format("%.5f, %.5f", lat, photo.gpsLng ?: 0.0))
                        }
                        if (photo.tags.isNotEmpty()) {
                            InfoLine("标签", photo.tags.joinToString("、") { it.tag })
                        }
                    }
                }
            }

            if (showRestoreConfirm) {
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
                            Button(onClick = { showRestoreConfirm = false }) { Text("取消") }
                            Box(modifier = Modifier.padding(start = 8.dp)) {
                                Button(
                                    onClick = {
                                        showRestoreConfirm = false
                                        onRestore { }
                                    },
                                    enabled = !busy
                                ) { Text("确认还原") }
                            }
                        }
                    }
                }
            }

            if (showResize) {
                Surface(color = Color.Black.copy(alpha = 0.75f)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "目标宽度（px，等比缩放）",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = widthText,
                                onValueChange = { v -> widthText = v.filter { it.isDigit() } },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color.White,
                                    unfocusedBorderColor = Color.LightGray
                                )
                            )
                            Box(modifier = Modifier.padding(start = 8.dp)) {
                                Button(
                                    onClick = {
                                        val w = widthText.toIntOrNull()
                                        if (w != null && w > 0) {
                                            showResize = false
                                            onResize(w) { }
                                        }
                                    },
                                    enabled = !busy
                                ) { Text("缩放") }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { showInfo = !showInfo }) { Text("信息") }
                // Everything below rewrites pixels on the server — photos only.
                if (!photo.isVideo) {
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
                        onClick = { widthText = photo.width?.toString() ?: ""; showResize = true },
                        enabled = !busy
                    ) {
                        Icon(Icons.Filled.PhotoSizeSelectLarge, contentDescription = "按宽度缩放", tint = Color.White)
                    }
                    IconButton(
                        onClick = { showRestoreConfirm = true },
                        enabled = !busy
                    ) {
                        Icon(Icons.Filled.Restore, contentDescription = "还原原图", tint = Color.White)
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
