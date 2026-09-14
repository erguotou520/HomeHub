package me.erguotou.homehub.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth table for the background re-lock rule.
 *
 * Pure logic, no Android: the gate's prompt cannot be answered from a test, so
 * this rule is exactly the part that cannot be driven from adb without a real
 * finger — getting it wrong means locking the user out right after they
 * unlocked, which is what happened before `lastUnlockAt` existed.
 */
class GatePolicyTest {

    /** A typical run: left the foreground at 1000, unlocked earlier at 500. */
    private fun relock(
        backgroundedAt: Long = 1_000L,
        lastUnlockAt: Long = 500L,
        now: Long = backgroundedAt + GatePolicy.GRACE_MS,
        gateEnabled: Boolean = true,
        alreadyLocked: Boolean = false
    ) = GatePolicy.shouldRelockOnReturn(
        backgroundedAt = backgroundedAt,
        lastUnlockAt = lastUnlockAt,
        now = now,
        gateEnabled = gateEnabled,
        alreadyLocked = alreadyLocked
    )

    @Test
    fun `locks once the grace window has passed`() {
        assertTrue(relock(now = 1_000L + GatePolicy.GRACE_MS))
    }

    @Test
    fun `grace boundary is inclusive`() {
        assertFalse(relock(now = 1_000L + GatePolicy.GRACE_MS - 1))
        assertTrue(relock(now = 1_000L + GatePolicy.GRACE_MS))
    }

    @Test
    fun `a short hop out and back keeps the user in`() {
        // The file picker / share sheet / permission dialog case.
        assertFalse(relock(now = 1_000L + 2_000L))
    }

    @Test
    fun `an app that never left the foreground is left alone`() {
        assertFalse(relock(backgroundedAt = 0L, now = 90_000L))
    }

    @Test
    fun `a disabled gate never locks`() {
        assertFalse(relock(gateEnabled = false, now = 1_000L + 60_000L))
    }

    @Test
    fun `an already locked app is not locked again`() {
        assertFalse(relock(alreadyLocked = true, now = 1_000L + 60_000L))
    }

    @Test
    fun `answering the prompt cannot lock the user straight back out`() {
        // The prompt pushed the Activity out at 1000 and the finger only
        // landed at 15000 — well past the grace window, but the user has just
        // proved who they are, so onStart must not lock again.
        assertFalse(
            relock(backgroundedAt = 1_000L, lastUnlockAt = 15_000L, now = 16_000L)
        )
    }

    @Test
    fun `a genuine absence after an earlier unlock still locks`() {
        // Unlocked at 500, left at 2000, back at 20000: nothing happened in
        // between, so this is a real absence.
        assertTrue(
            relock(backgroundedAt = 2_000L, lastUnlockAt = 500L, now = 20_000L)
        )
    }
}
