package me.erguotou.nvr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.app.Activity
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import me.erguotou.nvr.ui.theme.MynvrappTheme
import me.erguotou.nvr.wireguard.KeyPair

class MainActivity : AppCompatActivity() {

    private val isAuthenticated = mutableStateOf(false)
    private val authError = mutableStateOf(false)
    private val prefs by lazy { PreferencesManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        WebView.setWebContentsDebuggingEnabled(true)

        setContent {
            MynvrappTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when {
                        isAuthenticated.value -> AppContent(prefs)
                        authError.value -> AuthErrorScreen { startBiometricAuth() }
                        else -> LoadingScreen("正在验证身份...")
                    }
                }
            }
        }

        if (canUseBiometric()) {
            startBiometricAuth()
        } else {
            isAuthenticated.value = true
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    private fun hideSystemBars() {
        window.insetsController?.apply {
            hide(WindowInsets.Type.navigationBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun canUseBiometric(): Boolean {
        val manager = BiometricManager.from(this)
        return manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
                    or BiometricManager.Authenticators.BIOMETRIC_WEAK
                    or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun startBiometricAuth() {
        authError.value = false
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isAuthenticated.value = true
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED) return
                    authError.value = true
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("身份验证")
            .setSubtitle("请验证身份以访问监控")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
                        or BiometricManager.Authenticators.BIOMETRIC_WEAK
                        or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }
}

private enum class Screen {
    WireGuardSetup,
    FrigateSetup,
    Summary,
    Camera
}

private val sslBypassContext: SSLContext by lazy {
    val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    })
    SSLContext.getInstance("TLS").apply { init(null, trustAll, java.security.SecureRandom()) }
}

private const val TAG = "NvrApp"

/**
 * Login to Frigate and return the session cookie string (name=value part only).
 * Does NOT use CookieManager - returns the cookie for WebView JS injection.
 */
private fun doFrigateLogin(baseUrl: String, username: String, password: String): String? {
    try {
        val loginUrl = baseUrl.trimEnd('/') + "/api/login"
        Log.d(TAG, "login: POST $loginUrl user=$username")
        val conn = URL(loginUrl).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = sslBypassContext.socketFactory
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("X-CSRF-TOKEN", "1")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true

        val json = """{"user":"${username.replace("\\", "\\\\").replace("\"", "\\\"")}","password":"${password.replace("\\", "\\\\").replace("\"", "\\\"")}"}"""
        conn.outputStream.use { it.write(json.toByteArray()) }

        val code = conn.responseCode
        val cookies = conn.headerFields["Set-Cookie"]
        Log.d(TAG, "login: response code=$code cookies=$cookies")
        conn.disconnect()

        if (code in 200..299 && cookies != null) {
            // Extract just the name=value part (before the first semicolon)
            return cookies.joinToString("; ") { it.substringBefore(";") }
        }
        return null
    } catch (e: Exception) {
        Log.e(TAG, "login failed: ${e.message}")
        return null
    }
}

@Composable
private fun AppContent(prefs: PreferencesManager) {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf(
        if (prefs.isConfigured()) Screen.Summary else Screen.WireGuardSetup
    ) }
    var testUrl by remember { mutableStateOf("") }
    var testState by remember { mutableStateOf<String?>(null) } // null, "loading", "success", "error"
    var testHttpCode by remember { mutableStateOf(0) }
    var tunnelError by remember { mutableStateOf<String?>(null) }
    var pendingVpnAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var importResult by remember { mutableStateOf<String?>(null) }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingVpnAction?.invoke()
        } else {
            tunnelError = "VPN 权限被拒绝"
            pendingVpnAction = null
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val json = prefs.toJson()
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            importResult = "配置导出成功"
        } catch (e: Exception) {
            importResult = "导出失败：${e.message}"
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val json = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (prefs.fromJson(json)) {
                importResult = "配置导入成功"
            } else {
                importResult = "导入失败：文件格式不正确"
            }
        } catch (e: Exception) {
            importResult = "导入失败：${e.message}"
        }
    }

    if (importResult != null) {
        AlertDialog(
            onDismissRequest = { importResult = null },
            title = { Text(if (importResult!!.contains("成功")) "成功" else "失败") },
            text = { Text(importResult!!) },
            confirmButton = { TextButton(onClick = { importResult = null }) { Text("确定") } }
        )
    }

    fun requestVpnAndConnect(onGranted: () -> Unit) {
        tunnelError = null
        val intent = WireGuardTunnelManager.getVpnIntent(context)
        if (intent != null) {
            pendingVpnAction = onGranted
            vpnLauncher.launch(intent)
        } else {
            onGranted()
        }
    }

    fun connectTunnelAndThen(onSuccess: () -> Unit) {
        tunnelError = null
        if (WireGuardTunnelManager.isConnected) {
            onSuccess()
            return
        }
        requestVpnAndConnect {
            WireGuardTunnelManager.connect(context, prefs) { success, error ->
                Handler(Looper.getMainLooper()).post {
                    if (success) {
                        onSuccess()
                    } else {
                        tunnelError = error ?: "连接失败"
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (currentScreen) {
            Screen.WireGuardSetup -> WireGuardSetupScreen(
                prefs = prefs,
                onNext = { currentScreen = Screen.FrigateSetup },
                onConnectAndTest = { url ->
                    connectTunnelAndThen { testUrl = url }
                },
                onImportSuccess = {
                    if (prefs.isConfigured()) currentScreen = Screen.Summary
                }
            )
            Screen.FrigateSetup -> FrigateSetupScreen(
                prefs = prefs,
                onSaved = { currentScreen = Screen.Summary }
            )
            Screen.Summary -> SummaryScreen(
                prefs = prefs,
                tunnelError = tunnelError,
                onClearError = { tunnelError = null },
                onTest = { url ->
                    connectTunnelAndThen {
                        testState = "loading"
                        testHttpCode = 0
                        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                            try {
                                val connection = URL(url).openConnection() as HttpURLConnection
                                if (connection is HttpsURLConnection) {
                                    connection.sslSocketFactory = sslBypassContext.socketFactory
                                    connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
                                }
                                connection.requestMethod = "GET"
                                connection.connectTimeout = 10000
                                connection.readTimeout = 10000
                                connection.instanceFollowRedirects = true
                                testHttpCode = connection.responseCode
                                connection.disconnect()
                                testState = if (testHttpCode in 200..399) "success" else "error"
                            } catch (e: Exception) {
                                testState = "error"
                            }
                        }
                    }
                },
                testState = testState,
                testHttpCode = testHttpCode,
                onClearTestState = { testState = null },
                onEnter = { connectTunnelAndThen { currentScreen = Screen.Camera } },
                onConnectTunnel = {
                    connectTunnelAndThen { }
                },
                onDisconnectTunnel = {
                    WireGuardTunnelManager.disconnect(context)
                },
                onEditWireGuard = { currentScreen = Screen.WireGuardSetup },
                onEditFrigate = { currentScreen = Screen.FrigateSetup },
                onExportConfig = {
                    exportLauncher.launch("camera_config_backup.json")
                },
                onImportConfig = {
                    importLauncher.launch("*/*")
                },
                onReset = {
                    WireGuardTunnelManager.disconnect(context)
                    prefs.clear()
                    currentScreen = Screen.WireGuardSetup
                }
            )
            Screen.Camera -> CameraScreen(
                prefs = prefs,
                onBack = { currentScreen = Screen.Summary }
            )
        }

        if (testUrl.isNotEmpty()) {
            testUrl = ""
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WireGuardSetupScreen(
    prefs: PreferencesManager,
    onNext: () -> Unit,
    onConnectAndTest: (String) -> Unit,
    onImportSuccess: () -> Unit
) {
    val context = LocalContext.current
    var importStatus by remember { mutableStateOf<String?>(null) }

    val setupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val json = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (prefs.fromJson(json)) {
                importStatus = "配置导入成功"
                onImportSuccess()
            } else {
                importStatus = "导入失败：文件格式不正确"
            }
        } catch (e: Exception) {
            importStatus = "导入失败：${e.message}"
        }
    }
    var privateKey by remember { mutableStateOf(prefs.getWireGuardPrivateKey()) }
    var publicKey by remember { mutableStateOf(prefs.getWireGuardPublicKey()) }
    var address by remember { mutableStateOf(prefs.getWireGuardAddress()) }
    var dns by remember { mutableStateOf(prefs.getWireGuardDns()) }
    var peerPublicKey by remember { mutableStateOf(prefs.getWireGuardPeerPublicKey()) }
    var allowedIPs by remember { mutableStateOf(prefs.getWireGuardAllowedIPs()) }
    var endpoint by remember { mutableStateOf(prefs.getWireGuardEndpoint()) }
    var persistentKeepalive by remember { mutableStateOf(prefs.getWireGuardPersistentKeepalive()) }
    var showPrivate by remember { mutableStateOf(false) }

    if (importStatus != null) {
        AlertDialog(
            onDismissRequest = { importStatus = null },
            title = { Text(if (importStatus!!.contains("成功")) "成功" else "失败") },
            text = { Text(importStatus!!) },
            confirmButton = { TextButton(onClick = { importStatus = null }) { Text("确定") } }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("WireGuard 配置") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Import config shortcut
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "已有配置？直接导入",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "导入 JSON 配置文件跳过手动填写",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                    FilledTonalButton(onClick = { setupImportLauncher.launch("*/*") }) {
                        Text("导入配置")
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text("手动配置", style = MaterialTheme.typography.titleMedium)

            Text("Interface", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Address *") },
                placeholder = { Text("10.0.0.3/24") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = dns,
                onValueChange = { dns = it },
                label = { Text("DNS") },
                placeholder = { Text("8.8.8.8") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Peer", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = peerPublicKey,
                onValueChange = { peerPublicKey = it },
                label = { Text("服务器公钥 (Peer PublicKey) *") },
                placeholder = { Text("base64 encoded public key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = allowedIPs,
                onValueChange = { allowedIPs = it },
                label = { Text("AllowedIPs *") },
                placeholder = { Text("0.0.0.0/0, ::/0") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text("Endpoint *") },
                placeholder = { Text("vpn.example.com:51820") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = persistentKeepalive,
                onValueChange = { persistentKeepalive = it },
                label = { Text("PersistentKeepalive") },
                placeholder = { Text("25") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("密钥对", style = MaterialTheme.typography.titleMedium)

            if (privateKey.isEmpty()) {
                Button(
                    onClick = {
                        val kp = KeyPair()
                        privateKey = kp.privateKey.toBase64()
                        publicKey = kp.publicKey.toBase64()
                        prefs.saveWireGuardConfig(
                            privateKey = privateKey,
                            publicKey = publicKey,
                            address = address,
                            dns = dns,
                            peerPublicKey = peerPublicKey,
                            allowedIPs = allowedIPs,
                            endpoint = endpoint,
                            persistentKeepalive = persistentKeepalive
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("生成密钥对")
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "私钥",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { showPrivate = !showPrivate }) {
                                Icon(
                                    imageVector = if (showPrivate) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle"
                                )
                            }
                            IconButton(onClick = {
                                copyToClipboard(context, "WireGuard PrivateKey", privateKey)
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                            }
                        }
                        Text(
                            if (showPrivate) privateKey else "${privateKey.take(8)}************************=",
                            style = MaterialTheme.typography.bodySmall
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "公钥 (可复制给服务器管理员)",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                copyToClipboard(context, "WireGuard PublicKey", publicKey)
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                            }
                        }
                        SelectionContainer {
                            Text(
                                publicKey,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        val kp = KeyPair()
                        privateKey = kp.privateKey.toBase64()
                        publicKey = kp.publicKey.toBase64()
                        prefs.saveWireGuardConfig(
                            privateKey = privateKey,
                            publicKey = publicKey,
                            address = address,
                            dns = dns,
                            peerPublicKey = peerPublicKey,
                            allowedIPs = allowedIPs,
                            endpoint = endpoint,
                            persistentKeepalive = persistentKeepalive
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text("重新生成")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            OutlinedButton(
                onClick = {
                    prefs.saveWireGuardConfig(
                        privateKey = privateKey,
                        publicKey = publicKey,
                        address = address,
                        dns = dns,
                        peerPublicKey = peerPublicKey,
                        allowedIPs = allowedIPs,
                        endpoint = endpoint,
                        persistentKeepalive = persistentKeepalive
                    )
                    val url = if (prefs.getFrigateUrl().isNotEmpty()) prefs.getFrigateUrl() else "https://192.168.8.7:8971/"
                    onConnectAndTest(url)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = address.isNotBlank() && endpoint.isNotBlank() && privateKey.isNotBlank() && peerPublicKey.isNotBlank()
            ) {
                Text("测试连接 (建立隧道并访问 Frigate)")
            }

            Spacer(modifier = Modifier.height(8.dp))

            ElevatedButton(
                onClick = {
                    prefs.saveWireGuardConfig(
                        privateKey = privateKey,
                        publicKey = publicKey,
                        address = address,
                        dns = dns,
                        peerPublicKey = peerPublicKey,
                        allowedIPs = allowedIPs,
                        endpoint = endpoint,
                        persistentKeepalive = persistentKeepalive
                    )
                    onNext()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = address.isNotBlank() && endpoint.isNotBlank() && privateKey.isNotBlank() && peerPublicKey.isNotBlank()
            ) {
                Text("下一步：配置 Frigate")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrigateSetupScreen(
    prefs: PreferencesManager,
    onSaved: () -> Unit
) {
    var url by remember { mutableStateOf(prefs.getFrigateUrl().ifEmpty { "https://192.168.8.7:8971/" }) }
    var username by remember { mutableStateOf(prefs.getFrigateUsername()) }
    var password by remember { mutableStateOf(prefs.getFrigatePassword()) }
    var showPassword by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Frigate 配置") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Frigate 地址 *") },
                placeholder = { Text("https://192.168.8.7:8971/") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle"
                        )
                    }
                }
            )

            Text(
                "账号密码将保存在本地，用于自动登录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(16.dp))

            ElevatedButton(
                onClick = {
                    prefs.saveFrigateConfig(url = url, username = username, password = password)
                    onSaved()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = url.isNotBlank()
            ) {
                Text("保存配置")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummaryScreen(
    prefs: PreferencesManager,
    tunnelError: String?,
    onClearError: () -> Unit,
    onTest: (String) -> Unit,
    testState: String?,
    testHttpCode: Int,
    onClearTestState: () -> Unit,
    onEnter: () -> Unit,
    onConnectTunnel: () -> Unit,
    onDisconnectTunnel: () -> Unit,
    onEditWireGuard: () -> Unit,
    onEditFrigate: () -> Unit,
    onExportConfig: () -> Unit,
    onImportConfig: () -> Unit,
    onReset: () -> Unit
) {
    val url = prefs.getFrigateUrl()
    val tunnelConnected = WireGuardTunnelManager.isConnected
    val connecting = WireGuardTunnelManager.isConnecting.value
    var resetCount by remember { mutableStateOf(0) }
    var showResetDialog by remember { mutableStateOf(false) }

    if (tunnelError != null) {
        AlertDialog(
            onDismissRequest = onClearError,
            title = { Text("连接失败") },
            text = { Text(tunnelError) },
            confirmButton = {
                TextButton(onClick = onClearError) { Text("确定") }
            }
        )
    }

    if (showResetDialog) {
        val msg = when (resetCount) {
            1 -> "再点击 2 次确认重置"
            2 -> "再点击 1 次确认重置"
            else -> "确认重置所有配置？"
        }
        AlertDialog(
            onDismissRequest = { showResetDialog = false; resetCount = 0 },
            title = { Text("重置配置") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    if (resetCount >= 3) {
                        resetCount = 0
                        showResetDialog = false
                        onReset()
                    } else {
                        resetCount++
                    }
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false; resetCount = 0 }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("准备就绪") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "配置已完成",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "点击下方按钮进入监控页面",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("隧道状态", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when {
                            connecting -> {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                                    Text("正在连接...", color = MaterialTheme.colorScheme.outline)
                                }
                            }
                            tunnelConnected -> {
                                Text("已连接", color = MaterialTheme.colorScheme.primary)
                            }
                            else -> {
                                Text("未连接", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (connecting) {
                            // no button while connecting
                        } else if (tunnelConnected) {
                            OutlinedButton(onClick = onDisconnectTunnel) {
                                Text("断开隧道")
                            }
                        } else {
                            Button(onClick = onConnectTunnel) {
                                Text("连接隧道")
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("WireGuard 配置", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onEditWireGuard, contentPadding = PaddingValues(0.dp)) {
                            Text("编辑")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    SummaryRow("地址", prefs.getWireGuardAddress())
                    SummaryRow("端点", prefs.getWireGuardEndpoint())
                    SummaryRow("DNS", prefs.getWireGuardDns().ifEmpty { "(未设置)" })
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Frigate 配置", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onEditFrigate, contentPadding = PaddingValues(0.dp)) {
                            Text("编辑")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    SummaryRow("地址", prefs.getFrigateUrl())
                    SummaryRow("用户", prefs.getFrigateUsername().ifEmpty { "(未设置)" })
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (testState == "success" || testState == "error") {
                            onClearTestState()
                        } else {
                            onTest(url)
                        }
                    },
                    enabled = !connecting && (testState == null || testState == "success" || testState == "error")
                ) {
                    when (testState) {
                        "loading" -> {
                            CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                            Text("测试中...")
                        }
                        "success" -> {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.height(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                            Text("HTTP $testHttpCode", color = MaterialTheme.colorScheme.primary)
                        }
                        "error" -> {
                            Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.height(16.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                            Text(if (testHttpCode > 0) "HTTP $testHttpCode" else "失败", color = MaterialTheme.colorScheme.error)
                        }
                        else -> Text("测试连接")
                    }
                }
                Button(
                    onClick = onEnter,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    enabled = !connecting
                ) {
                    Text("进入监控", style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onExportConfig,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导出配置")
                }
                OutlinedButton(
                    onClick = onImportConfig,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导入配置")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = { showResetDialog = true; resetCount = 1 },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("重置所有配置", color = MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CameraScreen(prefs: PreferencesManager, onBack: () -> Unit) {
    var webView by remember { mutableStateOf<com.tencent.smtt.sdk.WebView?>(null) }
    val url = prefs.getFrigateUrl()

    BackHandler {
        webView?.let { if (it.canGoBack()) it.goBack() else onBack() } ?: onBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { wv ->
                (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.settings.javaScriptEnabled = false
                wv.loadUrl("about:blank")
                wv.clearHistory()
                wv.destroy()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 40.dp),
            factory = { ctx ->
                com.tencent.smtt.sdk.WebView(ctx).apply {
                    keepScreenOn = true
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        mediaPlaybackRequiresUserGesture = false
                    }
                    webViewClient = object : com.tencent.smtt.sdk.WebViewClient() {
                        override fun onReceivedSslError(
                            view: com.tencent.smtt.sdk.WebView?,
                            handler: com.tencent.smtt.export.external.interfaces.SslErrorHandler?,
                            error: com.tencent.smtt.export.external.interfaces.SslError?
                        ) {
                            Log.d(TAG, "X5 SSL error, proceeding anyway")
                            handler?.proceed()
                        }
                        override fun onReceivedError(
                            view: com.tencent.smtt.sdk.WebView?,
                            request: com.tencent.smtt.export.external.interfaces.WebResourceRequest?,
                            error: com.tencent.smtt.export.external.interfaces.WebResourceError?
                        ) {
                            Log.e(TAG, "X5 WebView error: ${error?.description} url=${request?.url}")
                        }
                        override fun onPageFinished(view: com.tencent.smtt.sdk.WebView?, pageUrl: String?) {
                            Log.d(TAG, "onPageFinished: $pageUrl")
                            view?.postDelayed({
                                val username = prefs.getFrigateUsername()
                                val password = prefs.getFrigatePassword()
                                if (username.isNotEmpty() && password.isNotEmpty()) {
                                    view.evaluateJavascript("""
                                        (function() {
                                            var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                                            var userField = document.querySelector('input[name="user"]')
                                                || document.querySelector('input[name="username"]')
                                                || document.querySelector('input[type="text"]')
                                                || document.querySelector('input[type="email"]')
                                                || document.querySelector('input[id="user"]')
                                                || document.querySelector('input[id="username"]');
                                            var passField = document.querySelector('input[name="password"]')
                                                || document.querySelector('input[type="password"]')
                                                || document.querySelector('input[id="password"]');
                                            if (userField && passField) {
                                                setter.call(userField, '${username.replace("'", "\\'")}');
                                                userField.dispatchEvent(new Event('input', { bubbles: true }));
                                                userField.dispatchEvent(new Event('change', { bubbles: true }));
                                                setter.call(passField, '${password.replace("'", "\\'")}');
                                                passField.dispatchEvent(new Event('input', { bubbles: true }));
                                                passField.dispatchEvent(new Event('change', { bubbles: true }));
                                                var btn = document.querySelector('button[type="submit"]')
                                                    || document.querySelector('input[type="submit"]')
                                                    || Array.from(document.querySelectorAll('button')).find(function(b) {
                                                        var t = b.textContent.trim().toLowerCase();
                                                        return t.includes('sign') || t.includes('login') || t.includes('submit') || t.includes('登录') || t.includes('确定');
                                                    });
                                                if (btn) { btn.click(); return 'submitted'; }
                                                return 'filled_no_button';
                                            }
                                            return 'no_fields';
                                        })();
                                    """) { result ->
                                        Log.d(TAG, "AUTO_LOGIN: $result")
                                    }
                                }
                            }, 2000)
                        }
                    }
                    webChromeClient = object : com.tencent.smtt.sdk.WebChromeClient() {
                        override fun onPermissionRequest(request: com.tencent.smtt.export.external.interfaces.PermissionRequest?) {
                            request?.grant(request.resources)
                        }
                    }
                    setOnLongClickListener { true }
                    loadUrl(url)
                    webView = this
                }
            },
            update = { webView = it }
        )

        // Overlay buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = {
                webView?.let { if (it.canGoBack()) it.goBack() else onBack() } ?: onBack()
            }) {
                Icon(Icons.Default.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            IconButton(onClick = { webView?.loadUrl(url) }) {
                Icon(Icons.Default.Refresh, "刷新", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun TestConnectionOverlay(url: String, onDismiss: () -> Unit) {
    var status by remember { mutableStateOf("loading") }
    var httpCode by remember { mutableStateOf(0) }

    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                if (connection is HttpsURLConnection) {
                    connection.sslSocketFactory = sslBypassContext.socketFactory
                    connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
                }
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.instanceFollowRedirects = true

                httpCode = connection.responseCode
                connection.disconnect()

                status = if (httpCode in 200..399) "success" else "error"
            } catch (e: Exception) {
                Log.e(TAG, "test connection failed: ${e.message}")
                status = "error"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (status) {
                    "success" -> "连接成功"
                    "error" -> "连接失败"
                    else -> "连接测试"
                }
            )
        },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (status) {
                    "loading" -> {
                        CircularProgressIndicator(modifier = Modifier.height(24.dp), strokeWidth = 2.dp)
                        Text("正在测试：$url")
                    }
                    "success" -> {
                        Icon(
                            Icons.Default.Visibility,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.height(24.dp)
                        )
                        Text("HTTP $httpCode - 连接成功\n$url")
                    }
                    "error" -> {
                        Icon(
                            Icons.Default.VisibilityOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.height(24.dp)
                        )
                        Text(
                            if (httpCode > 0) "HTTP $httpCode - 连接失败\n$url"
                            else "连接失败（网络错误）\n${url}",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                enabled = status != "loading"
            ) {
                Text(if (status == "loading") "测试中..." else "关闭")
            }
        }
    )
}

@Composable
private fun LoadingScreen(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun AuthErrorScreen(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "身份验证失败",
                style = MaterialTheme.typography.headlineMedium
            )
            Button(onClick = onRetry) {
                Text("重试验证")
            }
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.headlineMedium
            )
            Button(onClick = onRetry) {
                Text("重试")
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
}
