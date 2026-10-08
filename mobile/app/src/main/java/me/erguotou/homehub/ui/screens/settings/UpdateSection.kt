package me.erguotou.homehub.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.erguotou.homehub.ui.components.SettingsSection
import me.erguotou.homehub.update.UpdateStage
import me.erguotou.homehub.update.UpdateUiState
import me.erguotou.homehub.update.UpdateViewModel
import me.erguotou.homehub.util.formatBytes

/**
 * 设置页的「应用更新」卡片：查清单、增量优先下载、交给系统安装器。
 *
 * 状态全在 [UpdateViewModel] 里 —— 启动时那个「要不要升级」的弹框和这张卡看的是
 * 同一份状态，所以卡片自己不再持有任何东西。
 */
@Composable
fun UpdateSection(updates: UpdateViewModel, modifier: Modifier = Modifier) {
    val current by updates.state.collectAsStateWithLifecycle()

    SettingsSection(
        title = "应用更新",
        icon = Icons.Outlined.SystemUpdate,
        modifier = modifier,
        description = "当前版本 ${updates.currentVersionName}（${updates.currentVersionCode}）"
    ) {
        when (val s = current) {
            // 默认态刻意安静：只留一个手动检查的入口，不残留上一轮的任何提示。
            UpdateUiState.Idle -> {
                TextButton(onClick = { updates.check() }) { Text("检查更新") }
            }

            UpdateUiState.Checking -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在检查…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            UpdateUiState.UpToDate -> {
                Text(
                    "已是最新版本",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedButton(
                    onClick = { updates.check() },
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("重新检查") }
            }

            is UpdateUiState.Available -> {
                Text(
                    "检测到新版本 ${s.manifest.versionName}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    updateHint(s),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Button(
                    onClick = { updates.startUpdate() },
                    modifier = Modifier.padding(top = 10.dp)
                ) { Text("下载并安装") }
            }

            is UpdateUiState.Working -> WorkingBody(s)

            is UpdateUiState.Failed -> {
                Text(
                    s.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(
                    onClick = { updates.retry() },
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("重试") }
            }
        }
    }
}

/**
 * 启动后发现有新版本时问用户一句。
 *
 * 它和卡片共用同一份状态：在这里点「立即升级」，设置页那张卡也在同步走进度；
 * 反过来卡片上点的升级不会让弹框冒出来 —— 这个弹框只由**自动检查**触发。
 */
@Composable
fun UpdatePromptDialog(
    state: UpdateUiState,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        is UpdateUiState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("检测到新版本 ${state.manifest.versionName}") },
            text = { Text(updateHint(state)) },
            confirmButton = { TextButton(onClick = onStart) { Text("立即升级") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
        )

        // 下载与合并都在跑：进度留在弹框里，点「后台继续」也只是收起，不停下载。
        is UpdateUiState.Working -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("正在准备升级") },
            text = {
                Column {
                    Text(workingLabel(state), style = MaterialTheme.typography.bodyMedium)
                    if (state.stage == UpdateStage.DOWNLOADING && state.total > 0) {
                        LinearProgressIndicator(
                            progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("后台继续") } },
        )

        is UpdateUiState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("升级失败") },
            text = { Text("${state.message}\n\n稍后也可以在设置页重试，已经下载好的包不必再下一遍。") },
            confirmButton = { TextButton(onClick = onRetry) { Text("重试") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        )

        // 别的状态不该带着弹框出现（装好了、或用户已经处理完），什么都不画。
        else -> Unit
    }
}

/** 「增量更新：只需下载 812 KB，省去 98.6 MB」这类说明。 */
private fun updateHint(state: UpdateUiState.Available): String {
    val full = state.manifest.apk?.size ?: 0
    return if (state.delta != null) {
        "增量更新：只需下载 ${formatBytes(state.delta.size)}，" +
            "省去 ${formatBytes((full - state.delta.size).coerceAtLeast(0))}"
    } else {
        "需要下载完整安装包 ${formatBytes(full)}"
    }
}

/** 进度文案，卡片与弹框共用一份。 */
private fun workingLabel(s: UpdateUiState.Working): String {
    val label = when (s.stage) {
        UpdateStage.DOWNLOADING -> if (s.incremental) "下载增量包" else "下载完整安装包"
        UpdateStage.PATCHING -> "正在合并增量包（请勿退出）"
        UpdateStage.VERIFYING -> "校验安装包"
        UpdateStage.INSTALLING -> "等待系统确认安装"
    }
    return if (s.stage == UpdateStage.DOWNLOADING && s.total > 0) {
        "$label ${formatBytes(s.done)} / ${formatBytes(s.total)}"
    } else {
        label
    }
}

@Composable
private fun WorkingBody(s: UpdateUiState.Working) {
    Text(workingLabel(s), style = MaterialTheme.typography.bodyMedium)
    if (s.stage == UpdateStage.DOWNLOADING && s.total > 0) {
        LinearProgressIndicator(
            progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}
