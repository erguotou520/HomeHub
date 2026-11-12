package me.erguotou.homehub.data

import android.content.Context
import java.security.MessageDigest

/**
 * Where each video got to, on disk.
 *
 * The viewer already remembers an audio track's position in memory while it
 * is open ([me.erguotou.homehub.ui.components.AudioPlaybackMemory]), but that
 * dies with the process: every trip back to the home screen and into a video
 * again started from 0:00. This is the disk half — written every few seconds
 * while a video plays and once when the window goes away, read back when the
 * same file is reopened, and dropped the moment the file runs to its end.
 *
 * Plain `SharedPreferences`, not [Prefs]: a playback position is not a secret
 * and the encrypted store belongs to credentials. Entries nobody came back to
 * are swept at startup ([sweep]), so a long-lived install does not accumulate
 * one row per video ever opened.
 */
object PlaybackPositions {

    private const val FILE = "playback_positions"

    /** A position untouched for this long is no longer worth resuming. */
    const val STALE_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** Stored as `position:lastTouched`, both epoch/ms. */
    private const val SEPARATOR = ':'

    fun get(context: Context, url: String): Long? {
        val key = keyOf(url)
        val raw = store(context).getString(key, null) ?: return null
        val position = raw.substringBefore(SEPARATOR).toLongOrNull() ?: return null
        val touchedAt = raw.substringAfter(SEPARATOR, "").toLongOrNull() ?: 0L
        if (position <= 0L) return null
        if (System.currentTimeMillis() - touchedAt > STALE_MS) {
            // Expired; sweep would get it eventually, this just avoids
            // resuming a month-old position in the meantime.
            store(context).edit().remove(key).apply()
            return null
        }
        return position
    }

    fun save(context: Context, url: String, positionMs: Long) {
        if (positionMs <= 0L) return
        store(context).edit()
            .putString(keyOf(url), "$positionMs$SEPARATOR${System.currentTimeMillis()}")
            .apply()
    }

    fun clear(context: Context, url: String) {
        store(context).edit().remove(keyOf(url)).apply()
    }

    /** Drop entries that have not been touched in [maxAgeMs]. */
    fun sweep(context: Context, maxAgeMs: Long = STALE_MS) {
        val prefs = store(context)
        val cutoff = System.currentTimeMillis() - maxAgeMs
        val stale = prefs.all.keys.filter { key ->
            val touchedAt = (prefs.all[key] as? String)
                ?.substringAfter(SEPARATOR, "")
                ?.toLongOrNull()
                ?: 0L
            touchedAt < cutoff
        }
        if (stale.isEmpty()) return
        prefs.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    /**
     * A file URL can be several hundred characters; its hash keeps the key
     * short and stable. This is a lookup key, not a security boundary.
     */
    private fun keyOf(url: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(20)

    private fun store(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
