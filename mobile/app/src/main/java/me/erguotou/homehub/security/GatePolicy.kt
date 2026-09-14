package me.erguotou.homehub.security

/**
 * The policy behind the biometric gate's background re-lock.
 *
 * Deliberately free of Android types so the whole truth table is unit
 * testable. Two cases are the ones that actually bite:
 *
 *  * **the user just answered the prompt** — answering can take longer than
 *    the grace window, and the prompt itself can push the Activity to the
 *    background, so a naive timer locks the user out right after they proved
 *    who they are;
 *  * **a short hop out and back** — the system file picker, a share sheet or a
 *    permission dialog must not lock the user out mid-task.
 */
object GatePolicy {

    /** How long the app may sit in the background before it re-locks. */
    const val GRACE_MS = 10_000L

    /**
     * Whether a return to the foreground must re-lock the app.
     *
     * @param backgroundedAt when the app was last stopped, on the monotonic
     *   clock; `0` when it has not left the foreground since the process
     *   started.
     * @param lastUnlockAt when the user last passed the prompt; `0` when they
     *   never did in this process.
     * @param now monotonic clock reading of the return.
     * @param gateEnabled the 生物识别门禁 preference.
     * @param alreadyLocked whether the gate is already up — nothing more to do.
     */
    fun shouldRelockOnReturn(
        backgroundedAt: Long,
        lastUnlockAt: Long,
        now: Long,
        gateEnabled: Boolean,
        alreadyLocked: Boolean
    ): Boolean =
        backgroundedAt > 0L &&
            gateEnabled &&
            !alreadyLocked &&
            // A successful unlock always beats the timestamp taken on the way
            // out, so answering the prompt can never trigger a second lock.
            backgroundedAt >= lastUnlockAt &&
            now - backgroundedAt >= GRACE_MS
}
