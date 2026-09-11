package me.erguotou.homehub.ui.screens.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.erguotou.homehub.data.FileEntry
import me.erguotou.homehub.ui.components.DirectoryPickerDialog
import me.erguotou.homehub.ui.components.Empty
import me.erguotou.homehub.ui.components.ErrorText
import me.erguotou.homehub.ui.components.Loading
import me.erguotou.homehub.util.FileKind
import me.erguotou.homehub.util.formatBytes
import me.erguotou.homehub.util.formatDateTime

/** A pending 复制到 / 移动到 for one or more entries. */
private data class CopyMovePlan(val entries: List<FileEntry>, val op: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    onFullscreenChange: (Boolean) -> Unit = {},
    vm: FilesViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var showNewDir by remember { mutableStateOf(false) }
    var showUpload by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<List<FileEntry>?>(null) }
    var pendingUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }

    var renameEntry by remember { mutableStateOf<FileEntry?>(null) }
    var copyMove by remember { mutableStateOf<CopyMovePlan?>(null) }
    var pendingDownload by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }

    // Status lines (复制/移动/删除 results…) surface as snackbars.
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    /**
     * Actions that open a Dialog from inside a DropdownMenu are deferred by a
     * frame or two: the menu's own dismissal animation would otherwise swallow
     * the very tap that asked for the dialog, which reads as "点了删除没反应".
     */
    fun afterMenu(action: () -> Unit) {
        scope.launch {
            delay(120)
            action()
        }
    }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            pendingUris = uris
            showUpload = true
        }
    }

    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val (_, bytes) = pendingDownload ?: return@rememberLauncherForActivityResult
        pendingDownload = null
        uri?.let { target ->
            try {
                context.contentResolver.openOutputStream(target)?.use { it.write(bytes) }
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    fun requestDownload(entry: FileEntry) {
        vm.download(entry) { bytes ->
            if (bytes != null) {
                pendingDownload = entry.name to bytes
                downloadLauncher.launch(entry.name)
            } else {
                scope.launch { snackbar.showSnackbar("下载失败，请检查连接") }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(state.currentDir?.let { "$it/${state.currentPath}" } ?: "文件")
                    },
                    navigationIcon = {
                        if (state.currentPath.isNotBlank()) {
                            IconButton(onClick = { vm.navigateUp() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上级")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.toggleDesc() }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序方向")
                        }
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            listOf("name" to "按名称", "mtime" to "按时间", "size" to "按大小").forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { vm.setSort(key); menuExpanded = false }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (state.desc) "降序" else "升序") },
                                onClick = { vm.toggleDesc(); menuExpanded = false }
                            )
                        }
                        IconButton(onClick = {
                            val dir = state.currentDir ?: return@IconButton
                            vm.open(dir, state.currentPath)
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                )
            },
            floatingActionButton = {
                Row {
                    androidx.compose.material3.FloatingActionButton(onClick = { showNewDir = true }) {
                        Icon(Icons.Default.Add, contentDescription = "新建目录")
                    }
                    androidx.compose.material3.SmallFloatingActionButton(
                        onClick = { pickFiles.launch("*/*") },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = "上传")
                    }
                }
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                val segments = vm.breadcrumbSegments()
                val currentDir = state.currentDir
                val retryDir = currentDir?.let { it to state.currentPath }
                if (currentDir != null && segments.isNotEmpty()) {
                    Breadcrumb(
                        root = currentDir,
                        segments = segments,
                        onRoot = { vm.open(currentDir, "") },
                        onSegment = { vm.open(currentDir, segments.take(it + 1).joinToString("/")) }
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        state.loading && state.entries.isEmpty() -> Loading()
                        state.error != null && state.entries.isEmpty() ->
                            ErrorText(state.error!!) { retryDir?.let { vm.open(it.first, it.second) } }
                        state.entries.isEmpty() -> Empty("这个目录是空的", icon = Icons.Outlined.FolderOpen)
                        else -> LazyColumn {
                            items(state.entries, key = { it.path }) { entry ->
                                FileRow(
                                    entry = entry,
                                    selected = state.selected.contains(entry.path),
                                    selectionMode = state.selected.isNotEmpty(),
                                    onOpen = { vm.navigateTo(entry) },
                                    onToggleSelect = { vm.toggleSelect(entry) },
                                    onOpenExternal = { vm.openExternally(it) },
                                    onDownload = { requestDownload(it) },
                                    onRename = { e -> afterMenu { renameEntry = e } },
                                    onCopyMove = { e, op -> afterMenu { copyMove = CopyMovePlan(listOf(e), op) } },
                                    onDelete = { e -> afterMenu { confirmDelete = listOf(e) } }
                                )
                            }
                        }
                    }

                    if (state.selected.isNotEmpty()) {
                        // M3 floating action bar for the multi-select mode.
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(12.dp),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            tonalElevation = 6.dp,
                            shadowElevation = 4.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "已选 ${state.selected.size}",
                                    modifier = Modifier.padding(start = 12.dp),
                                    style = MaterialTheme.typography.labelLarge
                                )
                                TextButton(onClick = { vm.clearSelection() }) { Text("取消") }
                                TextButton(onClick = {
                                    val picked = selectedEntries(state)
                                    if (picked.isNotEmpty()) copyMove = CopyMovePlan(picked, "copy")
                                }) { Text("复制") }
                                TextButton(onClick = {
                                    val picked = selectedEntries(state)
                                    if (picked.isNotEmpty()) copyMove = CopyMovePlan(picked, "move")
                                }) { Text("移动") }
                                TextButton(onClick = {
                                    confirmDelete = selectedEntries(state)
                                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }

        // The viewer is the last child so it covers the file list's own top bar
        // and the bottom navigation bar (Root hides it while fullscreen).
        state.viewer?.let { session ->
            LaunchedEffect(session) { onFullscreenChange(true) }
            DisposableEffect(Unit) {
                onDispose { onFullscreenChange(false) }
            }
            FileViewerScreen(
                items = session.items,
                initialIndex = session.index,
                urlFor = vm::urlFor,
                loadText = { vm.loadText(it) },
                loadBytes = { vm.loadBytes(it) },
                onRotate = { item, angle, done -> vm.rotate(item, angle, done) },
                onFlip = { item, vertical, done -> vm.flip(item, vertical, done) },
                onRestore = { item, done -> vm.restore(item, done) },
                onOpenExternal = { item -> vm.openExternally(item.entry, item) },
                onDownload = { item -> requestDownload(item.entry) },
                onDismiss = { vm.dismissViewer() }
            )
        }
    }

    if (showNewDir) {
        NameDialog("新建目录", "") { name ->
            showNewDir = false
            if (name.isNotBlank()) vm.mkdir(name)
        }
    }

    if (showUpload && pendingUris.isNotEmpty()) {
        var deleteLocal by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = {
                showUpload = false
                pendingUris = emptyList()
            },
            title = { Text("上传 ${pendingUris.size} 个文件") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteLocal, onCheckedChange = { deleteLocal = it })
                    Text("上传完成后删除本地副本", modifier = Modifier.padding(start = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.upload(pendingUris, deleteLocal)
                    showUpload = false
                    pendingUris = emptyList()
                }) { Text("开始上传") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUpload = false
                    pendingUris = emptyList()
                }) { Text("取消") }
            }
        )
    }

    renameEntry?.let { entry ->
        NameDialog("重命名", entry.name) { name ->
            renameEntry = null
            if (name.isNotBlank() && name != entry.name) vm.rename(entry, name)
        }
    }

    // 复制到 / 移动到: a real directory browser — drill into sub-folders and
    // see what is already there instead of typing a path blind.
    copyMove?.let { plan ->
        DirectoryPickerDialog(
            title = if (plan.op == "copy") {
                if (plan.entries.size > 1) "复制 ${plan.entries.size} 项到" else "复制到"
            } else {
                if (plan.entries.size > 1) "移动 ${plan.entries.size} 项到" else "移动到"
            },
            dirs = state.dirs,
            initialDir = state.currentDir,
            initialPath = state.currentPath,
            confirmLabel = if (plan.op == "copy") "复制到这里" else "移动到这里",
            listFiles = { dir, path -> vm.listAt(dir, path) },
            createDir = { dir, path, name -> vm.createDirAt(dir, path, name) },
            onConfirm = { dir, path ->
                copyMove = null
                vm.copyOrMove(plan.entries, plan.op, dir, path)
            },
            onDismiss = { copyMove = null }
        )
    }

    // Destructive actions always stop here first.
    confirmDelete?.let { entries ->
        DeleteConfirmDialog(
            entries = entries,
            busy = state.busy,
            onConfirm = {
                confirmDelete = null
                vm.clearSelection()
                vm.delete(entries)
            },
            onDismiss = { confirmDelete = null }
        )
    }
}

