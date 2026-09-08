package me.erguotou.homehub.ui.screens.monitor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch
import me.erguotou.homehub.data.Repository
import me.erguotou.homehub.ui.components.SettingsSection

/**
 * Monitoring placeholder (PRD v1): keeps the tab and the configuration, but
 * does not play anything yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen() {
    val context = LocalContext.current
    val repository = remember { Repository(context) }
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        repository.monitorConfig().onSuccess {
            url = it.frigateUrl.orEmpty()
            note = it.note
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("监控") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.size(92.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Videocam, contentDescription = null, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("监控模块建设中", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                note ?: "本期仅保留入口与配置骨架，后续对接 Frigate 或自研录像。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            SettingsSection(
                title = "Frigate",
                icon = Icons.Outlined.Videocam,
                description = "保存后在后续版本中用于拉取摄像头列表与事件回放，当前不会发起连接。"
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Frigate 地址") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("http://10.0.0.5:5000") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrect = false,
                        imeAction = ImeAction.Done
                    )
                )
                Button(
                    onClick = {
                        scope.launch {
                            repository.saveMonitorConfig(url).onSuccess { saved = true }
                        }
                    },
                    modifier = Modifier.padding(top = 12.dp)
                ) { Text(if (saved) "已保存（暂不连接）" else "保存（暂不连接）") }
            }
        }
    }
}
