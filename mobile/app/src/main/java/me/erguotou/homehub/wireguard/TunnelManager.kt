package me.erguotou.homehub.wireguard

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * WireGuard tunnel management for the single `wg0` tunnel.
 *
 * Extracted from the old monolithic `MainActivity`; the only behavioural
 * change is that state is exposed as a [StateFlow] instead of Compose
 * `mutableStateOf`, so non-UI code (WorkManager, repository) can observe it.
 */
object TunnelManager {

    private val executor = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow(Tunnel.State.DOWN)
    val state: StateFlow<Tunnel.State> = _state.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var backend: Backend? = null

    private val tunnel = object : Tunnel {
        override fun getName() = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            Log.d(TAG, "tunnel state -> $newState")
            _state.value = newState
            if (newState == Tunnel.State.UP) {
                _connecting.value = false
                _lastError.value = null
            } else if (newState == Tunnel.State.DOWN) {
                _connecting.value = false
            }
        }
    }

    val isConnected: Boolean get() = _state.value == Tunnel.State.UP

    private fun backend(ctx: Context): Backend =
        backend ?: GoBackend(ctx).also { backend = it }

    /** Returns a non-null intent when the VPN permission must be granted first. */
    fun prepareIntent(context: Context): Intent? = VpnService.prepare(context)

    fun connect(
        context: Context,
        prefs: me.erguotou.homehub.data.Prefs,
        callback: ((Boolean, String?) -> Unit)? = null
    ) {
        _connecting.value = true
        _lastError.value = null
        executor.execute {
            try {
                val config = buildConfig(prefs)
                backend(context).setState(tunnel, Tunnel.State.UP, config)
                callback?.invoke(true, null)
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "connect failed: $msg", e)
                _state.value = Tunnel.State.DOWN
                _connecting.value = false
                _lastError.value = msg
                callback?.invoke(false, msg)
            }
        }
    }

    fun disconnect(context: Context, callback: ((Boolean) -> Unit)? = null) {
        executor.execute {
            try {
                val b = backend ?: run {
                    callback?.invoke(false)
                    return@execute
                }
                b.setState(tunnel, Tunnel.State.DOWN, null)
                callback?.invoke(true)
            } catch (e: Exception) {
                Log.e(TAG, "disconnect failed", e)
                callback?.invoke(false)
            }
        }
    }

    private fun buildConfig(prefs: me.erguotou.homehub.data.Prefs): Config {
        val iface = Interface.Builder().apply {
            parsePrivateKey(prefs.wgPrivateKey)
            if (prefs.wgAddress.isNotBlank()) parseAddresses(prefs.wgAddress)
            if (prefs.wgDns.isNotBlank()) parseDnsServers(prefs.wgDns)
        }.build()

        val peer = Peer.Builder().apply {
            parsePublicKey(prefs.wgPeerPublicKey)
            if (prefs.wgAllowedIps.isNotBlank()) parseAllowedIPs(prefs.wgAllowedIps)
            if (prefs.wgEndpoint.isNotBlank()) parseEndpoint(prefs.wgEndpoint)
            if (prefs.wgKeepalive.isNotBlank()) parsePersistentKeepalive(prefs.wgKeepalive)
        }.build()

        return Config.Builder().apply {
            setInterface(iface)
            addPeer(peer)
        }.build()
    }

}

private const val TAG = "HomeHubTunnel"
private const val TUNNEL_NAME = "wg0"

/** Curve25519 helper around the bundled wireguard-android key classes. */
object WgKeygen {
    fun generate(): Pair<String, String> {
        val pair = KeyPair()
        return pair.privateKey.toBase64() to pair.publicKey.toBase64()
    }

    fun publicKeyOf(privateKeyBase64: String): String? = try {
        KeyPair(Key.fromBase64(privateKeyBase64)).publicKey.toBase64()
    } catch (e: Exception) {
        null
    }
}
