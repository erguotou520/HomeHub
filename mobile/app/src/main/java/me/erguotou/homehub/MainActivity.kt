package me.erguotou.homehub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.erguotou.homehub.data.Prefs
import me.erguotou.homehub.security.AppLock
import me.erguotou.homehub.ui.HomeHubRoot
import me.erguotou.homehub.ui.screens.setup.SetupScreen
import me.erguotou.homehub.ui.theme.HomeHubTheme
import me.erguotou.homehub.wireguard.TunnelManager

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    /** Set when the app leaves the foreground, cleared when it comes back. */
    private var backgroundedAt = 0L

    /** VPN permission is requested once; the result re-triggers connect(). */
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) connectTunnel()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* upload notifications are best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = Prefs(this)

        android.util.Log.d(
            "HomeHubGate",
            "server=${prefs.isServerConfigured()} addr='${prefs.serverAddress}' " +
                "wg=${prefs.isWireGuardConfigured()} priv=${prefs.wgPrivateKey.length} " +
                "peer=${prefs.wgPeerPublicKey.length} ep='${prefs.wgEndpoint}'"
        )

        // A lock that can never be satisfied traps the user on the lock screen
        // forever (device lost its fingerprint / lock screen). Turn it off
        // instead of showing a prompt that always fails.
        if (prefs.biometricLock && !AppLock.canAuthenticate(this)) {
            prefs.biometricLock = false
        }
        AppLock.ensureInitialized(prefs.biometricLock)

        requestNotificationPermission()
        if (prefs.isWireGuardConfigured()) connectTunnel()

        observeForegroundRelock()

        val canAuthenticate = AppLock.canAuthenticate(this)
        val unavailableReason = AppLock.unavailableReason(this)

        setContent {
            HomeHubTheme {
                val locked by AppLock.locked.collectAsStateWithLifecycle()
                // Only the server address is required to enter the app; the
                // WireGuard tunnel is optional (LAN access works without it)
                // and can be configured later from 设置 → 编辑配置.
                var showSetup by remember { mutableStateOf(!prefs.isServerConfigured()) }

                if (locked && canAuthenticate) {
                    LaunchedEffect(Unit) { authenticate { AppLock.unlock() } }
                }

                when {
                    showSetup -> SetupScreen(onFinished = { showSetup = false })
                    locked -> LockScreen(
                        message = unavailableReason ?: "验证指纹、面容或锁屏密码以进入 HomeHub",
                        canAuthenticate = canAuthenticate,
                        onAuthenticate = { authenticate { AppLock.unlock() } },
                        onDisable = {
                            prefs.biometricLock = false
                            AppLock.unlock()
                        }
                    )
                    else -> HomeHubRoot()
                }
            }
        }
    }

    /**
     * Re-lock once the app has been in the background for longer than
     * [LOCK_GRACE_MS]. The grace window keeps short hops (file picker, share
     * sheet, permission dialog) from locking the user out mid-task.
     */
    private fun observeForegroundRelock() {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val since = backgroundedAt
                backgroundedAt = 0L
                if (since > 0L && prefs.biometricLock && !AppLock.locked.value &&
                    SystemClock.elapsedRealtime() - since >= LOCK_GRACE_MS
                ) {
                    AppLock.lock()
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                if (prefs.biometricLock) backgroundedAt = SystemClock.elapsedRealtime()
            }
        })
    }

    private fun connectTunnel() {
        val intent = TunnelManager.prepareIntent(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
            return
        }
        TunnelManager.connect(this, prefs)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Biometric gate (PRD M5). Falls back to the device credential.
     *
     * Cancelling leaves the lock screen up — the screen offers a 验证身份 retry
     * button, and the system back gesture exits the app.
     */
    private fun authenticate(onSuccess: () -> Unit) {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("HomeHub")
            .setSubtitle("验证身份以进入")
            .setAllowedAuthenticators(AppLock.authenticators())
            .build()
        prompt.authenticate(info)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private companion object {
        /** How long the app may sit in the background before it re-locks. */
        const val LOCK_GRACE_MS = 10_000L
    }
}

/**
 * Full-screen lock. Occupies the whole window so no content is visible behind
 * it, and always gives the user a way forward: retry, or switch the feature
 * off when the device cannot satisfy the prompt at all.
 */
@Composable
private fun LockScreen(
    message: String,
    canAuthenticate: Boolean,
    onAuthenticate: () -> Unit,
    onDisable: () -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Box(modifier = Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text("已锁定", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            if (canAuthenticate) {
                Button(onClick = onAuthenticate, modifier = Modifier.fillMaxWidth()) {
                    Text("验证身份")
                }
            } else {
                OutlinedButton(onClick = onDisable, modifier = Modifier.fillMaxWidth()) {
                    Text("关闭生物识别门禁")
                }
            }
        }
    }
}

/** Small helper so screens can observe the tunnel without owning it. */
@androidx.compose.runtime.Composable
fun rememberTunnelConnected(): Boolean {
    val state by TunnelManager.state.collectAsStateWithLifecycle()
    return state == com.wireguard.android.backend.Tunnel.State.UP
}
