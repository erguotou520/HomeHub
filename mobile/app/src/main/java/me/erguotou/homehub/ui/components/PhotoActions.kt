package me.erguotou.homehub.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.erguotou.homehub.data.PhotoItem
import me.erguotou.homehub.data.Repository
import me.erguotou.homehub.util.FileKinds
import me.erguotou.homehub.util.SavedFile
import me.erguotou.homehub.util.Sharing
import me.erguotou.homehub.util.TempFiles

/**
 * The album's 「下载到本机」 plumbing, shared by the 文件 list and the 相册 action
 * sheet so both save through exactly one code path — and, now, report the same
 * way: the outcome lands on [snackbar], naming where the bytes went.
 *
 * Returns a `(fileName, bytes) -> Unit` that parks the bytes until the system's
 * "save as" dialog comes back, then writes them to whatever the user picked.
 * The payload has to go through a state slot because the launcher's result
 * callback runs outside composition.
 */
@Composable
fun rememberLocalSaver(snackbar: SnackbarHostState): (String, ByteArray) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        // Read and clear first: the callback also fires with a null uri when
        // the user backs out of the picker, and the bytes must not be kept.
        val payload = pending
        pending = null
        // Backing out is not a failure — nothing was attempted, so say nothing.
        if (uri == null || payload == null) return@rememberLauncherForActivityResult
        val written = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(payload.second) }
                ?: error("no output stream for $uri")
        }.isSuccess
        scope.launch {
            // Naming the folder is the point: the picker can be pointed at any
            // of a dozen places on the device and gives no receipt of its own.
            val where = SavedFile.location(uri)
            if (written) {
                snackbar.notify(where?.let { "已保存到 $it" } ?: "已保存")
            } else {
                snackbar.notify("保存失败", NoticeKind.Failure)
            }
        }
    }
    return { name, bytes ->
        pending = name to bytes
        launcher.launch(name)
    }
}

/**
 * Fetch one item's original bytes and hand them to [consume].
 *
 * The fetch runs in the CALLER's composition scope on purpose. A scope owned by
 * the action sheet would be cancelled the moment the sheet is dismissed — i.e.
 * before the bytes arrive — and neither the "save as" dialog nor the share
 * sheet would ever open.
 */
@Composable
private fun rememberPhotoFetcher(
    repository: Repository,
    snackbar: SnackbarHostState,
    failure: String,
    consume: suspend (PhotoItem, ByteArray) -> Unit
): (PhotoItem) -> Unit {
    val scope = rememberCoroutineScope()
    return { photo ->
        scope.launch {
            repository.download(photo.dirName, photo.relPath).fold(
                onSuccess = { consume(photo, it) },
                onFailure = { snackbar.notify(failure, NoticeKind.Failure) }
            )
        }
    }
}

/**
 * 「下载到本机」 for one album item: fetch the original, then let the user pick a
 * destination through the system "save as" dialog. Returns a fire-and-forget
 * `(photo) -> Unit`, which is what the grid's action sheet and the full-screen
 * viewer's toolbar both call.
 */
@Composable
fun rememberPhotoDownloader(
    repository: Repository,
    snackbar: SnackbarHostState
): (PhotoItem) -> Unit {
    val saveLocal = rememberLocalSaver(snackbar)
    return rememberPhotoFetcher(repository, snackbar, "下载失败") { photo, bytes ->
        saveLocal(photo.name, bytes)
    }
}

/**
 * 「分享」 for one album item: fetch the original into scratch space, then open
 * the system share sheet ([Sharing]) on it.
 *
 * The bytes must hit the disk first — a share receiver reads a `content://` URI
 * asynchronously, long after our HTTP call is gone, so streaming straight from
 * the network is not an option. The copy lands in `cache/preview/handoff`,
 * which FileProvider exposes and [TempFiles.sweep] reclaims by age.
 */
@Composable
fun rememberPhotoSharer(
    repository: Repository,
    snackbar: SnackbarHostState
): (PhotoItem) -> Unit {
    val context = LocalContext.current
    return rememberPhotoFetcher(repository, snackbar, "分享失败") { photo, bytes ->
        val file = runCatching { TempFiles.writeHandoff(context, photo.name, bytes) }.getOrNull()
        if (file == null) {
            // Either way the user sees the same thing: the sheet never opened.
            snackbar.notify("没有可分享的应用", NoticeKind.Failure)
            return@rememberPhotoFetcher
        }
        if (!Sharing.share(context, file, FileKinds.mimeOf(photo.name), photo.name)) {
            snackbar.notify("没有可分享的应用", NoticeKind.Failure)
        }
    }
}

/**
 * Long-press action sheet for one album item. The 文件 tab reaches the same
 * actions from each row's ⋮ menu; tiles carry no chrome of their own, so a
 * held-down thumbnail is the only way in.
 *
 * Mount it unconditionally and drive it with a `var target by remember {
 * mutableStateOf<PhotoItem?>(null) }`; a null target composes nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoActionMenu(
    target: PhotoItem?,
    onDownload: (PhotoItem) -> Unit,
    onShare: (PhotoItem) -> Unit = {},
    onDismiss: () -> Unit
) {
    val photo = target ?: return
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            // A three-column grid makes it easy to lose track of which tile you
            // held down, so the sheet names the item before offering actions.
            ListItem(
                headlineContent = {
                    Text(photo.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Text(
                        "${photo.dirName}/${photo.relPath}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
            HorizontalDivider()
            // Each action closes the sheet FIRST: the work continues on the host
            // screen's scope, and the dialog/chooser should come up over the
            // album rather than over a sheet still animating away.
            ListItem(
                headlineContent = { Text("分享") },
                leadingContent = { Icon(Icons.Default.Share, contentDescription = null) },
                modifier = Modifier.clickable {
                    onDismiss()
                    onShare(photo)
                }
            )
            ListItem(
                headlineContent = { Text("下载到本机") },
                leadingContent = { Icon(Icons.Default.Download, contentDescription = null) },
                modifier = Modifier.clickable {
                    onDismiss()
                    onDownload(photo)
                }
            )
        }
    }
}
