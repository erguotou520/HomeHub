package me.erguotou.homehub.ui.screens.album

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import me.erguotou.homehub.data.DirEntry
import me.erguotou.homehub.data.PhotoItem
import me.erguotou.homehub.data.Prefs
import me.erguotou.homehub.data.Repository
import me.erguotou.homehub.ui.components.DirectoryPickerDialog
import me.erguotou.homehub.ui.components.Empty
import me.erguotou.homehub.ui.components.ErrorText
import me.erguotou.homehub.ui.components.Loading
import me.erguotou.homehub.ui.components.NoticeHost
import me.erguotou.homehub.ui.components.PhotoActionMenu
import me.erguotou.homehub.ui.components.PhotoTile
import me.erguotou.homehub.ui.components.SectionHeader
import me.erguotou.homehub.ui.components.rememberPhotoDownloader
import me.erguotou.homehub.ui.components.rememberPhotoSharer
import me.erguotou.homehub.work.UploadWorker

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumScreen(
    onFullscreenChange: (Boolean) -> Unit = {},
    onOpenSemantic: () -> Unit = {},
    vm: AlbumViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val repository = remember { Repository(context) }
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()

    var viewerPhoto by remember { mutableStateOf<PhotoItem?>(null) }
    var viewerList by remember { mutableStateOf(listOf<PhotoItem>()) }

    // Long-press target for the album's action sheet (分享 / 下载到本机).
    var menuPhoto by remember { mutableStateOf<PhotoItem?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val downloadPhoto = rememberPhotoDownloader(repository, snackbar)
    val sharePhoto = rememberPhotoSharer(repository, snackbar)

    // The viewer is handed a snapshot list when it opens. After an edit the
    // ViewModel refetches and that snapshot goes stale (the info panel would
    // keep reporting the pre-edit dimensions). Re-map the snapshot onto the
    // freshest objects by id: order and membership are preserved, only the
    // metadata is refreshed.
    val liveById = remember(state.groups, state.filtered, state.treePhotos) {
        buildMap<Long, PhotoItem> {
            state.groups.forEach { g -> g.items.forEach { put(it.id, it) } }
            state.filtered.forEach { put(it.id, it) }
            state.treePhotos.forEach { put(it.id, it) }
        }
    }

    // In the 目录 view back goes up one folder; only the top level leaves the app.
    BackHandler(enabled = state.view == AlbumView.TREE && state.treeStack.isNotEmpty()) {
        vm.treeUp()
    }

    var showUpload by remember { mutableStateOf(false) }
    var uploadDirs by remember { mutableStateOf<List<DirEntry>>(emptyList()) }
    var uploadDir by remember { mutableStateOf<String?>(null) }
    var uploadPath by remember { mutableStateOf("") }
    var pendingUploadUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showUploadConfirm by remember { mutableStateOf(false) }
    // 设置 → 上传 → 上传后删除本地副本 决定确认框里的默认勾选。
    // 这里只是初值；真正的默认值在每次打开确认框时重读（见 pickImages），
    // 否则切到设置改完开关再切回来，本页的 remember 还是旧值。
    var uploadDeleteLocal by remember { mutableStateOf(prefs.deleteAfterUpload) }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            pendingUploadUris = uris
            uploadDeleteLocal = prefs.deleteAfterUpload
            showUploadConfirm = true
        }
    }

    // The viewer is drawn as the last child of this Box so it covers the
    // album's own top bar; Root.kt hides the bottom navigation bar while the
    // viewer is open, which makes the experience truly full screen.
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("相册") },
                    actions = {
                        IconButton(onClick = onOpenSemantic) {
                            Icon(Icons.Default.Search, contentDescription = "语义搜索")
                        }
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    // Only album-marked directories are valid photo
                    // destinations; the picker browses inside them.
                    scope.launch {
                        repository.dirs().fold(
                            onSuccess = { list ->
                                uploadDirs = list.filter { it.marks.contains("album") }.ifEmpty { list }
                            },
                            onFailure = { uploadDirs = emptyList() }
                        )
                        showUpload = true
                    }
                }) {
                    Icon(Icons.Default.Upload, contentDescription = "上传")
                }
            }
        ) { padding ->
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                // 下拉指示器只在「已经有内容、正在刷新它」时出现。屏幕还空着的时候
                // 全屏 spinner 已经把「在加载」说清楚了，再叠一个下拉圈就是两个圈
                // 同时转 —— 判据与下面那道守卫用同一个 `hasContent`。
                isRefreshing = state.loading && state.hasContent,
                onRefresh = { vm.refresh() },
                state = pullState,
                modifier = Modifier.padding(padding).fillMaxSize()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // View switcher — horizontally scrollable so it never
                    // wraps on narrow phones.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AlbumView.values().forEach { view ->
                            FilterChip(
                                selected = state.view == view,
                                onClick = { vm.select(view) },
                                label = { Text(labelOf(view)) }
                            )
                        }
                    }

                    // Timeline-only media type filter, like the system gallery's
                    // 全部 / 照片 / 视频 segmented control.
                    if (state.view == AlbumView.TIMELINE && state.activeFilter == null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MediaKind.values().forEach { kind ->
                                FilterChip(
                                    selected = state.kind == kind,
                                    onClick = { vm.selectKind(kind) },
                                    label = { Text(kind.label) }
                                )
                            }
                        }
                    }

                    // 全屏 spinner 只在屏幕确实没东西可显示时出现。判据取自当前
                    // 视图真正显示的那份数据（`hasContent`）：切到「分类」时时间
                    // 轴的照片还留在 state 里，拿它当「有内容」就会放行，于是先闪
                    // 一个「还没有标签」再跳成数据。列表已有数据时旧内容留在原地，
                    // 刷新在它下面跑 —— 缓存优先不受影响。
                    if (state.loading && !state.hasContent) {
                        Loading()
                        return@PullToRefreshBox
                    }
                    state.error?.let {
                        ErrorText(it, onRetry = { vm.refresh() })
                        return@PullToRefreshBox
                    }

                    if (state.activeFilter != null) {
                        FilterHeader(state.activeFilter!!) { vm.clearFilter() }
                        PhotoGrid(
                            photos = state.filtered,
                            paging = state.filterPaging,
                            urlResolver = vm::url,
                            onOpen = { photo, list ->
                                viewerList = list
                                viewerPhoto = photo
                            },
                            onLongClick = { menuPhoto = it },
                            onLoadMore = { vm.loadFilteredMore() }
                        )
                        return@PullToRefreshBox
                    }

                    when (state.view) {
                        AlbumView.TIMELINE -> TimelineGrid(
                            groups = state.groups,
                            paging = state.timelinePaging,
                            urlResolver = vm::url,
                            onLoadMore = { vm.loadMore() },
                            onOpen = { photo, list ->
                                viewerList = list
                                viewerPhoto = photo
                            },
                            onLongClick = { menuPhoto = it }
                        )
                        AlbumView.TREE -> FolderBrowser(
                            stack = state.treeStack,
                            folders = state.treeFolders,
                            photos = state.treePhotos,
                            paging = state.treePaging,
                            urlResolver = vm::url,
                            onOpenFolder = { vm.treeEnter(it) },
                            onGoUp = { vm.treeUp() },
                            onJumpTo = { vm.treeJumpTo(it) },
                            onOpenPhoto = { photo, list ->
                                viewerList = list
                                viewerPhoto = photo
                            },
                            onLongClick = { menuPhoto = it },
                            onLoadMore = { vm.loadTreePhotosMore() }
                        )
                        AlbumView.TAGS -> TagList(
                            tags = state.tags,
                            paging = state.tagPaging,
                            urlResolver = vm::url,
                            onOpen = vm::filterByTag,
                            onLoadMore = { vm.loadTagsMore() }
                        )
                        AlbumView.PEOPLE -> PeopleList(
                            people = state.people,
                            paging = state.personPaging,
                            urlResolver = vm::url,
                            onOpen = { p -> vm.filterByPerson(p.id, p.name) },
                            onLoadMore = { vm.loadPeopleMore() }
                        )
                        // 请求还在飞、points 还空着的情况上面那道守卫已经接管，
                        // 能走到这里的「空」就是接口真的返回了空。
                        AlbumView.GEO -> if (state.points.isEmpty()) {
                            Empty("没有带 GPS 信息的照片")
                        } else {
                            AMapView(
                                context = LocalContext.current,
                                points = state.points,
                                onSelect = { point -> vm.filterByGeo(point) }
                            )
                        }
                    }
                }
            }
        }

        viewerPhoto?.let { photo ->
            // Prefer the freshest objects so post-edit metadata (dimensions,
            // tags) is accurate; fall back to the snapshot if the state has
            // not loaded the photo yet.
            val list = if (liveById.isEmpty()) viewerList
            else viewerList.map { liveById[it.id] ?: it }
            val index = list.indexOfFirst { it.id == photo.id }.coerceAtLeast(0)
            LaunchedEffect(photo.id) { onFullscreenChange(true) }
            DisposableEffect(Unit) {
                onDispose { onFullscreenChange(false) }
            }
            PhotoViewerScreen(
                photos = list,
                initialIndex = index,
                urlResolver = vm::url,
                mediaUrlResolver = { id -> repository.mediaUrl(id) },
                onRotate = { p, angle, done -> vm.rotate(p, angle, done) },
                onFlip = { p, vertical, done -> vm.flip(p, vertical, done) },
                onRestore = { p, done -> vm.restore(p, done) },
                onDownload = downloadPhoto,
                onShare = sharePhoto,
                onDismiss = { viewerPhoto = null }
            )
        }

        // Deliberately NOT Scaffold's own snackbarHost: the full-screen viewer
        // is drawn over the Scaffold, so a failure reported by the viewer's 下载
        // button would be hidden behind it. Last child of the Box wins.
        NoticeHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    PhotoActionMenu(
        target = menuPhoto,
        onDownload = downloadPhoto,
        onShare = sharePhoto,
        onDismiss = { menuPhoto = null }
    )

    if (showUpload) {
        // Exactly the same directory browser as 复制/移动到 in the 文件 tab:
        // drill into any sub-folder (creating one inline if needed) and see
        // what is already there before the system picker opens.
        DirectoryPickerDialog(
            title = "上传到相册目录",
            dirs = uploadDirs,
            // Reopen where the last upload landed, if anywhere.
            initialDir = prefs.lastUploadDir.ifBlank { null },
            initialPath = prefs.lastUploadPath,
            confirmLabel = "选择照片/视频",
            listFiles = { dir, path -> repository.listFiles(dir, path).getOrNull() },
            createDir = { dir, path, name -> repository.mkdir(dir, path, name).isSuccess },
            onConfirm = { dir, path ->
                uploadDir = dir
                uploadPath = path
                prefs.rememberUploadTarget(dir, path)
                showUpload = false
                // Photos and videos both belong in the album.
                pickImages.launch("image/* video/*")
            },
            onDismiss = { showUpload = false }
        )
    }

    if (showUploadConfirm && pendingUploadUris.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                showUploadConfirm = false
                pendingUploadUris = emptyList()
            },
            title = { Text("上传 ${pendingUploadUris.size} 个文件") },
            text = {
                Column {
                    Text(
                        "目标：" + (uploadDir?.let { d -> if (uploadPath.isBlank()) d else "$d/$uploadPath" } ?: ""),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Checkbox(checked = uploadDeleteLocal, onCheckedChange = { uploadDeleteLocal = it })
                        Text("上传完成后删除本地副本", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val dir = uploadDir
                    if (dir != null) {
                        UploadWorker.enqueue(
                            context,
                            dir,
                            uploadPath.trim().trim('/'),
                            pendingUploadUris,
                            uploadDeleteLocal
                        )
                        vm.refresh()
                    }
                    showUploadConfirm = false
                    pendingUploadUris = emptyList()
                }) { Text("开始上传") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUploadConfirm = false
                    pendingUploadUris = emptyList()
                }) { Text("取消") }
            }
        )
    }
}

