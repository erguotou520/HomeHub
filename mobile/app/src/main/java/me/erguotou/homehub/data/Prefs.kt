@file:Suppress("DEPRECATION")

package me.erguotou.homehub.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Encrypted credential store.
 *
 * `EncryptedSharedPreferences` is marked deprecated in `security-crypto` 1.1.0,
 * but it is still the supported Keystore-backed store (Tink based); the newer
 * androidx.datastore replacement for encrypted prefs is not available yet.
 *
 * The previous version kept the WireGuard private key in plain
 * SharedPreferences – it now lives in an AES256-GCM encrypted file backed by
 * the Android Keystore.
 */
@Suppress("DEPRECATION")
class Prefs(context: Context) {

    private val prefs: SharedPreferences = openStore(context)

    // ─────────────────────────── server ────────────────────────────

    var serverAddress: String
        get() = prefs.getString(KEY_SERVER_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_ADDRESS, value.trim()).apply()

    var serverPort: Int
        get() = prefs.getInt(KEY_SERVER_PORT, 8485)
        set(value) = prefs.edit().putInt(KEY_SERVER_PORT, value).apply()

    var useHttps: Boolean
        get() = prefs.getBoolean(KEY_USE_HTTPS, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_HTTPS, value).apply()

    /** PEM encoded self-signed server certificate (optional). */
    var serverCertPem: String
        get() = prefs.getString(KEY_SERVER_CERT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_CERT, value.trim()).apply()

    /** Trust the configured certificate only (pin it); false = system trust. */
    var trustCustomCert: Boolean
        get() = prefs.getBoolean(KEY_TRUST_CUSTOM, false)
        set(value) = prefs.edit().putBoolean(KEY_TRUST_CUSTOM, value).apply()

