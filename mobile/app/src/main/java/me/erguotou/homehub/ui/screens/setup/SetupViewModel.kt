package me.erguotou.homehub.ui.screens.setup

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import me.erguotou.homehub.data.Prefs
import me.erguotou.homehub.data.Repository
import me.erguotou.homehub.wireguard.WgKeygen

data class SetupUiState(
    // step 0 = WireGuard, 1 = server, 2 = connection test
    val step: Int = 0,
    val privateKey: String = "",
    val publicKey: String = "",
    val address: String = "10.0.0.2/24",
    val dns: String = "",
    val peerPublicKey: String = "",
    val allowedIps: String = "10.0.0.0/24",
    val endpoint: String = "",
    val keepalive: String = "25",
    val serverAddress: String = "",
    val serverPort: String = "8485",
    val useHttps: Boolean = false,
    /** 监控数据面的共享令牌；后台没开校验时留空。 */
    val deviceToken: String = "",
    val testing: Boolean = false,
    val testResult: String? = null,
    val saved: Boolean = false
)

class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val repository = Repository(app)

    var state: SetupUiState by androidx.compose.runtime.mutableStateOf(
        SetupUiState(
            privateKey = prefs.wgPrivateKey,
            publicKey = prefs.wgPublicKey,
            address = prefs.wgAddress.ifBlank { "10.0.0.2/24" },
            dns = prefs.wgDns,
            peerPublicKey = prefs.wgPeerPublicKey,
            allowedIps = prefs.wgAllowedIps.ifBlank { "10.0.0.0/24" },
            endpoint = prefs.wgEndpoint,
            keepalive = prefs.wgKeepalive,
            serverAddress = prefs.serverAddress,
            serverPort = prefs.serverPort.toString(),
            useHttps = prefs.useHttps,
            deviceToken = prefs.deviceToken
        )
    )
        private set

    fun update(mutate: SetupUiState.() -> SetupUiState) {
        state = state.mutate()
    }

    fun generateKeys() {
        val (privateKey, publicKey) = WgKeygen.generate()
        state = state.copy(privateKey = privateKey, publicKey = publicKey)
    }

    fun publicKeyOf(privateKey: String): String? = WgKeygen.publicKeyOf(privateKey)

    fun save() {
        prefs.saveWireGuard(
            privateKey = state.privateKey.trim(),
            publicKey = state.publicKey.trim(),
            address = state.address.trim(),
            dns = state.dns.trim(),
            peerPublicKey = state.peerPublicKey.trim(),
            allowedIps = state.allowedIps.trim(),
            endpoint = state.endpoint.trim(),
            keepalive = state.keepalive.trim()
        )
        prefs.serverAddress = state.serverAddress.trim()
        prefs.serverPort = state.serverPort.toIntOrNull() ?: 8485
        prefs.useHttps = state.useHttps
        prefs.deviceToken = state.deviceToken
        repository.invalidate()
        state = state.copy(saved = true)
    }

    /** Probe `/api/health`; only meaningful while the tunnel is up. */
    fun testConnection() {
        save()
        state = state.copy(testing = true, testResult = null)
        viewModelScope.launch {
            val result = repository.health()
            state = state.copy(
                testing = false,
                testResult = result.fold(
                    onSuccess = { "已连接：HomeHub v${it.version}，${it.photos} 张照片" },
                    onFailure = { e -> "连接失败：${e.message ?: e.javaClass.simpleName}" }
                )
            )
        }
    }

    fun import(json: String): Boolean {
        val ok = prefs.importConfig(json)
        if (ok) {
            state = SetupUiState(
                privateKey = prefs.wgPrivateKey,
                publicKey = prefs.wgPublicKey,
                address = prefs.wgAddress,
                dns = prefs.wgDns,
                peerPublicKey = prefs.wgPeerPublicKey,
                allowedIps = prefs.wgAllowedIps,
                endpoint = prefs.wgEndpoint,
                keepalive = prefs.wgKeepalive,
                serverAddress = prefs.serverAddress,
                serverPort = prefs.serverPort.toString(),
                useHttps = prefs.useHttps,
                deviceToken = prefs.deviceToken
            )
        }
        return ok
    }

    fun export(): String = prefs.exportConfig()
}