private fun labelOf(view: AlbumView) = when (view) {
    AlbumView.TIMELINE -> "时间轴"
    AlbumView.TREE -> "目录"
    AlbumView.TAGS -> "分类"
    AlbumView.PEOPLE -> "人物"
    AlbumView.GEO -> "地点"
}

@Composable
private fun FilterHeader(filter: String, onClear: () -> Unit) {
    // 地点筛选自带前缀，读作「地点：上海市 …」；其余仍用「筛选：xxx」。
    val text = if (filter.startsWith("地点 ")) "地点：" + filter.removePrefix("地点 ")
    else "筛选：$filter"
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onClear) { Text("清除") }
    }
}

/**
 * The filtered photo grid (分类 / 人物 / 目录 / 地点 drill-down).
 *
 * Paged like the timeline: the server used to hand back at most 300 photos and
 * nothing ever asked for more, so the 301st was simply unreachable.
 */
@Composable
private fun PhotoGrid(
    photos: List<PhotoItem>,
    paging: Paging,
    urlResolver: (String) -> String,
    onOpen: (PhotoItem, List<PhotoItem>) -> Unit,
    onLongClick: (PhotoItem) -> Unit,
    onLoadMore: () -> Unit
) {
    if (photos.isEmpty()) {
        Empty("没有照片")
        return
    }
    val gridState = rememberLazyGridState()
    PrefetchNextPage(gridState, paging.hasMore, onLoadMore)
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 108.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(photos, key = { it.id }) { photo ->
            PhotoTile(
                photo = photo,
                urlResolver = urlResolver,
                onClick = { onOpen(photo, photos) },
                onLongClick = { onLongClick(photo) }
            )
        }
        item(key = "photo-footer", span = { GridItemSpan(maxLineSpan) }) {
            ListFooter(paging, atEnd = !paging.hasMore, onRetry = onLoadMore)
        }
    }
}

