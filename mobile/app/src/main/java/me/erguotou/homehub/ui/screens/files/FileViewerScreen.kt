package me.erguotou.homehub.ui.screens.files

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.erguotou.homehub.ui.components.AudioPage
import me.erguotou.homehub.ui.components.AudioPlaybackMemory
import me.erguotou.homehub.ui.components.InfoLine
import me.erguotou.homehub.ui.components.ImagePage
import me.erguotou.homehub.ui.components.VideoControllerInset
import me.erguotou.homehub.ui.components.VideoPage
import me.erguotou.homehub.ui.components.findHostActivity
import me.erguotou.homehub.util.FileKind
import me.erguotou.homehub.util.FileKinds
import me.erguotou.homehub.util.OfficeText
import me.erguotou.homehub.util.TempFiles
import me.erguotou.homehub.util.formatBytes
import me.erguotou.homehub.util.formatDateTime
import java.io.File

/**
 * Full screen viewer for the 文件 tab.
 *
 * Opening a file never downloads it if the content can be streamed or parsed
 * on the fly:
 *
 *  * images / videos / audio stream straight from `/api/files/…` (the server
 *    answers Range requests) and play in the *same* surfaces as the album
 *    viewer — pinch-zoom, Media3 controller, swipe between siblings;
 *  * text-ish files are fetched as text (`/api/documents/…`, 1 MiB cap) and
 *    shown in a full screen monospace, selectable reader;
 *  * PDFs are the one case that needs random access, so the bytes land in the
 *    scratch cache and the platform `PdfRenderer` draws them — the file is
 *    deleted as soon as the viewer closes;
 *  * OOXML documents get an on-device text/table extraction (see [OfficeText]),
 *    with a one-tap "用其他应用打开" for faithful layout.
 *
 * Swiping moves through every openable sibling in the directory, mirroring the
 * album's photo+video pager. Image pages keep the album's server-side
 * rotate/flip/restore toolbar — those endpoints are file-address based, so they
 * work for any image in a registered directory.
 *
 * Audio pages are the one exception to "swipe always pages": the music surface
 * is a full player (disc + cover + seek bar), and while the seek bar is being
 * dragged the pager is frozen — otherwise scrubbing a track immediately flung
 * the viewer onto the neighbouring file. See [AudioPage].
 *
 * Leaving an audio page pauses the track at the exact spot and returning
 * carries on from there, which is what [audioMemory] is for — the page itself,
 * and its player, do not survive the swipe.
 */
/**
 * Height of the translucent bar floating over the viewer (back arrow, file
 * name, page counter, actions). Text-ish pages inset their content by this
 * much below the status bar so the first line never hides under it.
 */
private val ViewerTopBarHeight = 56.dp

/**
 * Cover-image file stems that sit next to a track rather than inside it.
 * Mirrors what the server's own music parser looks for.
 */
private val CoverStems = setOf("cover", "folder", "album", "albumart", "artwork", "front")

/**
 * URL of a cover image shipped alongside [item], if any.
 *
 * Embedded artwork (read by the player from the file's tags) wins over this —
 * [AudioPage] only asks for a sibling cover when the file carries none. Matches
 * `track.jpg` first, then the usual `cover/folder/album` names, and only looks
 * at image siblings in the *same* folder.
 */
private fun siblingCover(
    item: ViewerItem,
    items: List<ViewerItem>,
    urlFor: (ViewerItem) -> String
): String? {
    if (item.kind != FileKind.AUDIO) return null
    val folder = item.relPath.substringBeforeLast('/', "")
    val siblings = items.filter {
        it.kind == FileKind.IMAGE &&
            it.dir == item.dir &&
            it.relPath.substringBeforeLast('/', "") == folder
    }
    if (siblings.isEmpty()) return null
    fun stemOf(name: String) = name.substringBeforeLast('.', name).lowercase()
    val stem = stemOf(item.name)
    val match = siblings.firstOrNull { stemOf(it.name) == stem }
        ?: siblings.firstOrNull { stemOf(it.name) in CoverStems }
    return match?.let(urlFor)
}

