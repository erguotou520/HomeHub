package me.erguotou.homehub.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.erguotou.homehub.ui.components.SettingsSection
import me.erguotou.homehub.update.ApkInstaller
import me.erguotou.homehub.update.InstallStart
import me.erguotou.homehub.update.UpdateDelta
import me.erguotou.homehub.update.UpdateManager
import me.erguotou.homehub.update.UpdateManifest
import me.erguotou.homehub.update.UpdateResult
import me.erguotou.homehub.update.UpdateStage
import me.erguotou.homehub.util.formatBytes

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val manifest: UpdateManifest, val delta: UpdateDelta?) : UpdateUiState
    data class Working(
        val stage: UpdateStage,
        val done: Long,
        val total: Long,
        val incremental: Boolean,
    ) : UpdateUiState

    data class Failed(val message: String) : UpdateUiState
}

/**
 * 设置页的「应用更新」卡片：查清单、增量优先下载、交给系统安装器。
 *
 * 状态用 [MutableStateFlow] 而不是 `mutableStateOf`：下载进度回调跑在 IO 线程上，
 * 每秒会来几十次，从后台线程写 StateFlow 是安全的，写普通状态则有快照隐患。
 */
@Composable
fun UpdateSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember { UpdateManager(context) }
    val scope = rememberCoroutineScope()
    val state = remember { MutableStateFlow<UpdateUiState>(UpdateUiState.Idle) }
    val current by state.collectAsState()
    val installResult by ApkInstaller.result.collectAsState()
    // 正在等哪一次安装的结果；[ApkInstaller.NO_ATTEMPT] 表示没在等。理由见下面的用法。
    var awaiting by remember { mutableStateOf(ApkInstaller.NO_ATTEMPT) }

    LaunchedEffect(installResult, awaiting) {
        val outcome = installResult ?: return@LaunchedEffect
        if (!ApkInstaller.isApplicable(outcome, awaiting)) {
            // 迟到、或压根不属于这次尝试的结果：悄悄丢掉。
            //
            // 这是「已取消安装」幽灵消息的解药。换包会把进程杀掉，而安装结果广播
            // 随后才到 —— 系统于是**事后**冷启一个进程专门投递它（真机日志：
            // 13:43:32 换包 → 13:43:35 `Start proc ... for broadcast
            // {InstallResultReceiver}`）。那一刻没有任何界面在等结果，值就留在
            // StateFlow 里；用户几分钟后打开设置页，这张卡一组合就把它当成本次
            // 的结果弹给用户，而屏幕上「当前版本」明明已经是新的。
            // 只有「正在等」的那一次的结果才配显示，其余一律丢弃。
            ApkInstaller.clear()
            return@LaunchedEffect
        }
        awaiting = ApkInstaller.NO_ATTEMPT
        ApkInstaller.clear()
        Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
        // 走到这里，一次安装尝试已经结束（成功、被取消、或被系统拒绝），
        // 界面就不能再停在 Working 上：失败/取消时那会变成一块没有任何按钮的
        // 死页面（"校验安装包" 永远转圈），用户只能杀进程。
        // 成功时不显示额外状态 —— 覆盖安装随即替换进程，用户看到的本就是新版。
        state.value = if (outcome.success) {
            UpdateUiState.Idle
        } else {
            UpdateUiState.Failed(outcome.message)
        }
    }

    fun check() {
        state.value = UpdateUiState.Checking
        scope.launch {
            val manifest = manager.check()
            state.value = when {
                manifest == null -> UpdateUiState.Failed("无法获取更新信息，请检查网络")
                !manifest.isNewerThan(manager.currentVersionCode) -> UpdateUiState.UpToDate
                else -> {
                    // 只有版本号命中才去算本机 APK 的指纹（97 MB，要几秒）。
                    val delta = manager.deltasMatch(manifest)
                    UpdateUiState.Available(manifest, delta)
                }
            }
        }
    }

    fun download(manifest: UpdateManifest, delta: UpdateDelta?) {
        state.value = UpdateUiState.Working(
            stage = UpdateStage.DOWNLOADING,
            done = 0,
            total = delta?.size ?: (manifest.apk?.size ?: -1),
            incremental = delta != null,
        )
        scope.launch {
            val result = manager.prepare(
                manifest = manifest,
                onStage = { stage ->
                    val prev = state.value
                    if (prev is UpdateUiState.Working) state.value = prev.copy(stage = stage)
                },
                onProgress = { done, total ->
                    val prev = state.value
                    if (prev is UpdateUiState.Working) state.value = prev.copy(done = done, total = total)
                },
            )
            when (result) {
                is UpdateResult.Failed -> state.value = UpdateUiState.Failed(result.reason)
                is UpdateResult.Ready -> {
                    // 目标版本随会话一起交给系统，结果回来时用它和「已安装版本」对账。
                    when (val start = ApkInstaller.install(context, result.apk, manifest.versionCode)) {
                        is InstallStart.Refused -> state.value = UpdateUiState.Failed(start.reason)
                        is InstallStart.Submitted -> awaiting = start.attempt
                    }
                }
            }
        }
    }

    SettingsSection(
        title = "应用更新",
        icon = Icons.Outlined.SystemUpdate,
        modifier = modifier,
        description = "当前版本 ${manager.currentVersionName}（${manager.currentVersionCode}）"
    ) {
        when (val s = current) {
            UpdateUiState.Idle -> {
                Button(onClick = { check() }) { Text("检查更新") }
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
                    onClick = { check() },
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("重新检查") }
            }

            is UpdateUiState.Available -> {
                Text(
                    "发现新版本 ${s.manifest.versionName}",
                    style = MaterialTheme.typography.bodyLarge
                )
                val full = s.manifest.apk?.size ?: 0
                val hint = if (s.delta != null) {
                    "增量更新：只需下载 ${formatBytes(s.delta.size)}，省去 ${formatBytes((full - s.delta.size).coerceAtLeast(0))}"
                } else {
                    "需要下载完整安装包 ${formatBytes(full)}"
                }
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    Button(onClick = { download(s.manifest, s.delta) }) { Text("下载并安装") }
                    TextButton(onClick = { state.value = UpdateUiState.Idle }) { Text("稍后") }
                }
            }

            is UpdateUiState.Working -> {
                val label = when (s.stage) {
                    UpdateStage.DOWNLOADING ->
                        if (s.incremental) "下载增量包" else "下载完整安装包"

                    UpdateStage.PATCHING -> "正在合并增量包（请勿退出）"
                    UpdateStage.VERIFYING -> "校验安装包"
                }
                Text(
                    if (s.stage == UpdateStage.DOWNLOADING && s.total > 0) {
                        "$label ${formatBytes(s.done)} / ${formatBytes(s.total)}"
                    } else {
                        label
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (s.stage == UpdateStage.DOWNLOADING && s.total > 0) {
                    LinearProgressIndicator(
                        progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }

            is UpdateUiState.Failed -> {
                Text(
                    s.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(
                    onClick = { state.value = UpdateUiState.Idle },
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("重试") }
            }
        }
    }
}