/**
 * How many rows from the end the next page starts loading. Enough that the
 * request is in flight before the user reaches the footer, small enough that it
 * is not fired while the first screen is still being looked at.
 */
private const val PREFETCH_ROWS = 8

/**
 * 滚到离末尾 [PREFETCH_ROWS] 行时叫一次 [onLoadMore]。
 *
 * 四条分页列表（时间轴 / 分类 / 人物 / 筛选后的照片）共用它：它们的触发条件
 * 本来就该一致，各写一份迟早会漂。
 *
 * 触发条件里带上条目总数（`totalItems`）是必须的：一页填不满屏幕时 `nearEnd`
 * 会一直为真，只认 `nearEnd` 的话，第二页之后再也无人举手。[onLoadMore] 自己
 * 会拒绝叠加请求。
 */
@Composable
private fun PrefetchNextPage(
    gridState: LazyGridState,
    hasMore: Boolean,
    onLoadMore: () -> Unit
) {
    val nearEnd by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 &&
                last >= info.totalItemsCount - PREFETCH_ROWS
        }
    }
    val totalItems by remember(gridState) {
        derivedStateOf { gridState.layoutInfo.totalItemsCount }
    }
    LaunchedEffect(nearEnd, totalItems, hasMore) {
        if (nearEnd && hasMore) onLoadMore()
    }
}

