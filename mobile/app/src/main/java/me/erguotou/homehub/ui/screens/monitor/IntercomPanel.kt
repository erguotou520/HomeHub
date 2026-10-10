package me.erguotou.homehub.ui.screens.monitor

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import me.erguotou.homehub.data.nvr.NvrApi

/**
 * 双向通话（对讲）占位面板。
 *
 * 服务器当前没有 talkback / audio_inject 通道（ISAPI 探测 404），设备侧
 * 音频注入能力尚未接入，所以面板只说明现状、保留入口——能力落地后把这里
 * 换成真正的麦克风采集 + 推流界面。
 */
@Composable
fun IntercomPanel(
    camera: NvrApi.Camera,
    onDismiss: () -> Unit,
    ptzStop: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("双向通话") },
        text = {
            Text(
                "「${camera.label ?: camera.name}」暂不支持对讲：服务器还没有接入该设备的音频注入（talkback）通道。功能预留中，敬请期待。"
            )
        },
        confirmButton = {
            Button(onClick = {
                ptzStop()
                onDismiss()
            }) { Text("知道了") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
