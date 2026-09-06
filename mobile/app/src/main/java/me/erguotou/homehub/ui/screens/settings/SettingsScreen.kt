package me.erguotou.homehub.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch
import me.erguotou.homehub.data.Prefs
import me.erguotou.homehub.data.Repository
import me.erguotou.homehub.ui.components.ExportConfigButton
import me.erguotou.homehub.wireguard.TunnelManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenSetup: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val repository = remember { Repository(context) }
    val scope = rememberCoroutineScope()

    val tunnelState by TunnelManager.state.collectAsState()
    val lastError by TunnelManager.lastError.collectAsState()

    var serverAddress by remember { mutableStateOf(prefs.serverAddress) }
    var serverPort by remember { mutableStateOf(prefs.serverPort.toString()) }
    var useHttps by remember { mutableStateOf(prefs.useHttps) }
    var biometric by remember { mutableStateOf(prefs.biometricLock) }
    var deleteAfterUpload by remember { mutableStateOf(prefs.deleteAfterUpload) }
    var amapKey by remember { mutableStateOf(prefs.amapKey) }
    var status by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("WireGuard 隧道", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "状态：${tunnelState.name}" + (lastError?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { TunnelManager.connect(context, prefs) }) { Text("连接") }
                        TextButton(onClick = { TunnelManager.disconnect(context) }) { Text("断开") }
                        TextButton(onClick = onOpenSetup) { Text("编辑配置") }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("服务器", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = serverAddress,
                        onValueChange = { serverAddress = it },
                        label = { Text("地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = serverPort,
                        onValueChange = { serverPort = it },
                        label = { Text("端口") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ToggleRow("使用 HTTPS", useHttps) { useHttps = it }
                    ToggleRow("生物识别门禁", biometric) { biometric = it }
                    ToggleRow("上传后删除本地副本", deleteAfterUpload) { deleteAfterUpload = it }
                    Button(onClick = {
                        prefs.serverAddress = serverAddress
                        prefs.serverPort = serverPort.toIntOrNull() ?: 8485
                        prefs.useHttps = useHttps
                        prefs.biometricLock = biometric
                        prefs.deleteAfterUpload = deleteAfterUpload
                        repository.invalidate()
                        status = null
                        scope.launch {
                            status = repository.health().fold(
                                onSuccess = { "已连接：v${it.version}" },
                                onFailure = { e -> "连接失败：${e.message}" }
                            )
                        }
                    }) { Text("保存并测试") }
                    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("地图（高德）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "相册「地点」视图使用高德地图 Android SDK 渲染服务端聚合的 GPS 数据。" +
                            "Key 保存在加密存储中，运行时通过 MapsInitializer 注入，不写入 APK。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = amapKey,
                        onValueChange = { amapKey = it },
                        label = { Text("高德 Android SDK Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = {
                        prefs.amapKey = amapKey
                        status = "高德 Key 已保存"
                    }) { Text("保存 Key") }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("安全", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "凭据（含 WireGuard 私钥）保存在 EncryptedSharedPreferences 中，" +
                            "密钥由 Android Keystore 管理。服务端证书可在下方登记，" +
                            "仅信任该证书——不再全局信任所有证书。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = prefs.serverCertPem,
                        onValueChange = { prefs.serverCertPem = it },
                        label = { Text("服务端证书（PEM，可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                    ToggleRow("仅信任上述证书", prefs.trustCustomCert) {
                        prefs.trustCustomCert = it
                        repository.invalidate()
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("配置", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "将服务器与 WireGuard 配置导出为 JSON（不含私钥），便于迁移到新设备。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    ExportConfigButton(prefs.exportConfig())
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