/**
 * System-gallery style timeline: a three-column grid whose day headers stick
 * to the top while scrolling.
 *
 * Paged — the server hands back one page of items plus a cursor, and reaching
 * the end of the grid pulls the next one. The whole library in a single
 * response is what used to make this screen spin forever on a slow link.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineGrid(
    groups: List<me.erguotou.homehub.data.TimelineGroup>,
    paging: Paging,
    urlResolver: (String) -> String,
    onLoadMore: () -> Unit,
    onOpen: (PhotoItem, List<PhotoItem>) -> Unit,
    onLongClick: (PhotoItem) -> Unit
) {
    if (groups.isEmpty()) {
        Empty("还没有照片或视频。连上 WireGuard 并等待目录扫描完成。")
        return
    }
    val gridState = rememberLazyGridState()
    PrefetchNextPage(gridState, paging.hasMore, onLoadMore)

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        groups.forEach { group ->
            // Full-span header rows; stickyHeader is not in this foundation
            // version, so the day label scrolls with the content.
            item(key = "h-${group.key}", span = { GridItemSpan(maxLineSpan) }) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    SectionHeader(title = group.label, count = group.count.toInt())
                }
            }
            items(group.items, key = { it.id }) { photo ->
                PhotoTile(
                    photo = photo,
                    urlResolver = urlResolver,
                    onClick = { onOpen(photo, group.items) },
                    onLongClick = { onLongClick(photo) }
                )
            }
        }
        item(key = "timeline-footer", span = { GridItemSpan(maxLineSpan) }) {
            ListFooter(paging, atEnd = !paging.hasMore, onRetry = onLoadMore)
        }
    }
}

/** End-of-list state, shared by every paged grid: spinner / retry / 没有更多了. */
@Composable
private fun ListFooter(
    paging: Paging,
    atEnd: Boolean,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            paging.error != null -> TextButton(onClick = onRetry) { Text("加载失败，点击重试") }
            paging.loadingMore -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    "加载中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            atEnd -> Text(
                "没有更多了",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> Unit
        }
    }
}

/**
 * Hierarchical 目录 browser: one level at a time, with a clickable
 * breadcrumb and an "up" affordance instead of the old flat list of every
 * folder on the server. A rightward drag anywhere in the list goes up a level
 * too.
 *
 * The level comes from the server ([Repository.treeLevel]) rather than being
 * folded out of a whole-library response: the folders directly below, plus the
 * photos sitting in this one, paged. The old call shipped every photo in the
 * library to render what is usually a single row — 3.2 MB and 3 s on the real
 * library, for a first screen that needs one line of JSON.
 *
 * The breadcrumb starts with a Home icon (back to 全部目录); each segment
 * jumps straight to that level. It scrolls horizontally so deep paths stay
 * reachable instead of being ellipsised away.
 */
