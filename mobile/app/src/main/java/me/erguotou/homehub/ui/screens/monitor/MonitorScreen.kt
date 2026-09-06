package me.erguotou.homehub.ui.screens.monitor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.erguotou.homehub.data.Repository

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
        Box(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "监控模块建设中",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    note ?: "本期仅保留入口与配置骨架，后续对接 Frigate 或自研录像。",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Frigate 地址") },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    singleLine = true,
                    placeholder = { Text("http://10.0.0.5:5000") }
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
