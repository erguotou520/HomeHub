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
import me.erguotou.homehub.security.GatePolicy
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

        // Deliberately terse: the previous message logged the server address,
        // the WireGuard endpoint and key lengths, which leaks the internal
        // topology to anything that can read logcat.
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "HomeHubGate",
                "server=${prefs.isServerConfigured()} wg=${prefs.isWireGuardConfigured()}"
            )
        }

        val canAuthenticate = AppLock.canAuthenticate(this)
        val unavailableReason = AppLock.unavailableReason(this)

        // A lock that can never be satisfied traps the user on the lock screen
        // forever (device lost its fingerprint / lock screen). Turn it off
        // instead of showing a prompt that always fails.
        if (prefs.biometricLock && !canAuthenticate) {
            prefs.biometricLock = false
        }
        // An enabled gate therefore implies an available authenticator, so it
        // can open straight into the prompt rather than drawing 已锁定 first.
        AppLock.ensureInitialized(gateEnabled = prefs.biometricLock)

        requestNotificationPermission()
        if (prefs.isWireGuardConfigured()) connectTunnel()

        observeForegroundRelock()

        setContent {
            HomeHubTheme {
                val gate by AppLock.state.collectAsStateWithLifecycle()
                // Only the server address is required to enter the app; the
                // WireGuard tunnel is optional (LAN access works without it)
                // and can be configured later from 设置 → 编辑配置.
                var showSetup by remember { mutableStateOf(!prefs.isServerConfigured()) }

                // The prompt is driven by the state instead of being fired
                // once, so both the retry button and the 设置 toggle come back
                // through here.
                LaunchedEffect(gate) {
                    if (gate == AppLock.State.AUTHENTICATING) {
                        authenticate(
                            onSuccess = { AppLock.unlock() },
                            onDismissed = { AppLock.requireAuthentication() }
                        )
                    }
                }

                when {
                    showSetup -> SetupScreen(onFinished = { showSetup = false })
                    gate == AppLock.State.UNLOCKED -> HomeHubRoot()
                    // Behind the prompt: wordless, so 已锁定 never flashes.
                    gate == AppLock.State.AUTHENTICATING -> AuthenticatingGate()
                    else -> LockScreen(
                        message = unavailableReason ?: "验证指纹、面容或锁屏密码以进入 HomeHub",
                        canAuthenticate = canAuthenticate,
                        onAuthenticate = { AppLock.lock() },
                        onDisable = {
                            prefs.biometricLock = false
                            AppLock.unlock()
                        }
                    )
                }
            }
        }
    }

    /**
     * Re-lock once the app has been in the background for longer than the
     * grace window. All the deciding happens in [GatePolicy] so the rule can
     * be tested without a fingerprint; this only feeds it the clock and the
     * lifecycle callbacks.
     */
    private fun observeForegroundRelock() {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val since = backgroundedAt
                backgroundedAt = 0L
                if (GatePolicy.shouldRelockOnReturn(
                        backgroundedAt = since,
                        lastUnlockAt = AppLock.lastUnlockAt(),
                        now = SystemClock.elapsedRealtime(),
                        gateEnabled = prefs.biometricLock,
                        alreadyLocked = AppLock.locked
                    )
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
     * Dismissing the prompt (system back / 取消) hands control back to the
     * retry screen. A rejected finger does *not* arrive here — that is
     * [BiometricPrompt.AuthenticationCallback.onAuthenticationFailed], and the
     * prompt stays up so the user can simply try again.
     */
    private fun authenticate(onSuccess: () -> Unit, onDismissed: () -> Unit) {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onDismissed()
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
}

/**
 * What sits behind the system prompt.
 *
 * Deliberately wordless: the prompt already tells the user what to do, and
 * drawing 已锁定 here would flash a lock screen in front of it — and then take
 * it away again the moment the finger lands, which reads as a glitch.
 */
@Composable
private fun AuthenticatingGate() {
    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
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
        }
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
