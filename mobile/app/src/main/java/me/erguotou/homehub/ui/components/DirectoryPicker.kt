package me.erguotou.homehub.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import me.erguotou.homehub.data.DirEntry
import me.erguotou.homehub.data.FileEntry
import me.erguotou.homehub.util.formatBytes

/**
 * Directory chooser shared by every "put this file somewhere on the NAS"
 * flow: 复制到 / 移动到 in the 文件 tab and 上传 in the 相册.
 *
 * It browses the registered directories the way a file manager does — drill
 * into any sub-folder, walk back up with the breadcrumb — and lists the files
 * that are already in the folder you are standing in, so the target is obvious
 * before committing. A sub-folder can be created inline instead of typing a
 * path blind, which is what the old "目标子目录（可选）" text field asked for.
 */
@Composable
fun DirectoryPickerDialog(
    title: String,
    dirs: List<DirEntry>,
    initialDir: String?,
    initialPath: String = "",
    confirmLabel: String = "选择此目录",
    allowCreateDir: Boolean = true,
    listFiles: suspend (dir: String, path: String) -> List<FileEntry>?,
    createDir: (suspend (dir: String, path: String, name: String) -> Boolean)? = null,
    onConfirm: (dir: String, path: String) -> Unit,
    onDismiss: () -> Unit
) {
    var currentDir by remember { mutableStateOf(initialDir ?: dirs.firstOrNull()?.name) }
    var currentPath by remember { mutableStateOf(initialPath.trim().trim('/')) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var showNewDir by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentDir, currentPath, reload) {
        val dir = currentDir ?: return@LaunchedEffect
        loading = true
        error = null
        val list = listFiles(dir, currentPath)
        loading = false
        if (list == null) {
            // A remembered sub-folder (相册 uploads reopen the last target) may
            // have been renamed or deleted since. Walk up instead of stranding
            // the user on an error they cannot dismiss.
            if (currentPath.isNotBlank()) {
                currentPath = currentPath.trimEnd('/').substringBeforeLast('/', "")
                return@LaunchedEffect
            }
            entries = emptyList()
            error = "无法读取该目录，请检查连接"
            return@LaunchedEffect
        }
        entries = list
    }

    val folders = entries.filter { it.isDir }
    val files = entries.filter { !it.isDir }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp)
                )
                Text(
                    "目标：" + (currentDir?.let { d ->
                        if (currentPath.isBlank()) d else "$d/$currentPath"
                    } ?: "—"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp)
                )

                // Registered roots — switching keeps the file tree honest
                // (paths are always relative to one named directory).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    dirs.forEach { d ->
                        FilterChip(
                            selected = d.name == currentDir,
                            onClick = { currentDir = d.name; currentPath = "" },
                            label = { Text(d.name) }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            currentPath = currentPath.trimEnd('/').substringBeforeLast('/', "")
                        },
                        enabled = currentPath.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一级")
                    }
                    Text(
                        currentDir ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { currentPath = "" }
                    )
                    currentPath.split('/').filter { it.isNotBlank() }.forEachIndexed { index, segment ->
                        Text(" / ", style = MaterialTheme.typography.bodySmall)
                        Text(
                            segment,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                val segments = currentPath.split('/').filter { it.isNotBlank() }
                                currentPath = segments.take(index + 1).joinToString("/")
                            }
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        loading && entries.isEmpty() -> Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }
                        error != null -> Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(error!!, color = MaterialTheme.colorScheme.error)
                        }
                        folders.isEmpty() && files.isEmpty() -> Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "这个目录是空的，可以在这里新建子目录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        else -> LazyColumn {
                            items(folders, key = { "d:${it.path}" }) { folder ->
                                PickerRow(
                                    icon = {
                                        Icon(
                                            Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    title = folder.name,
                                    subtitle = "文件夹",
                                    trailing = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    onClick = {
                                        currentPath = if (currentPath.isBlank()) {
                                            folder.name
                                        } else {
                                            "${currentPath.trimEnd('/')}/${folder.name}"
                                        }
                                    }
                                )
                            }
                            // Files are listed for orientation only — you pick
                            // the folder, not the file.
                            items(files, key = { "f:${it.path}" }) { file ->
                                PickerRow(
                                    icon = {
                                        Icon(
                                            Icons.Default.Description,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    title = file.name,
                                    subtitle = formatBytes(file.size ?: 0),
                                    muted = true
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (allowCreateDir && createDir != null && currentDir != null) {
                        TextButton(
                            onClick = { showNewDir = true },
                            enabled = !creating
                        ) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                            Text("新建目录", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    Box(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        onClick = { currentDir?.let { onConfirm(it, currentPath) } },
                        enabled = currentDir != null && !loading
                    ) { Text(confirmLabel) }
                }
            }
        }
    }

    if (showNewDir) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewDir = false },
            title = { Text("新建目录") },
            text = {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("目录名") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val dir = currentDir
                        val created = createDir
                        val trimmed = name.trim()
                        if (dir != null && created != null && trimmed.isNotEmpty()) {
                            showNewDir = false
                            creating = true
                            scope.launch {
                                val ok = created(dir, currentPath, trimmed)
                                creating = false
                                if (ok) {
                                    // Follow the newly created folder so the
                                    // next tap on 确定 is a no-op.
                                    currentPath = if (currentPath.isBlank()) {
                                        trimmed
                                    } else {
                                        "${currentPath.trimEnd('/')}/$trimmed"
                                    }
                                    reload++
                                } else {
                                    error = "新建目录失败，可能是名称重复或没有权限"
                                }
                            }
                        }
                    }
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewDir = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun PickerRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
    muted: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (muted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing?.invoke()
    }
}