@Composable
fun FileViewerScreen(
    items: List<ViewerItem>,
    initialIndex: Int,
    urlFor: (ViewerItem) -> String,
    loadText: suspend (ViewerItem) -> String?,
    loadBytes: suspend (ViewerItem) -> ByteArray?,
    onRotate: (ViewerItem, Int, (Boolean) -> Unit) -> Unit,
    onFlip: (ViewerItem, Boolean, (Boolean) -> Unit) -> Unit,
    onRestore: (ViewerItem, (Boolean) -> Unit) -> Unit,
    onOpenExternal: (ViewerItem) -> Unit,
    onDownload: (ViewerItem) -> Unit,
    onShare: (ViewerItem) -> Unit,
    onDismiss: () -> Unit
) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, items.size - 1),
        pageCount = { items.size }
    )
    var pageZoomed by remember { mutableStateOf(false) }
    /** A seek bar is being dragged: paging must not steal the gesture. */
    var scrubbing by remember { mutableStateOf(false) }
    /**
     * Where each track was left. Lives as long as the viewer does because audio
     * pages are torn down with their player: without this, leaving a song
     * paused would lose the position and it would replay from 0:00.
     */
    val audioMemory = remember { AudioPlaybackMemory() }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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

    BackHandler { onDismiss() }

    val view = LocalView.current
    val darkTheme = isSystemInDarkTheme()
    DisposableEffect(view) {
        val controller = view.context.findHostActivity()?.window
            ?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = !darkTheme
            controller?.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // Freeze paging while a seek bar is held: the drag belongs to the
            // slider, and letting the pager take it flipped to another file.
            userScrollEnabled = !pageZoomed && !scrubbing
        ) { page ->
            val item = items[page]
            when (item.kind) {
                FileKind.IMAGE -> ImagePage(
                    key = item.entry.path,
                    url = urlFor(item),
                    contentDescription = item.name,
                    onZoomChanged = { pageZoomed = it }
                )
                FileKind.VIDEO -> VideoPage(
                    key = item.entry.path,
                    url = urlFor(item),
                    isCurrentPage = pagerState.currentPage == page
                )
                FileKind.AUDIO -> AudioPage(
                    key = item.entry.path,
                    url = urlFor(item),
                    title = item.name,
                    subtitle = item.entry.mimeType,
                    isCurrentPage = pagerState.currentPage == page,
                    memory = audioMemory,
                    coverUrl = siblingCover(item, items, urlFor),
                    onScrubbingChanged = { scrubbing = it },
                    onPrev = if (page > 0) {
                        { scope.launch { pagerState.animateScrollToPage(page - 1) } }
                    } else null,
                    onNext = if (page < items.size - 1) {
                        { scope.launch { pagerState.animateScrollToPage(page + 1) } }
                    } else null
                )
                FileKind.TEXT -> TextPage(item, loadText)
                FileKind.PDF -> PdfPage(item, loadBytes) { onOpenExternal(item) }
                FileKind.OFFICE -> OfficePage(item, loadBytes) { onOpenExternal(item) }
                FileKind.OTHER -> UnsupportedPage(item) { onOpenExternal(item) }
            }
        }

        val currentPage = pagerState.currentPage.coerceIn(0, items.size - 1)
        val current = items[currentPage]

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Text(
                    current.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                )
                Text(
                    "${currentPage + 1} / ${items.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
                IconButton(onClick = { showInfo = !showInfo }) {
                    Icon(Icons.Outlined.Info, contentDescription = "信息", tint = Color.White)
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = Color.White)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("用其他应用打开") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                            onClick = { menu = false; onOpenExternal(current) }
                        )
                        DropdownMenuItem(
                            text = { Text("下载到本机") },
                            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                            onClick = { menu = false; onDownload(current) }
                        )
                        DropdownMenuItem(
                            text = { Text("分享") },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            onClick = { menu = false; onShare(current) }
                        )
                    }
                }
            }

            if (showInfo) {
                Surface(color = Color.Black.copy(alpha = 0.6f)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        InfoLine("名称", current.name)
                        InfoLine("位置", "${current.dir}/${current.relPath}")
                        InfoLine("类型", kindLabel(current.kind) + (current.entry.mimeType?.let { " · $it" } ?: ""))
                        current.entry.size?.let { InfoLine("大小", formatBytes(it)) }
                        current.entry.modified?.let { InfoLine("修改", formatDateTime(it)) }
                        if (current.kind == FileKind.OFFICE) {
                            InfoLine("提示", "内联为纯文本抽取，版式请用其他应用打开")
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            toast?.let {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(color = Color.Black.copy(alpha = 0.7f), shape = MaterialTheme.shapes.small) {
                        Text(
                            it,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // Image pages keep the album's edit toolbar — the transform
            // endpoints address any file in a registered directory.
            if (current.kind == FileKind.IMAGE) {
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
                        IconButton(onClick = { onRotate(current, 270) { } }, enabled = !busy) {
                            Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = "左转", tint = Color.White)
                        }
                        IconButton(onClick = { onRotate(current, 90) { } }, enabled = !busy) {
                            Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "右转", tint = Color.White)
                        }
                        IconButton(onClick = { onFlip(current, false) { } }, enabled = !busy) {
                            Icon(Icons.Filled.Flip, contentDescription = "水平翻转", tint = Color.White)
                        }
                        IconButton(onClick = { onFlip(current, true) { } }, enabled = !busy) {
                            Icon(Icons.Filled.SwapVert, contentDescription = "垂直翻转", tint = Color.White)
                        }
                        IconButton(onClick = { onRestore(current) { } }, enabled = !busy) {
                            Icon(Icons.Filled.Restore, contentDescription = "还原原图", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────────────── text ───────────────────────────────────

private sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/**
 * Full screen text reader. The server caps `/api/documents` at 1 MiB, which is
 * plenty for the ini/log/md/csv files this surface targets; anything larger is
 * announced by [FilesViewModel] instead of silently truncated here.
 */
@Composable
private fun TextPage(item: ViewerItem, load: suspend (ViewerItem) -> String?) {
    var state by remember(item.entry.path) { mutableStateOf<Load<String>>(Load.Loading) }

    LaunchedEffect(item.entry.path) {
        state = Load.Loading
        val text = load(item)
        state = when {
            text == null -> Load.Failed("读取失败，请检查网络或改用其他应用打开")
            FileKinds.looksBinary(text) -> Load.Failed("这看起来是二进制文件，无法作为文本显示")
            else -> Load.Ready(text)
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is Load.Loading -> CircularProgressIndicator(color = Color.White)
            is Load.Failed -> MessageBlock(s.message)
            is Load.Ready -> SelectionContainer {
                Text(
                    s.value,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(
                            start = 14.dp,
                            end = 14.dp,
                            top = ViewerTopBarHeight,
                            bottom = 100.dp
                        )
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

// ────────────────────────────────── pdf ───────────────────────────────────

/** PDF: fetch once, render on device, delete the scratch copy on the way out. */
@Composable
private fun PdfPage(
    item: ViewerItem,
    loadBytes: suspend (ViewerItem) -> ByteArray?,
    onOpenExternal: () -> Unit
) {
    val context = LocalContext.current
    var file by remember(item.entry.path) { mutableStateOf<File?>(null) }
    var failed by remember(item.entry.path) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.entry.path) {
        file = null
        failed = null
        val bytes = loadBytes(item)
        if (bytes == null) {
            failed = "PDF 下载失败"
        } else {
            file = runCatching { TempFiles.writeViewer(context, item.name, bytes) }.getOrNull()
            if (file == null) failed = "无法写入临时文件"
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            failed != null -> MessageBlock(failed!!, onOpenExternal)
            file == null -> CircularProgressIndicator(color = Color.White)
            else -> PdfPages(file!!) { failed = it }
        }
    }
}

/**
 * Vertical, continuously scrolling page list — PDFs read top-to-bottom, and a
 * same-axis nested pager would fight the outer one for horizontal swipes.
 * Renderer access is serialised because `PdfRenderer` is single-threaded.
 */
@Composable
private fun PdfPages(file: File, onError: (String) -> Unit) {
    val descriptor = remember(file) {
        runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
    }
    val renderer = remember(file) {
        descriptor?.let { runCatching { PdfRenderer(it) }.getOrNull() }
    }

    if (descriptor == null || renderer == null) {
        LaunchedEffect(file) { onError("无法打开 PDF") }
        return
    }

    DisposableEffect(file) {
        onDispose {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // ~2x the physical width keeps the text crisp without blowing up memory.
    val targetWidthPx = with(density) { (screenWidthDp.dp.toPx() * 2f).toInt() }.coerceAtLeast(720)
    val mutex = remember(file) { Mutex() }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = topInset + ViewerTopBarHeight,
            bottom = 96.dp,
            start = 6.dp,
            end = 6.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(count = renderer.pageCount) { index ->
            PdfPageBitmap(
                renderer = renderer,
                index = index,
                targetWidthPx = targetWidthPx,
                mutex = mutex
            )
        }
    }
}

@Composable
private fun PdfPageBitmap(
    renderer: PdfRenderer,
    index: Int,
    targetWidthPx: Int,
    mutex: Mutex
) {
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(index) {
        bitmap = runCatching {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    if (index >= renderer.pageCount) return@withLock null
                    val page = renderer.openPage(index)
                    try {
                        val scale = targetWidthPx.toFloat() / page.width.coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceIn(1, 6000)
                        // A PDF page has no background of its own — white paper.
                        val bmp = createBitmap(targetWidthPx, height)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    } finally {
                        page.close()
                    }
                }
            }
        }.getOrNull()
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp == null) {
            Surface(color = Color(0xFF1B1B1B), modifier = Modifier.fillMaxWidth().aspectRatio(0.7f)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("第 ${index + 1} 页", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "第 ${index + 1} 页",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ───────────────────────────────── office ────────────────────────────────

/**
 * Office documents: text extraction when we can (OOXML), otherwise a clear
 * "open it elsewhere" card. Faithful layout is explicitly out of scope — no
 * on-device renderer does it, and shipping Apache POI/TBS would cost more APK
 * than the whole feature is worth.
 */
@Composable
private fun OfficePage(
    item: ViewerItem,
    loadBytes: suspend (ViewerItem) -> ByteArray?,
    onOpenExternal: () -> Unit
) {
    var state by remember(item.entry.path) { mutableStateOf<Load<String>>(Load.Loading) }

    LaunchedEffect(item.entry.path) {
        state = Load.Loading
        if (!FileKinds.canExtractOfficeText(item.name)) {
            state = Load.Failed("该格式无法内联预览（仅支持 docx / xlsx / pptx 的文本抽取）")
            return@LaunchedEffect
        }
        val bytes = loadBytes(item)
        if (bytes == null) {
            state = Load.Failed("下载失败，请检查网络")
            return@LaunchedEffect
        }
        val text = withContext(Dispatchers.Default) { OfficeText.extract(item.name, bytes) }
        state = if (text == null) Load.Failed("没有可提取的文本内容（可能是扫描件或纯图片文档）")
        else Load.Ready(text)
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is Load.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Text(
                    "正在解析文档…",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            is Load.Failed -> MessageBlock(s.message, onOpenExternal)
            is Load.Ready -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = ViewerTopBarHeight)
            ) {
                Surface(color = Color.Black.copy(alpha = 0.55f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, tint = Color.LightGray)
                        Text(
                            "纯文本抽取预览 · 版式请用其他应用打开",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )
                        OutlinedButton(onClick = onOpenExternal) { Text("打开方式") }
                    }
                }
                SelectionContainer {
                    Text(
                        s.value,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 96.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

@Composable
private fun UnsupportedPage(item: ViewerItem, onOpenExternal: () -> Unit) {
    MessageBlock(
        "这个格式需要别的应用来打开：\n${item.name}",
        onOpenExternal
    )
}

@Composable
private fun MessageBlock(message: String, onOpenExternal: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            message,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
        onOpenExternal?.let {
            Button(
                onClick = it,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Text("用其他应用打开", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private fun kindLabel(kind: FileKind): String = when (kind) {
    FileKind.IMAGE -> "图片"
    FileKind.VIDEO -> "视频"
    FileKind.AUDIO -> "音频"
    FileKind.TEXT -> "文本"
    FileKind.PDF -> "PDF"
    FileKind.OFFICE -> "Office 文档"
    FileKind.OTHER -> "文件"
}
