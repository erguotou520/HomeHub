package me.erguotou.homehub.ui.screens.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 监控 tab。
 *
 * 进 tab 直接就是预览页（[MonitorPreviewScreen]，实时 / 历史同页切换）：
 * **没有管理页，也没有登录页**。服务端把手机端要用的只读接口放在了数据面上
 * （不需要凭据），所以这里不需要任何密码；没有接入摄像头时显示空态。
 * 摄像头的新增、删除、录像保留策略都在 HomeHub 后台完成，手机端只负责看。
 */
@Composable
fun MonitorScreen(onFullscreenChange: (Boolean) -> Unit = {}) {
    val vm: MonitorViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // One-shot feedback from the view model.
    LaunchedEffect(ui.notice) {
        ui.notice?.let {
            snackbar.showSnackbar(it)
            vm.consumeNotice()
        }
    }

    // 切回 tab 时列表可能被系统回收过，补拉一次，避免看到空态
    LaunchedEffect(Unit) {
        if (ui.cameras.isEmpty()) vm.refresh()
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (!vm.isServerConfigured) {
            EmptyHint(
                "尚未配置服务器",
                "请先在「设置」中填写 HomeHub 服务器地址。"
            )
        } else {
            MonitorPreviewScreen(vm = vm, onFullscreenChange = onFullscreenChange)
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

// ─────────────────────────── 提示 ───────────────────────────

@Composable
private fun EmptyHint(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─────────────────────────── 格式化 ───────────────────────────

fun formatBytes(b: Long): String {
    if (b < 1024) return "${b} B"
    val kb = b / 1024.0
    if (kb < 1024) return "${formatNum(kb)} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${formatNum(mb)} MB"
    val gb = mb / 1024.0
    return "${formatNum(gb)} GB"
}

private fun formatNum(v: Double): String =
    if (v >= 100) v.toLong().toString() else String.format("%.1f", v)