/** Entries currently in the listing that are selected. */
private fun selectedEntries(state: FilesUiState): List<FileEntry> =
    state.entries.filter { state.selected.contains(it.path) }

/**
 * Delete confirmation: names what goes away, separates folders (which take
 * their contents with them) from plain files, and states where it lands.
 */
@Composable
private fun DeleteConfirmDialog(
    entries: List<FileEntry>,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val folders = entries.count { it.isDir }
    val files = entries.size - folders
    val preview = entries.take(5).joinToString("\n") { it.name }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
        title = { Text(if (entries.size == 1) "删除这一项？" else "删除这 ${entries.size} 项？") },
        text = {
            Column {
                Text(
                    buildString {
                        append("· ")
                        if (folders > 0) append("文件夹 $folders 个（连同其中内容）")
                        if (folders > 0 && files > 0) append("、")
                        if (files > 0) append("文件 $files 个")
                        append("\n· 共 ")
                        append(formatBytes(entries.sumOf { it.size ?: 0 }))
                    }
                )
                Text(
                    preview + if (entries.size > 5) "\n…等 ${entries.size} 项" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    "删除后先进入回收站，30 天后自动清理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        }
    )
}

@Composable
private fun Breadcrumb(
    root: String,
    segments: List<String>,
    onRoot: () -> Unit,
    onSegment: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            root,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onRoot() }
        )
        segments.forEachIndexed { index, seg ->
            Text(" / ", style = MaterialTheme.typography.bodySmall)
            Text(
                seg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onSegment(index) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onConfirm("") },
        title = { Text(title) },
        text = { TextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = { onConfirm("") }) { Text("取消") } }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntry,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
    onDownload: (FileEntry) -> Unit,
    onRename: (FileEntry) -> Unit,
    onCopyMove: (FileEntry, String) -> Unit,
    onDelete: (FileEntry) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val kind = remember(entry.path) { kindOf(entry) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Long press starts multi-select (it never was wired up before,
            // so the batch bar was unreachable); once a selection exists,
            // plain taps toggle instead of opening.
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect() else onOpen() },
                onLongClick = onToggleSelect
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            when {
                entry.isDir -> Icons.Default.Folder
                kind == FileKind.IMAGE -> Icons.Default.Image
                else -> Icons.Default.Description
            },
            contentDescription = null,
            tint = if (entry.isDir) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    if (!entry.isDir) append(formatBytes(entry.size ?: 0)).append(" · ")
                    append(formatDateTime(entry.modified ?: 0))
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "操作")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (!entry.isDir) {
                    DropdownMenuItem(
                        text = { Text(if (kind == FileKind.OTHER) "用其他应用打开" else "打开") },
                        onClick = { menuOpen = false; onOpen() }
                    )
                    if (kind != FileKind.OTHER) {
                        DropdownMenuItem(
                            text = { Text("用其他应用打开") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                            onClick = { menuOpen = false; onOpenExternal(entry) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("下载到本机") },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        onClick = { menuOpen = false; onDownload(entry) }
                    )
                }
                DropdownMenuItem(
                    text = { Text("重命名") },
                    onClick = { menuOpen = false; onRename(entry) }
                )
                DropdownMenuItem(
                    text = { Text("复制到…") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) },
                    onClick = { menuOpen = false; onCopyMove(entry, "copy") }
                )
                DropdownMenuItem(
                    text = { Text("移动到…") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) },
                    onClick = { menuOpen = false; onCopyMove(entry, "move") }
                )
                DropdownMenuItem(
                    text = { Text("删除") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = { menuOpen = false; onDelete(entry) }
                )
            }
        }
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
        }
    }
}