    var biometricLock: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC, value).apply()

    var deleteAfterUpload: Boolean
        get() = prefs.getBoolean(KEY_DELETE_AFTER_UPLOAD, false)
        set(value) = prefs.edit().putBoolean(KEY_DELETE_AFTER_UPLOAD, value).apply()

    /**
     * What to do when the server reports the uploaded photo already exists.
     * "skip" discards the duplicate (the default), "keep" stores both copies.
     */
    var duplicatePolicy: String
        get() = prefs.getString(KEY_DUPLICATE_POLICY, "skip") ?: "skip"
        set(value) = prefs.edit().putString(KEY_DUPLICATE_POLICY, value).apply()

    var lastUploadDir: String
        get() = prefs.getString(KEY_LAST_UPLOAD_DIR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_UPLOAD_DIR, value).apply()

    /** Sub-path inside [lastUploadDir], empty when the root itself was picked. */
    var lastUploadPath: String
        get() = prefs.getString(KEY_LAST_UPLOAD_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_UPLOAD_PATH, value.trim('/')).apply()

    /**
     * Remember where the last upload landed, so the picker can open there next
     * time. Internal convenience only — it is not surfaced in 设置.
     */
    fun rememberUploadTarget(dir: String, path: String) {
        lastUploadDir = dir.trim()
        lastUploadPath = path
    }

    /**
     * Root directory the 文件 tab was last browsing. The server registers
     * several named directories and the tab used to always reopen the first
     * one, which made the others look unreachable. Internal convenience only,
     * not surfaced in 设置.
     */
    var lastFileDir: String
        get() = prefs.getString(KEY_LAST_FILE_DIR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_FILE_DIR, value.trim()).apply()

    var frigateUrl: String
        get() = prefs.getString(KEY_FRIGATE_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FRIGATE_URL, value.trim()).apply()

    fun baseUrl(): String {
        val scheme = if (useHttps) "https" else "http"
        val host = serverAddress.trim().trimEnd('/')
        return "$scheme://$host:$serverPort"
    }

    fun isServerConfigured(): Boolean = serverAddress.isNotBlank()

    // ─────────────────────────── WireGuard ─────────────────────────

    var wgPrivateKey: String
        get() = prefs.getString(WG_PRIVATE_KEY, "") ?: ""
        set(value) = prefs.edit().putString(WG_PRIVATE_KEY, value).apply()

    var wgPublicKey: String
        get() = prefs.getString(WG_PUBLIC_KEY, "") ?: ""
        set(value) = prefs.edit().putString(WG_PUBLIC_KEY, value).apply()

    var wgAddress: String
        get() = prefs.getString(WG_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(WG_ADDRESS, value).apply()

    var wgDns: String
        get() = prefs.getString(WG_DNS, "") ?: ""
        set(value) = prefs.edit().putString(WG_DNS, value).apply()

    var wgPeerPublicKey: String
        get() = prefs.getString(WG_PEER_PUBLIC_KEY, "") ?: ""
        set(value) = prefs.edit().putString(WG_PEER_PUBLIC_KEY, value).apply()

    var wgAllowedIps: String
        get() = prefs.getString(WG_ALLOWED_IPS, "") ?: ""
        set(value) = prefs.edit().putString(WG_ALLOWED_IPS, value).apply()

    var wgEndpoint: String
        get() = prefs.getString(WG_ENDPOINT, "") ?: ""
        set(value) = prefs.edit().putString(WG_ENDPOINT, value).apply()

    var wgKeepalive: String
        get() = prefs.getString(WG_KEEPALIVE, "25") ?: "25"
        set(value) = prefs.edit().putString(WG_KEEPALIVE, value).apply()

    fun isWireGuardConfigured(): Boolean =
        wgPrivateKey.isNotBlank() && wgPeerPublicKey.isNotBlank() && wgEndpoint.isNotBlank()

    fun saveWireGuard(
        privateKey: String,
        publicKey: String,
        address: String,
        dns: String,
        peerPublicKey: String,
        allowedIps: String,
        endpoint: String,
        keepalive: String
    ) {
        wgPrivateKey = privateKey
        wgPublicKey = publicKey
        wgAddress = address
        wgDns = dns
        wgPeerPublicKey = peerPublicKey
        wgAllowedIps = allowedIps
        wgEndpoint = endpoint
        wgKeepalive = keepalive
    }

    /** Export everything except the private key (safe to share). */
    fun exportConfig(): String {
        val obj = JSONObject()
        obj.put("server_address", serverAddress)
        obj.put("server_port", serverPort)
        obj.put("use_https", useHttps)
        obj.put("wg_public_key", wgPublicKey)
        obj.put("wg_address", wgAddress)
        obj.put("wg_dns", wgDns)
        obj.put("wg_peer_public_key", wgPeerPublicKey)
        obj.put("wg_allowed_ips", wgAllowedIps)
        obj.put("wg_endpoint", wgEndpoint)
        obj.put("wg_persistent_keepalive", wgKeepalive)
        obj.put("frigate_url", frigateUrl)
        return obj.toString(2)
    }

    fun importConfig(json: String, includePrivateKey: Boolean = true): Boolean {
        return try {
            val obj = JSONObject(json)
            if (obj.has("server_address")) serverAddress = obj.optString("server_address")
            if (obj.has("server_port")) serverPort = obj.optInt("server_port", 8485)
            if (obj.has("use_https")) useHttps = obj.optBoolean("use_https")
            if (includePrivateKey && obj.has("wg_private_key")) {
                wgPrivateKey = obj.optString("wg_private_key")
            }
            if (obj.has("wg_public_key")) wgPublicKey = obj.optString("wg_public_key")
            if (obj.has("wg_address")) wgAddress = obj.optString("wg_address")
            if (obj.has("wg_dns")) wgDns = obj.optString("wg_dns")
            if (obj.has("wg_peer_public_key")) wgPeerPublicKey = obj.optString("wg_peer_public_key")
            if (obj.has("wg_allowed_ips")) wgAllowedIps = obj.optString("wg_allowed_ips")
            if (obj.has("wg_endpoint")) wgEndpoint = obj.optString("wg_endpoint")
            if (obj.has("wg_persistent_keepalive")) wgKeepalive = obj.optString("wg_persistent_keepalive")
            if (obj.has("frigate_url")) frigateUrl = obj.optString("frigate_url")
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clearAll() = prefs.edit().clear().apply()

    /**
     * Open the encrypted store, rebuilding it if it cannot be read.
     *
     * The Keystore master key and the encrypted keyset beside it can drift
     * apart — reinstalling over restored data, a Keystore reset, or a restore
     * from a backup onto a new device all leave a file that nothing can
     * decrypt. `EncryptedSharedPreferences.create` throws when that happens,
     * and because this runs in `onCreate` the app used to crash on every
     * launch with no way out. Wiping both halves and retrying keeps the app
     * reachable; the credentials are re-entered through 设置 like on a fresh
     * install.
     */
    private fun openStore(context: Context): SharedPreferences {
        try {
            return createEncrypted(context)
        } catch (e: Exception) {
            Log.w(TAG, "encrypted preferences unreadable, rebuilding", e)
        }
        runCatching { context.deleteSharedPreferences(FILE_NAME) }
        runCatching { deleteMasterKey() }
        try {
            return createEncrypted(context)
        } catch (e: Exception) {
            // Last resort: stay usable rather than crash-looping at launch.
            //
            // KNOWN RESIDUAL RISK: this store holds the WireGuard private key
            // and the server credentials in *cleartext*. It is only reached
            // when the Android Keystore itself is unusable (twice), and the
            // alternative was an app that could not start at all. Anything
            // written here must be treated as recoverable by a local attacker
            // with file access – re-entering the configuration once the
            // Keystore recovers is what restores the encrypted store.
            Log.e(TAG, "encrypted preferences unavailable, using PLAIN-TEXT storage", e)
        }
        return context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    @Suppress("DEPRECATION")
    private fun createEncrypted(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Drop the Keystore entry so a fresh one is generated on the retry. */
    @Suppress("DEPRECATION")
    private fun deleteMasterKey() {
        java.security.KeyStore.getInstance("AndroidKeyStore")
            .apply { load(null) }
            .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
    }

    companion object {
        private const val TAG = "HomeHubPrefs"
        private const val FILE_NAME = "homehub_secure_prefs"

        private const val KEY_SERVER_ADDRESS = "server_address"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_USE_HTTPS = "use_https"
        private const val KEY_SERVER_CERT = "server_cert"
        private const val KEY_TRUST_CUSTOM = "trust_custom_cert"
        private const val KEY_BIOMETRIC = "biometric_lock"
        private const val KEY_DELETE_AFTER_UPLOAD = "delete_after_upload"
        private const val KEY_DUPLICATE_POLICY = "duplicate_policy"
        private const val KEY_LAST_UPLOAD_DIR = "last_upload_dir"
        private const val KEY_LAST_UPLOAD_PATH = "last_upload_path"
        private const val KEY_LAST_FILE_DIR = "last_file_dir"
        private const val KEY_FRIGATE_URL = "frigate_url"

        private const val WG_PRIVATE_KEY = "wg_private_key"
        private const val WG_PUBLIC_KEY = "wg_public_key"
        private const val WG_ADDRESS = "wg_address"
        private const val WG_DNS = "wg_dns"
        private const val WG_PEER_PUBLIC_KEY = "wg_peer_public_key"
        private const val WG_ALLOWED_IPS = "wg_allowed_ips"
        private const val WG_ENDPOINT = "wg_endpoint"
        private const val WG_KEEPALIVE = "wg_persistent_keepalive"
    }
}
