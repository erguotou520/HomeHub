package me.erguotou.nvr

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import java.util.concurrent.Executors

object WireGuardTunnelManager {
    private const val TAG = "WgTunnel"
    private const val TUNNEL_NAME = "wg0"

    private val executor = Executors.newSingleThreadExecutor()

    val tunnelState = mutableStateOf(Tunnel.State.DOWN)
    val isConnecting = mutableStateOf(false)
    val lastError = mutableStateOf<String?>(null)
    val isConnected: Boolean get() = tunnelState.value == Tunnel.State.UP

    private val tunnel = object : Tunnel {
        override fun getName() = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            Log.d(TAG, "Tunnel state changed: $newState")
            tunnelState.value = newState
            if (newState == Tunnel.State.UP) {
                isConnecting.value = false
                lastError.value = null
            } else if (newState == Tunnel.State.DOWN) {
                isConnecting.value = false
            }
        }
    }

    private var backend: Backend? = null

    private fun getBackend(context: Context): Backend {
        return backend ?: GoBackend(context).also { backend = it }
    }

    fun getVpnIntent(context: Context): Intent? {
        return VpnService.prepare(context)
    }

    fun connect(context: Context, prefs: PreferencesManager, callback: ((Boolean, String?) -> Unit)? = null) {
        Log.i(TAG, "connect() called")
        isConnecting.value = true
        lastError.value = null
        executor.execute {
            try {
                val config = buildConfig(prefs)
                Log.i(TAG, "Config built successfully, getting backend...")
                val b = getBackend(context)
                Log.i(TAG, "Backend obtained, setting tunnel UP...")
                b.setState(tunnel, Tunnel.State.UP, config)
                Log.i(TAG, "Tunnel connect succeeded, state=${tunnelState.value}")
                callback?.invoke(true, null)
            } catch (e: Exception) {
                val errMsg = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "Failed to connect tunnel: $errMsg", e)
                tunnelState.value = Tunnel.State.DOWN
                isConnecting.value = false
                lastError.value = errMsg
                callback?.invoke(false, errMsg)
            }
        }
    }

    fun disconnect(context: Context, callback: ((Boolean) -> Unit)? = null) {
        Log.i(TAG, "disconnect() called")
        executor.execute {
            try {
                val b = backend ?: return@execute
                b.setState(tunnel, Tunnel.State.DOWN, null)
                Log.i(TAG, "Tunnel disconnect succeeded")
                callback?.invoke(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disconnect tunnel", e)
                callback?.invoke(false)
            }
        }
    }

    private fun buildConfig(prefs: PreferencesManager): Config {
        val privateKey = prefs.getWireGuardPrivateKey()
        val address = prefs.getWireGuardAddress()
        val dns = prefs.getWireGuardDns()
        val peerPublicKey = prefs.getWireGuardPeerPublicKey()
        val allowedIPs = prefs.getWireGuardAllowedIPs()
        val endpoint = prefs.getWireGuardEndpoint()
        val persistentKeepalive = prefs.getWireGuardPersistentKeepalive()

        Log.d(TAG, "Building config: address=$address, dns=$dns, endpoint=$endpoint, peerPubKey=${peerPublicKey.take(8)}..., allowedIPs=$allowedIPs, keepalive=$persistentKeepalive")

        val iface = Interface.Builder().apply {
            parsePrivateKey(privateKey)
            if (address.isNotEmpty()) parseAddresses(address)
            if (dns.isNotEmpty()) parseDnsServers(dns)
        }.build()

        val peer = Peer.Builder().apply {
            parsePublicKey(peerPublicKey)
            if (allowedIPs.isNotEmpty()) parseAllowedIPs(allowedIPs)
            parseEndpoint(endpoint)
            if (persistentKeepalive.isNotEmpty()) parsePersistentKeepalive(persistentKeepalive)
        }.build()

        return Config.Builder().apply {
            setInterface(iface)
            addPeer(peer)
        }.build()
    }
}
