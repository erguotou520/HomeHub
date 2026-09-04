package me.erguotou.nvr

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_config", Context.MODE_PRIVATE)

    fun saveWireGuardConfig(
        privateKey: String,
        publicKey: String,
        address: String,
        dns: String,
        peerPublicKey: String,
        allowedIPs: String,
        endpoint: String,
        persistentKeepalive: String
    ) {
        prefs.edit().apply {
            putString("wg_private_key", privateKey)
            putString("wg_public_key", publicKey)
            putString("wg_address", address)
            putString("wg_dns", dns)
            putString("wg_peer_public_key", peerPublicKey)
            putString("wg_allowed_ips", allowedIPs)
            putString("wg_endpoint", endpoint)
            putString("wg_persistent_keepalive", persistentKeepalive)
            apply()
        }
    }

    fun saveFrigateConfig(url: String, username: String, password: String) {
        prefs.edit().apply {
            putString("frigate_url", url)
            putString("frigate_username", username)
            putString("frigate_password", password)
            apply()
        }
    }

    fun isConfigured(): Boolean {
        return prefs.contains("wg_private_key") && prefs.contains("frigate_url")
    }

    fun getWireGuardPrivateKey(): String = prefs.getString("wg_private_key", "") ?: ""
    fun getWireGuardPublicKey(): String = prefs.getString("wg_public_key", "") ?: ""
    fun getWireGuardAddress(): String = prefs.getString("wg_address", "") ?: ""
    fun getWireGuardDns(): String = prefs.getString("wg_dns", "") ?: ""
    fun getWireGuardPeerPublicKey(): String = prefs.getString("wg_peer_public_key", "") ?: ""
    fun getWireGuardAllowedIPs(): String = prefs.getString("wg_allowed_ips", "") ?: ""
    fun getWireGuardEndpoint(): String = prefs.getString("wg_endpoint", "") ?: ""
    fun getWireGuardPersistentKeepalive(): String =
        prefs.getString("wg_persistent_keepalive", "") ?: ""

    fun getFrigateUrl(): String = prefs.getString("frigate_url", "") ?: ""
    fun getFrigateUsername(): String = prefs.getString("frigate_username", "") ?: ""
    fun getFrigatePassword(): String = prefs.getString("frigate_password", "") ?: ""

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun toJson(): String {
        val obj = org.json.JSONObject()
        obj.put("wg_private_key", getWireGuardPrivateKey())
        obj.put("wg_public_key", getWireGuardPublicKey())
        obj.put("wg_address", getWireGuardAddress())
        obj.put("wg_dns", getWireGuardDns())
        obj.put("wg_peer_public_key", getWireGuardPeerPublicKey())
        obj.put("wg_allowed_ips", getWireGuardAllowedIPs())
        obj.put("wg_endpoint", getWireGuardEndpoint())
        obj.put("wg_persistent_keepalive", getWireGuardPersistentKeepalive())
        obj.put("frigate_url", getFrigateUrl())
        obj.put("frigate_username", getFrigateUsername())
        obj.put("frigate_password", getFrigatePassword())
        return obj.toString(2)
    }

    fun fromJson(json: String): Boolean {
        return try {
            val obj = org.json.JSONObject(json)
            saveWireGuardConfig(
                privateKey = obj.optString("wg_private_key"),
                publicKey = obj.optString("wg_public_key"),
                address = obj.optString("wg_address"),
                dns = obj.optString("wg_dns"),
                peerPublicKey = obj.optString("wg_peer_public_key"),
                allowedIPs = obj.optString("wg_allowed_ips"),
                endpoint = obj.optString("wg_endpoint"),
                persistentKeepalive = obj.optString("wg_persistent_keepalive")
            )
            saveFrigateConfig(
                url = obj.optString("frigate_url"),
                username = obj.optString("frigate_username"),
                password = obj.optString("frigate_password")
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
