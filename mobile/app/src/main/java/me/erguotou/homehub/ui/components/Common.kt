package me.erguotou.homehub.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import me.erguotou.homehub.data.PhotoItem

@Composable
fun Loading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun Empty(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ErrorText(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.error)
    }
}

/** Square photo tile loading the 256px server thumbnail. */
@Composable
fun PhotoTile(
    photo: PhotoItem,
    urlResolver: (String) -> String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = urlResolver(photo.thumbUrl),
            contentDescription = photo.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Button that writes the given JSON config to a user-chosen file via the
 * Storage Access Framework (SAF). Used for WG/server config export.
 */
@Composable
fun ExportConfigButton(
    json: String,
    defaultFileName: String = "homehub-config.json"
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { target ->
            try {
                context.contentResolver.openOutputStream(target)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
            } catch (_: Exception) {
                // Best effort; the user can retry.
            }
        }
    }
    Button(onClick = { launcher.launch(defaultFileName) }) {
        Text("导出配置")
    }
}
