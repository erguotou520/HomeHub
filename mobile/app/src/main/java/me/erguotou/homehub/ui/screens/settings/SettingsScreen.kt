package me.erguotou.homehub.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.erguotou.homehub.data.Prefs
import me.erguotou.homehub.data.Repository
import me.erguotou.homehub.security.AppLock
import me.erguotou.homehub.ui.components.ExportConfigButton
import me.erguotou.homehub.ui.components.SettingsSection
import me.erguotou.homehub.update.UpdateViewModel
import me.erguotou.homehub.wireguard.TunnelManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenSetup: () -> Unit, updates: UpdateViewModel) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val repository = remember { Repository(context) }

    val tunnelState by TunnelManager.state.collectAsState()
    val lastError by TunnelManager.lastError.collectAsState()

    var serverAddress by remember { mutableStateOf(prefs.serverAddress) }
    var serverPort by remember { mutableStateOf(prefs.serverPort.toString()) }
    var useHttps by remember { mutableStateOf(prefs.useHttps) }
    var certPem by remember { mutableStateOf(prefs.serverCertPem) }
    var trustCustom by remember { mutableStateOf(prefs.trustCustomCert) }

    var biometric by remember { mutableStateOf(prefs.biometricLock) }
    var deleteAfterUpload by remember { mutableStateOf(prefs.deleteAfterUpload) }
    var duplicatePolicy by remember { mutableStateOf(prefs.duplicatePolicy) }
    var status by remember { mutableStateOf<String?>(null) }

    val canAuthenticate = remember { AppLock.canAuthenticate(context) }
    val authReason = remember { AppLock.unavailableReason(context) }

    // 改动即生效：每个字段直接写入 Prefs（不再需要「保存」），连接相关字段
    // 变更后重建 HTTP 客户端，并做一次防抖的连通性探测，结果直接显示在下面。
    LaunchedEffect(serverAddress, serverPort, useHttps, certPem, trustCustom) {
        prefs.serverAddress = serverAddress
        serverPort.toIntOrNull()?.let { prefs.serverPort = it }
        prefs.useHttps = useHttps
        prefs.serverCertPem = certPem
        prefs.trustCustomCert = trustCustom
        repository.invalidate()

        if (serverAddress.isBlank()) {
            status = null
            return@LaunchedEffect
        }
        delay(400)
        status = "连接中…"
        status = repository.health().fold(
            onSuccess = { "已连接：v${it.version}" },
            onFailure = { e -> "连接失败：${e.message}" }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsSection(
                title = "WireGuard 隧道",
                icon = Icons.Outlined.VpnKey,
                description = "状态：${tunnelState.name}" + (lastError?.let { " · $it" } ?: "")
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { TunnelManager.connect(context, prefs) }) { Text("连接") }
                    OutlinedButton(onClick = { TunnelManager.disconnect(context) }) { Text("断开") }
                    TextButton(onClick = onOpenSetup) { Text("编辑配置") }
                }
            }

            UpdateSection(updates)

            SettingsSection(
                title = "服务器",
                icon = Icons.Outlined.Dns,
                description = "改动即生效，无需保存。"
            ) {
                OutlinedTextField(
                    value = serverAddress,
                    onValueChange = { serverAddress = it },
                    label = { Text("地址") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = serverPort,
                    onValueChange = { serverPort = it.filter(Char::isDigit) },
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                ToggleRow("使用 HTTPS", useHttps) { useHttps = it }

                // 只有走 HTTPS 时证书固定才有意义；裸 HTTP（WireGuard 隧道内）
                // 场景下把它藏起来，避免误导。
                if (useHttps) {
                    Text(
                        "自签名证书（PEM，可选）。填入后勾选下方开关，即只信任这张证书。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = certPem,
                        onValueChange = { certPem = it },
                        label = { Text("服务端证书") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        minLines = 3
                    )
                    ToggleRow("仅信任上述证书", trustCustom, enabled = certPem.isNotBlank()) {
                        trustCustom = it
                    }
                }

                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            SettingsSection(
                title = "上传",
                icon = Icons.Outlined.Upload,
                description = "上传确认框的默认选项，也可以在那里临时改动。"
            ) {
                ToggleRow("上传后删除本地副本", deleteAfterUpload) { on ->
                    deleteAfterUpload = on
                    prefs.deleteAfterUpload = on
                }
                Text(
                    "开启后，上传确认框里的「上传完成后删除本地副本」会默认勾选。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DuplicatePolicyDropdown(duplicatePolicy) { value ->
                    duplicatePolicy = value
                    prefs.duplicatePolicy = value
                }
            }

            SettingsSection(
                title = "安全",
                icon = Icons.Outlined.Shield,
                description = "开启后，进入应用、或离开超过 10 秒再回来时，" +
                    "需要验证指纹 / 面容 / 锁屏密码。"
            ) {
                ToggleRow(
                    label = "生物识别门禁",
                    checked = biometric,
                    enabled = canAuthenticate
                ) { on ->
                    biometric = on
                    prefs.biometricLock = on
                    // 立即生效：开启后马上弹出一次验证，让用户确认可用，
                    // 之后 MainActivity 会立刻切到锁定页。
                    if (on) AppLock.lock() else AppLock.unlock()
                }
                if (canAuthenticate) {
                    Text(
                        if (biometric) "已开启。关闭开关即可取消验证。" else "当前已关闭。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    Text(
                        authReason ?: "当前设备无法使用生物识别或锁屏密码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            SettingsSection(
                title = "配置",
                icon = Icons.Outlined.IosShare,
                description = "将服务器与 WireGuard 配置导出为 JSON（不含私钥），便于迁移到新设备。"
            ) {
                ExportConfigButton(prefs.exportConfig())
            }
        }
    }
}

/**
 * 「上传重复时」 — one row, dropdown, defaults to 跳过.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DuplicatePolicyDropdown(value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (value == "keep") "保留副本" else "跳过"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("上传重复时") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth().padding(top = 8.dp)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("跳过") },
                onClick = {
                    onChange("skip")
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("保留副本") },
                onClick = {
                    onChange("keep")
                    expanded = false
                }
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
