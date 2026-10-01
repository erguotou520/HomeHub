package me.erguotou.homehub.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
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

/**
 * The album's 「下载到本机」 plumbing, shared by the 文件 list and the 相册 action
 * sheet so both save through exactly one code path.
 *
 * Returns a `(fileName, bytes) -> Unit` that parks the bytes until the system's
 * "save as" dialog comes back, then writes them to whatever the user picked.
 * The payload has to go through a state slot because the launcher's result
 * callback runs outside composition.
 */
@Composable
fun rememberLocalSaver(): (String, ByteArray) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        // Read and clear first: the callback also fires with a null uri when
        // the user backs out of the picker, and the bytes must not be kept.
        val payload = pending
        pending = null
        if (uri != null && payload != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload.second) }
            }
        }
    }
    return { name, bytes ->
        pending = name to bytes
        launcher.launch(name)
    }
}

/**
 * 「下载到本机」 for one album item: fetch the original through [repository] and
 * hand it to the system "save as" dialog, reporting a failure through
 * [snackbar]. Returns a fire-and-forget `(photo) -> Unit`, which is what the
 * grid's action sheet and the full-screen viewer's toolbar both call.
 *
 * The fetch runs in the CALLER's composition scope on purpose. A scope owned by
 * the action sheet would be cancelled the moment the sheet is dismissed — i.e.
 * before the bytes arrive — and the SAF dialog would never open.
 */
@Composable
fun rememberPhotoDownloader(
    repository: Repository,
    snackbar: SnackbarHostState
): (PhotoItem) -> Unit {
    val scope = rememberCoroutineScope()
    val saveLocal = rememberLocalSaver()
    return { photo ->
        scope.launch {
            repository.download(photo.dirName, photo.relPath).fold(
                onSuccess = { saveLocal(photo.name, it) },
                onFailure = { snackbar.showSnackbar("下载失败，请检查连接") }
            )
        }
    }
}

/**
 * Long-press action sheet for one album item. The 文件 tab reaches the same
 * action from each row's ⋮ menu; tiles carry no chrome of their own, so a
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
            ListItem(
                headlineContent = { Text("下载到本机") },
                leadingContent = { Icon(Icons.Default.Download, contentDescription = null) },
                modifier = Modifier.clickable {
                    // Close first: the download continues on the host screen's
                    // scope, and the "save as" dialog should come up over the
                    // album rather than over a sheet still animating away.
                    onDismiss()
                    onDownload(photo)
                }
            )
        }
    }
}
