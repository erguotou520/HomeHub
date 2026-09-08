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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import me.erguotou.homehub.data.PhotoItem
import me.erguotou.homehub.util.formatBytes
import me.erguotou.homehub.util.formatDateTime

/**
 * Full screen viewer: swipe between photos, pinch/double-tap zoom and in-place
 * rotation (which asks the server to rewrite the file).
 */
@Composable
fun PhotoViewerScreen(
    photos: List<PhotoItem>,
    initialIndex: Int,
    urlResolver: (String) -> String,
    onRotate: (PhotoItem, Int, (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { photos.size })
    val scope = rememberCoroutineScope()
    android.util.Log.d("ViewerDbg", "PhotoViewerScreen compose photos=${photos.size}")

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val photo = photos[page]
            var scale by remember(photo.id) { mutableFloatStateOf(1f) }
            var offsetX by remember(photo.id) { mutableFloatStateOf(0f) }
            var offsetY by remember(photo.id) { mutableFloatStateOf(0f) }
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
                model = urlResolver(photo.url),
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

        // Overlay lives OUTSIDE the pager: one instance over the current
        // page, so its buttons can never be clipped by a page transition.
        val currentPage = pagerState.currentPage
        val currentPhoto = photos[currentPage.coerceIn(0, photos.size - 1)]
        ViewerOverlay(
            photo = currentPhoto,
            page = currentPage,
            total = photos.size,
            onClose = onDismiss,
            onRotate = { angle -> onRotate(currentPhoto, angle) {} }
        )
    }
}

@Composable
private fun ViewerOverlay(
    photo: PhotoItem,
    page: Int,
    total: Int,
    onClose: () -> Unit,
    onRotate: (Int) -> Unit
) {
    var showInfo by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

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

        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            if (showInfo) {
                Surface(color = Color.Black.copy(alpha = 0.55f)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        InfoLine("文件", photo.name)
                        InfoLine("路径", "${photo.dirName}/${photo.relPath}")
                        InfoLine("时间", formatDateTime(photo.takenAt))
                        InfoLine(
                            "尺寸",
                            "${photo.width ?: "-"} × ${photo.height ?: "-"} · ${formatBytes(photo.size)}"
                        )
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { showInfo = !showInfo }) { Text("信息") }
                IconButton(onClick = { busy = true; onRotate(270); busy = false }, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = "左转", tint = Color.White)
                }
                IconButton(onClick = { busy = true; onRotate(90); busy = false }, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "右转", tint = Color.White)
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
