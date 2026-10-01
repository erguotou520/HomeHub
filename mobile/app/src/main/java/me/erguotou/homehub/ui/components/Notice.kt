package me.erguotou.homehub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Whether a receipt reports something that worked or something that did not. */
enum class NoticeKind { Success, Failure }

/**
 * A one-line receipt.
 *
 * Deliberately not Material's default snackbar: those are full-width slabs with
 * a tall empty action slot, which is heavy for what is usually four words
 * ("已保存到 Download"). This renders as a centred pill with an icon instead.
 */
data class Notice(
    override val message: String,
    val kind: NoticeKind = NoticeKind.Success
) : SnackbarVisuals {
    override val actionLabel: String? get() = null
    override val withDismissAction: Boolean get() = false
    override val duration: SnackbarDuration get() = SnackbarDuration.Short
}

/** Post a [Notice]-styled receipt. */
suspend fun SnackbarHostState.notify(message: String, kind: NoticeKind = NoticeKind.Success) {
    showSnackbar(Notice(message, kind))
}

/**
 * Renders [Notice]s as compact pills. Drop-in replacement for `SnackbarHost`
 * at every site that posts to a [SnackbarHostState] whose messages come from
 * this package.
 *
 * Plain-string messages (e.g. the file page's 复制/移动 status lines) still
 * render, just without an icon — they carry no success/failure flag to trust.
 */
@Composable
fun NoticeHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.widthIn(max = 340.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    (data.visuals as? Notice)?.let { notice ->
                        NoticeBadge(notice.kind)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        data.visuals.message,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Small filled circle holding the tick/cross. Both pairs are theme-aware, so
 * the badge keeps its contrast whichever way `inverseSurface` flips.
 */
@Composable
private fun NoticeBadge(kind: NoticeKind) {
    val scheme = MaterialTheme.colorScheme
    val success = kind == NoticeKind.Success
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (success) scheme.inversePrimary else scheme.errorContainer)
    ) {
        Icon(
            imageVector = if (success) Icons.Rounded.Check else Icons.Rounded.Close,
            contentDescription = null,
            tint = if (success) scheme.inverseSurface else scheme.onErrorContainer,
            modifier = Modifier.size(14.dp)
        )
    }
}
