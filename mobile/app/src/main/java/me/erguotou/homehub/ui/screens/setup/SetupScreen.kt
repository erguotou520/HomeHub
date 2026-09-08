package me.erguotou.homehub.ui.screens.setup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import me.erguotou.homehub.ui.components.ExportConfigButton

/**
 * First-run wizard: generate/import the WireGuard identity, enter the server
 * address and finally probe the connection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(vm: SetupViewModel = viewModel(), onFinished: () -> Unit) {
    val state = vm.state
    var importUri by remember { mutableStateOf<Uri?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> importUri = uri }

    Scaffold(
        topBar = { TopAppBar(title = { Text("连接 HomeHub") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StepHeader(state.step)

            when (state.step) {
                0 -> WireGuardStep(vm)
                1 -> ServerStep(vm)
                else -> TestStep(vm, onFinished)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { importLauncher.launch("application/json") }) { Text("导入配置") }
                Row {
                    if (state.step > 0) {
                        TextButton(onClick = { vm.update { copy(step = step - 1) } }) { Text("上一步") }
                    }
                    Button(onClick = {
                        if (state.step < 2) {
                            // Persist after every step so a crash/kill never
                            // loses what the user already typed.
                            vm.save()
                            vm.update { copy(step = step + 1) }
                        } else {
                            vm.testConnection()
                        }
                    }) {
                        Text(if (state.step < 2) "下一步" else "测试连接")
                    }
                }
            }

            ExportConfigButton(vm.export())

            if (state.saved && state.step == 2) {
                Button(onClick = onFinished, modifier = Modifier.fillMaxWidth()) {
                    Text("进入 HomeHub")
                }
            }
        }
    }

    importUri?.let { uri ->
        ImportDialog(uri) { json ->
            importUri = null
            if (json != null) vm.import(json)
        }
    }
}

@Composable
private fun StepHeader(step: Int) {
    val labels = listOf("WireGuard 身份", "服务器地址", "连接测试")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            Text(
                "${index + 1}. $label",
                color = if (index == step) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun WireGuardStep(vm: SetupViewModel) {
    val state = vm.state
    Field("私钥", state.privateKey, { vm.update { copy(privateKey = it) } }, password = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { vm.generateKeys() }) { Text("生成密钥对") }
        TextButton(onClick = {
            val pub = vm.publicKeyOf(state.privateKey)
            if (pub != null) vm.update { copy(publicKey = pub) }
        }) { Text("由私钥推导公钥") }
    }
    Field("公钥（发给服务端登记）", state.publicKey, { vm.update { copy(publicKey = it) } })
    Field("隧道地址", state.address, { vm.update { copy(address = it) } })
    Field("DNS（可选）", state.dns, { vm.update { copy(dns = it) } })
    Field("对端公钥", state.peerPublicKey, { vm.update { copy(peerPublicKey = it) } })
    Field("Allowed IPs", state.allowedIps, { vm.update { copy(allowedIps = it) } })
    Field("Endpoint（host:port）", state.endpoint, { vm.update { copy(endpoint = it) } })
    Field("Persistent Keepalive", state.keepalive, { vm.update { copy(keepalive = it) } })
}

@Composable
private fun ServerStep(vm: SetupViewModel) {
    val state = vm.state
    Field("服务器地址（WG 内网 IP 或域名）", state.serverAddress, {
        vm.update { copy(serverAddress = it) }
    })
    Field("端口", state.serverPort, { vm.update { copy(serverPort = it) } })
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = state.useHttps, onCheckedChange = { vm.update { copy(useHttps = it) } })
        Text("使用 HTTPS（自签证书请在设置里登记证书）")
    }
}

@Composable
private fun TestStep(vm: SetupViewModel, onFinished: () -> Unit) {
    val state = vm.state
    Text(
        "保存配置后点击下方按钮测试连通性。若失败，请确认：\n" +
            "1. WireGuard 隧道已连接\n" +
            "2. 服务端已启动并监听 8485\n" +
            "3. 隧道 IP 已在服务端登记或可被自动识别",
        style = MaterialTheme.typography.bodyMedium
    )
    if (state.testing) Text("测试中…", color = MaterialTheme.colorScheme.primary)
    state.testResult?.let {
        Text(
            it,
            color = if (it.startsWith("已连接")) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    password: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None
    )
}

@Composable
private fun ImportDialog(uri: Uri, onDone: (String?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(uri) {
        text = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
        } catch (e: Exception) {
            null
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("导入配置") },
        text = { Text(text ?: "无法读取该文件") },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("导入") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("取消") } }
    )
}
