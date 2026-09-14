package me.erguotou.homehub.security

import android.content.Context
import androidx.biometric.BiometricManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide state for the biometric gate.
 *
 * The lock lives outside the Activity so that
 *  * the 设置 screen can lock the app the instant the toggle is flipped
 *    (MainActivity observes [locked] and swaps in the lock screen), and
 *  * a configuration change does not re-lock an already unlocked app —
 *    [ensureInitialized] only seeds the state once per process.
 */
object AppLock {

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var initialized = false

    /** Fingerprint/face, or the device PIN/pattern/password as a fallback. */
    private val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun authenticators(): Int = authenticators

    /** True when the prompt can actually be shown on this device. */
    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /** Null when available, otherwise a sentence explaining why it is not. */
    fun unavailableReason(context: Context): String? =
        when (BiometricManager.from(context).canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                "设备还没有设置锁屏，请先在系统设置里添加指纹、面容或锁屏密码。"
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                "设备不支持生物识别，也没有设置锁屏密码。"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                "生物识别硬件暂时不可用，请稍后重试。"
            else -> "当前设备无法使用生物识别或锁屏密码。"
        }

    /**
     * Seed the lock state on the first Activity creation of this process.
     * Later creations (rotation, dark-mode switch) keep whatever the user has
     * already unlocked.
     */
    fun ensureInitialized(lock: Boolean) {
        if (!initialized) {
            _locked.value = lock
            initialized = true
        }
    }

    fun lock() {
        initialized = true
        _locked.value = true
    }

    fun unlock() {
        initialized = true
        _locked.value = false
    }
}
