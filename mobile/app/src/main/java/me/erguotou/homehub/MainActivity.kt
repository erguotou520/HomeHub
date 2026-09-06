package me.erguotou.homehub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.erguotou.homehub.data.Prefs
import me.erguotou.homehub.ui.HomeHubRoot
import me.erguotou.homehub.ui.screens.setup.SetupScreen
import me.erguotou.homehub.ui.theme.HomeHubTheme
import me.erguotou.homehub.wireguard.TunnelManager

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

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

        requestNotificationPermission()
        if (prefs.isWireGuardConfigured()) connectTunnel()

        setContent {
            HomeHubTheme {
                var unlocked by rememberSaveable { mutableStateOf(!prefs.biometricLock) }
                var showSetup by remember {
                    mutableStateOf(!prefs.isServerConfigured() || !prefs.isWireGuardConfigured())
                }

                if (prefs.biometricLock && !unlocked) {
                    LaunchedEffect(Unit) { authenticate { unlocked = true } }
                }

                when {
                    showSetup -> SetupScreen(onFinished = { showSetup = false })
                    unlocked -> HomeHubRoot()
                    else -> Unit
                }
            }
        }
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

    /** Biometric gate (PRD M5). Falls back to device credential. */
    private fun authenticate(onSuccess: () -> Unit) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Device credential fallbacks report errors here too; closing
                    // the dialog leaves the app locked.
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED
                    ) {
                        finish()
                    }
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("HomeHub")
            .setSubtitle("验证身份以进入")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

/** Small helper so screens can observe the tunnel without owning it. */
@androidx.compose.runtime.Composable
fun rememberTunnelConnected(): Boolean {
    val state by TunnelManager.state.collectAsStateWithLifecycle()
    return state == com.wireguard.android.backend.Tunnel.State.UP
}
