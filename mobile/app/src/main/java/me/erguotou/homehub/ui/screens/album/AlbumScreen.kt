package me.erguotou.homehub.ui.screens.album

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import me.erguotou.homehub.data.DirEntry
import me.erguotou.homehub.data.PhotoItem
import me.erguotou.homehub.data.Repository
import me.erguotou.homehub.ui.components.Empty
import me.erguotou.homehub.ui.components.ErrorText
import me.erguotou.homehub.ui.components.Loading
import me.erguotou.homehub.ui.components.PhotoTile
import me.erguotou.homehub.ui.components.SectionHeader
import me.erguotou.homehub.work.UploadWorker

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumScreen(onFullscreenChange: (Boolean) -> Unit = {}, vm: AlbumViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { Repository(context) }

    var viewerPhoto by remember { mutableStateOf<PhotoItem?>(null) }
    var viewerList by remember { mutableStateOf(listOf<PhotoItem>()) }

    var showUpload by remember { mutableStateOf(false) }
    var uploadDirs by remember { mutableStateOf<List<DirEntry>>(emptyList()) }
    var uploadDir by remember { mutableStateOf<String?>(null) }
    var uploadSubdir by remember { mutableStateOf("") }
    var pendingUploadUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showUploadConfirm by remember { mutableStateOf(false) }
    var uploadDeleteLocal by remember { mutableStateOf(false) }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            pendingUploadUris = uris
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
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    scope.launch {
                        repository.dirs().onSuccess { list ->
                            val albumDirs = list.filter { it.marks.contains("album") }.ifEmpty { list }
                            uploadDirs = albumDirs
                            uploadDir = albumDirs.firstOrNull()?.name
                            uploadSubdir = ""
                            showUpload = true
                        }
                    }
                }) {
                    Icon(Icons.Default.Upload, contentDescription = "上传")
                }
            }
        ) { padding ->
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = state.loading,
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

                    if (state.loading && state.groups.isEmpty() && state.filtered.isEmpty()) {
                        Loading()
                        return@PullToRefreshBox
                    }
                    state.error?.let {
                        ErrorText(it, onRetry = { vm.refresh() })
                        return@PullToRefreshBox
                    }

                    if (state.activeFilter != null) {
                        FilterHeader(state.activeFilter!!) { vm.clearFilter() }
                        PhotoGrid(state.filtered, vm::url) { photo, list ->
                            viewerList = list
                            viewerPhoto = photo
                        }
                        return@PullToRefreshBox
                    }

                    when (state.view) {
                        AlbumView.TIMELINE -> TimelineGrid(state.groups, vm::url) { photo, list ->
                            viewerList = list
                            viewerPhoto = photo
                        }
                        AlbumView.TREE -> TreeList(state.trees, vm::url, vm::filterByTree)
                        AlbumView.TAGS -> TagList(state.tags, vm::url, vm::filterByTag)
                        AlbumView.PEOPLE -> PeopleList(state.people, vm::url) { p ->
                            vm.filterByPerson(p.id, p.name)
                        }
                        AlbumView.GEO -> when {
                            state.loading && state.points.isEmpty() -> Loading()
                            state.points.isEmpty() -> Empty("没有带 GPS 信息的照片")
                            else -> AMapView(
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
            val index = viewerList.indexOfFirst { it.id == photo.id }.coerceAtLeast(0)
            LaunchedEffect(photo.id) { onFullscreenChange(true) }
            DisposableEffect(Unit) {
                onDispose { onFullscreenChange(false) }
            }
            PhotoViewerScreen(
                photos = viewerList,
                initialIndex = index,
                urlResolver = vm::url,
                mediaUrlResolver = { id -> repository.mediaUrl(id) },
                onRotate = { p, angle, done -> vm.rotate(p, angle, done) },
                onDismiss = { viewerPhoto = null }
            )
        }
    }

    if (showUpload) {
        UploadTargetDialog(
            dirs = uploadDirs,
            selectedDir = uploadDir,
            subdir = uploadSubdir,
            onDirChange = { uploadDir = it },
            onSubdirChange = { uploadSubdir = it },
            onConfirm = {
                if (uploadDir != null) {
                    showUpload = false
                    // Photos and videos both belong in the album.
                    pickImages.launch("image/* video/*")
                }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = uploadDeleteLocal, onCheckedChange = { uploadDeleteLocal = it })
                    Text("上传完成后删除本地副本", modifier = Modifier.padding(start = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val dir = uploadDir
                    if (dir != null) {
                        UploadWorker.enqueue(
                            context,
                            dir,
                            uploadSubdir.trim().trim('/'),
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

@Composable
private fun UploadTargetDialog(
    dirs: List<DirEntry>,
    selectedDir: String?,
    subdir: String,
    onDirChange: (String) -> Unit,
    onSubdirChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var dirMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上传到相册目录") },
        text = {
            Column {
                Box {
                    OutlinedButton(onClick = { dirMenu = true }) {
                        Text(selectedDir ?: "选择目录")
                    }
                    DropdownMenu(expanded = dirMenu, onDismissRequest = { dirMenu = false }) {
                        dirs.forEach { d ->
                            DropdownMenuItem(
                                text = { Text(d.name) },
                                onClick = { onDirChange(d.name); dirMenu = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = subdir,
                    onValueChange = onSubdirChange,
                    label = { Text("子目录（可选，自动新建）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("选择照片/视频") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "筛选：$filter",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onClear) { Text("清除") }
    }
}

@Composable
private fun PhotoGrid(
    photos: List<PhotoItem>,
    urlResolver: (String) -> String,
    onOpen: (PhotoItem, List<PhotoItem>) -> Unit
) {
    if (photos.isEmpty()) {
        Empty("没有照片")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(photos, key = { it.id }) { photo ->
            PhotoTile(photo = photo, urlResolver = urlResolver) { onOpen(photo, photos) }
        }
    }
}

/**
 * System-gallery style timeline: a three-column grid whose day headers stick
 * to the top while scrolling.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineGrid(
    groups: List<me.erguotou.homehub.data.TimelineGroup>,
    urlResolver: (String) -> String,
    onOpen: (PhotoItem, List<PhotoItem>) -> Unit
) {
    if (groups.isEmpty()) {
        Empty("还没有照片或视频。连上 WireGuard 并等待目录扫描完成。")
        return
    }
    LazyVerticalGrid(
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
                PhotoTile(photo, urlResolver) { onOpen(photo, group.items) }
            }
        }
    }
}

@Composable
private fun TreeList(
    trees: List<me.erguotou.homehub.data.TreeGroup>,
    urlResolver: (String) -> String,
    onOpen: (me.erguotou.homehub.data.TreeGroup) -> Unit
) {
    if (trees.isEmpty()) {
        Empty("没有相册目录")
        return
    }
    LazyColumn {
        items(trees) { group ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clickable { onOpen(group) }
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    group.items.firstOrNull()?.let { photo ->
                        AsyncImage(
                            model = urlResolver(photo.thumbUrl),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .aspectRatio(1f)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        )
                    }
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text("${group.dirName}/${group.path}", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${group.count} 张",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TagList(
    tags: List<me.erguotou.homehub.data.TagSummary>,
    urlResolver: (String) -> String,
    onOpen: (String) -> Unit
) {
    if (tags.isEmpty()) {
        Empty("还没有标签，等待识别任务完成")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(tags) { tag ->
            Card(modifier = Modifier.clickable { onOpen(tag.tag) }) {
                Box {
                    tag.coverUrl?.let {
                        AsyncImage(
                            model = urlResolver(it),
                            contentDescription = tag.tag,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        )
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(8.dp)
                    ) {
                        Text(tag.tag, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${if (tag.kind == "scene") "场景" else "物体"} · ${tag.photoCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeopleList(
    people: List<me.erguotou.homehub.data.PersonGroup>,
    urlResolver: (String) -> String,
    onOpen: (me.erguotou.homehub.data.PersonGroup) -> Unit
) {
    if (people.isEmpty()) {
        Empty("还没有人脸分组")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(people) { person ->
            Card(modifier = Modifier.clickable { onOpen(person) }) {
                Box {
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
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
