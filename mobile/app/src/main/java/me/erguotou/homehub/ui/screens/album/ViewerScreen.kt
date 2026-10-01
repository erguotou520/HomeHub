package me.erguotou.homehub.ui.screens.album

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import me.erguotou.homehub.data.PhotoItem
import me.erguotou.homehub.ui.components.InfoLine
import me.erguotou.homehub.ui.components.ImagePage
import me.erguotou.homehub.ui.components.VideoControllerInset
import me.erguotou.homehub.ui.components.VideoPage
import me.erguotou.homehub.ui.components.findHostActivity
import me.erguotou.homehub.util.formatBytes
import me.erguotou.homehub.util.formatDateTime
import me.erguotou.homehub.util.formatDuration
import java.util.Locale

/**
 * Full screen viewer for photos AND videos, following the system gallery:
 * swipe between media, images pinch/double-tap zoom, videos play in place
 * with the Media3 controller. Rotation / flip (server rewrite) is photo-only.
 *
 * The media surfaces themselves ([ImagePage], [VideoPage], [AudioPage]) are
 * shared with the 文件 tab's viewer — see ui/components/MediaViewer.kt. This
 * file only owns the album-specific chrome: the counter, the info panel and
 * the edit toolbar.
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
    onDownload: (PhotoItem) -> Unit,
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
        val controller = view.context.findHostActivity()?.window
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
                    key = photo.id,
                    url = mediaUrlResolver(photo.id),
                    isCurrentPage = pagerState.currentPage == page
                )
            } else {
                ImagePage(
                    key = photo.id,
                    url = urlResolver(photo.url),
                    contentDescription = photo.name,
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
            onDownload = onDownload,
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
    onDownload: (PhotoItem) -> Unit,
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
                // Explicit button rather than a long-press gesture: the video
                // page deliberately carries no Compose gesture layer (it would
                // swallow the Media3 controller's taps), and a hidden gesture
                // on the photo page would fight pinch-zoom.
                IconButton(onClick = { onDownload(photo) }, enabled = !busy) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "下载到本机",
                        tint = Color.White
                    )
                }
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
                // On a video page the Media3 controller owns the bottom ~56dp
                // (seek bar + time row + settings); without this lift the info
                // panel's last line is drawn straight through the seek bar.
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier.padding(bottom = if (photo.isVideo) VideoControllerInset else 0.dp)
                ) {
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
