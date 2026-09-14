package me.erguotou.homehub.security

import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide state for the biometric gate.
 *
 * The gate has three observable states, and keeping them apart is what stops
 * the 已锁定 screen from flashing in front of the prompt: while the system
 * prompt is up there is nothing for the user to read yet, so the app shows a
 * neutral surface, and the retry screen appears only once the prompt has
 * actually been dismissed. The single boolean this used to be could not
 * express that difference.
 *
 * The lock lives outside the Activity so that
 *  * the 设置 screen can lock the app the instant the toggle is flipped
 *    (MainActivity observes [state] and swaps in the gate), and
 *  * a configuration change does not re-lock an already unlocked app —
 *    [ensureInitialized] only seeds the state once per process.
 */
object AppLock {

    enum class State {
        /** Content is on screen. */
        UNLOCKED,

        /** The system prompt is up; show a neutral surface, not "已锁定". */
        AUTHENTICATING,

        /** The prompt was dismissed or refused; offer a retry. */
        LOCKED
    }

    private val _state = MutableStateFlow(State.UNLOCKED)
    val state: StateFlow<State> = _state.asStateFlow()

    /** True while the app must not show its content. */
    val locked: Boolean
        get() = _state.value != State.UNLOCKED

    private var initialized = false

    /** When the user last passed the prompt, on the monotonic clock. */
    private var lastUnlockAt = 0L

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
     * Seed the state on the first Activity creation of this process. Later
     * creations (rotation, dark-mode switch) keep whatever the user has
     * already reached.
     *
     * [gateEnabled] is only ever true when an authenticator is available —
     * MainActivity clears the preference first — so an enabled gate starts
     * already in [State.AUTHENTICATING] and goes straight to the prompt
     * instead of drawing 已锁定 first.
     */
    fun ensureInitialized(gateEnabled: Boolean) {
        if (initialized) return
        initialized = true
        _state.value = if (gateEnabled) State.AUTHENTICATING else State.UNLOCKED
    }

    /**
     * Arm the gate. The prompt is about to be shown, so skip the retry screen:
     * used by the 设置 toggle and by the background re-lock.
     */
    fun lock() {
        initialized = true
        _state.value = State.AUTHENTICATING
    }

    /** The prompt was dismissed or refused — show the retry screen. */
    fun requireAuthentication() {
        initialized = true
        _state.value = State.LOCKED
    }

    fun unlock() {
        initialized = true
        lastUnlockAt = SystemClock.elapsedRealtime()
        _state.value = State.UNLOCKED
    }

    /**
     * When the user last passed the prompt. A return to the foreground must
     * not re-lock an app the user just unlocked — answering the prompt can
     * itself take longer than the grace window.
     */
    fun lastUnlockAt(): Long = lastUnlockAt
}
