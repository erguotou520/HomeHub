package me.erguotou.homehub.ui.screens.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import me.erguotou.homehub.data.DirEntry
import me.erguotou.homehub.data.FileEntry
import me.erguotou.homehub.ui.components.Empty
import me.erguotou.homehub.ui.components.ErrorText
import me.erguotou.homehub.ui.components.Loading
import me.erguotou.homehub.util.formatBytes
import me.erguotou.homehub.util.formatDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: FilesViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var showNewDir by remember { mutableStateOf(false) }
    var showUpload by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<List<FileEntry>?>(null) }
    var pendingUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }

    var renameEntry by remember { mutableStateOf<FileEntry?>(null) }
    var copyMoveEntry by remember { mutableStateOf<FileEntry?>(null) }
    var copyMoveOp by remember { mutableStateOf<String?>(null) }
    var pendingDownload by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }

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
            }
        }
    }

    Scaffold(
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
                    state.error != null && state.entries.isEmpty() -> ErrorText(state.error!!) { retryDir?.let { vm.open(it.first, it.second) } }
                    state.entries.isEmpty() -> Empty("这个目录是空的", icon = Icons.Outlined.FolderOpen)
                    else -> LazyColumn {
                        items(state.entries, key = { it.path }) { entry ->
                            FileRow(
                                entry = entry,
                                selected = state.selected.contains(entry.path),
                                onOpen = { vm.navigateTo(entry) },
                                onLongPress = { vm.toggleSelect(entry) },
                                onDownload = { requestDownload(it) },
                                onRename = { renameEntry = it },
                                onCopyMove = { e, op ->
                                    copyMoveEntry = e
                                    copyMoveOp = op
                                },
                                onDelete = { confirmDelete = listOf(it) }
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
                            modifier = Modifier.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "已选 ${state.selected.size}",
                                modifier = Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.labelLarge
                            )
                            TextButton(onClick = { vm.clearSelection() }) { Text("取消") }
                            TextButton(onClick = {
                                confirmDelete = state.entries.filter { state.selected.contains(it.path) }
                            }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
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

    copyMoveOp?.let { op ->
        val entry = copyMoveEntry
        if (entry != null) {
            CopyMoveDialog(
                op = op,
                dirs = state.dirs,
                onConfirm = { toDir, toPath ->
                    copyMoveOp = null
                    copyMoveEntry = null
                    if (toDir.isNotBlank()) {
                        vm.copyOrMove(listOf(entry), op, toDir, toPath)
                    }
                },
                onDismiss = {
                    copyMoveOp = null
                    copyMoveEntry = null
                }
            )
        }
    }

    confirmDelete?.let { entries ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除 ${entries.size} 项？") },
            text = { Text("文件会先移入回收站，30 天后自动清理。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(entries)
                    confirmDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } }
        )
    }

    state.preview?.let { text ->
        AlertDialog(
            onDismissRequest = { vm.dismissPreview() },
            title = { Text("预览") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { vm.dismissPreview() }) { Text("关闭") } }
        )
    }

    state.imageUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { vm.dismissImage() },
            title = { Text("预览") },
            text = {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = { TextButton(onClick = { vm.dismissImage() }) { Text("关闭") } }
        )
    }
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

@Composable
private fun CopyMoveDialog(
    op: String,
    dirs: List<DirEntry>,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var toDir by remember { mutableStateOf(dirs.firstOrNull()?.name ?: "") }
    var toPath by remember { mutableStateOf("") }
    var dirMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (op == "copy") "复制到" else "移动到") },
        text = {
            Column {
                Box {
                    OutlinedButton(onClick = { dirMenu = true }) {
                        Text(toDir.ifBlank { "选择目标目录" })
                    }
                    DropdownMenu(expanded = dirMenu, onDismissRequest = { dirMenu = false }) {
                        dirs.forEach { d ->
                            DropdownMenuItem(
                                text = { Text(d.name) },
                                onClick = { toDir = d.name; dirMenu = false }
                            )
                        }
                    }
                }
                TextField(
                    value = toPath,
                    onValueChange = { toPath = it },
                    label = { Text("目标子目录（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(toDir, toPath) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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

@Composable
private fun FileRow(
    entry: FileEntry,
    selected: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onDownload: (FileEntry) -> Unit,
    onRename: (FileEntry) -> Unit,
    onCopyMove: (FileEntry, String) -> Unit,
    onDelete: (FileEntry) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (entry.isDir) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = null,
            tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(entry.name, style = MaterialTheme.typography.bodyMedium)
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
                        text = { Text("下载") },
                        onClick = { menuOpen = false; onDownload(entry) }
                    )
                }
                DropdownMenuItem(
                    text = { Text("重命名") },
                    onClick = { menuOpen = false; onRename(entry) }
                )
                DropdownMenuItem(
                    text = { Text("复制到…") },
                    onClick = { menuOpen = false; onCopyMove(entry, "copy") }
                )
                DropdownMenuItem(
                    text = { Text("移动到…") },
                    onClick = { menuOpen = false; onCopyMove(entry, "move") }
                )
                DropdownMenuItem(
                    text = { Text("删除") },
                    onClick = { menuOpen = false; onDelete(entry) }
                )
            }
        }
        if (selected) {
            Icon(Icons.Default.Delete, contentDescription = "已选中", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