@Composable
private fun FolderBrowser(
    stack: List<TreeNode>,
    folders: List<TreeNode>,
    photos: List<PhotoItem>,
    paging: Paging,
    urlResolver: (String) -> String,
    onOpenFolder: (TreeNode) -> Unit,
    onGoUp: () -> Unit,
    onJumpTo: (Int) -> Unit,
    onOpenPhoto: (PhotoItem, List<PhotoItem>) -> Unit,
    onLongClick: (PhotoItem) -> Unit,
    onLoadMore: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 4.dp, end = 12.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (stack.isNotEmpty()) {
                IconButton(onClick = onGoUp) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一级")
                }
            }
            IconButton(onClick = { onJumpTo(-1) }) {
                Icon(
                    Icons.Filled.Home,
                    contentDescription = "全部目录",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (stack.isEmpty()) {
                Text(
                    text = "全部目录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp)
                )
            } else {
                stack.forEachIndexed { index, node ->
                    if (index > 0) {
                        Text(
                            text = " / ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = node.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (index == stack.lastIndex) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        maxLines = 1,
                        modifier = Modifier.clickable { onJumpTo(index) }
                    )
                }
            }
        }

        if (folders.isEmpty() && photos.isEmpty()) {
            Empty(if (stack.isEmpty()) "没有相册目录" else "这个目录是空的")
            return@Column
        }

        val gridState = rememberLazyGridState()
        PrefetchNextPage(gridState, paging.hasMore, onLoadMore)
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(stack.size) {
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = { if (dragged > 120f) onGoUp() },
                        onHorizontalDrag = { _, delta -> dragged += delta }
                    )
                }
        ) {
            items(folders, key = { "d:${it.dirId}/${it.path}" }, span = { GridItemSpan(maxLineSpan) }) { node ->
                FolderRow(node) { onOpenFolder(node) }
            }
            items(photos, key = { it.id }) { photo ->
                PhotoTile(
                    photo = photo,
                    urlResolver = urlResolver,
                    onClick = { onOpenPhoto(photo, photos) },
                    onLongClick = { onLongClick(photo) }
                )
            }
            item(key = "tree-footer", span = { GridItemSpan(maxLineSpan) }) {
                ListFooter(paging, atEnd = !paging.hasMore, onRetry = onLoadMore)
            }
        }
    }
}

@Composable
private fun FolderRow(node: TreeNode, onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                node.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 12.dp)
            )
            Text(
                "${node.count} 项",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TagList(
    tags: List<me.erguotou.homehub.data.TagSummary>,
    paging: Paging,
    urlResolver: (String) -> String,
    onOpen: (String) -> Unit,
    onLoadMore: () -> Unit
) {
    if (tags.isEmpty()) {
        Empty("还没有标签，等待识别任务完成")
        return
    }
    val gridState = rememberLazyGridState()
    PrefetchNextPage(gridState, paging.hasMore, onLoadMore)
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 108.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 不给 key：同一屏里 (tag, kind) 唯一，但服务端游标已经保证两页不重叠，
        // 再加一层 key 只会把「万一重复」变成直接崩。分类是几百条的量级，
        // 位置标识足够。
        items(tags) { tag ->
            Card(modifier = Modifier.clickable { onOpen(tag.tag) }) {
                // Caption goes BELOW the cover: overlaying it on the thumbnail
                // made both the photo and the text unreadable.
                Column {
                    tag.coverUrl?.let {
                        AsyncImage(
                            model = urlResolver(it),
                            contentDescription = tag.tag,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        )
                    }
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(
                            tag.tag,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${if (tag.kind == "scene") "场景" else "物体"} · ${tag.photoCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item(key = "tag-footer", span = { GridItemSpan(maxLineSpan) }) {
            ListFooter(paging, atEnd = !paging.hasMore, onRetry = onLoadMore)
        }
    }
}

@Composable
private fun PeopleList(
    people: List<me.erguotou.homehub.data.PersonGroup>,
    paging: Paging,
    urlResolver: (String) -> String,
    onOpen: (me.erguotou.homehub.data.PersonGroup) -> Unit,
    onLoadMore: () -> Unit
) {
    if (people.isEmpty()) {
        Empty("还没有人像分组")
        return
    }
    val gridState = rememberLazyGridState()
    PrefetchNextPage(gridState, paging.hasMore, onLoadMore)
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 108.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(people) { person ->
            Card(modifier = Modifier.clickable { onOpen(person) }) {
                Column {
                    person.coverUrl?.let {
                        AsyncImage(
                            model = urlResolver(it),
                            contentDescription = person.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        )
                    }
                    Text(
                        person.name ?: "未命名 #${person.id}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
        item(key = "people-footer", span = { GridItemSpan(maxLineSpan) }) {
            ListFooter(paging, atEnd = !paging.hasMore, onRetry = onLoadMore)
        }
    }
}
