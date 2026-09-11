package me.erguotou.homehub.util

import android.content.Context
import java.io.File

/**
 * Scratch space for viewers that need real bytes on disk.
 *
 * The server streams every file over the WireGuard tunnel, so nothing is
 * downloaded except what genuinely requires a file: PDF/Office parsing and the
 * "用其他应用打开" hand-off for apps that only accept `file:`/`content:` URIs.
 * Both land under `cacheDir/preview/`, which is our own cache folder — never
 * the shared Downloads — and is cleaned two ways:
 *
 *  * `viewer/` holds copies made by the in-app viewer. They are deleted the
 *    moment the viewer closes, because nothing outside this app ever saw them.
 *  * `handoff/` holds copies handed to another app. Those are only reclaimed by
 *    age ([sweep], six hours), so closing the viewer can never pull the file
 *    out from under the receiving app.
 */
object TempFiles {

    private const val ROOT = "preview"
    private const val VIEWER = "viewer"
    private const val HANDOFF = "handoff"

    /** Age after which a hand-off copy is considered abandoned. */
    const val STALE_MS: Long = 6 * 60 * 60 * 1000L

    /** Root of the scratch area (the FileProvider exposes exactly this). */
    fun dir(context: Context): File = File(context.cacheDir, ROOT).apply { mkdirs() }

    private fun folder(context: Context, name: String): File =
        File(dir(context), name).apply { mkdirs() }

    /** Copy consumed by the in-app viewer (PDF / Office parsing). */
    fun writeViewer(context: Context, name: String, bytes: ByteArray): File =
        write(folder(context, VIEWER), name, bytes)

    /** Copy handed to another app via `ACTION_VIEW`. */
    fun writeHandoff(context: Context, name: String, bytes: ByteArray): File =
        write(folder(context, HANDOFF), name, bytes)

    /** Drop every copy the in-app viewer created. */
    fun clearViewer(context: Context) {
        runCatching { folder(context, VIEWER).listFiles()?.forEach { it.delete() } }
    }

    /** Remove scratch files (both kinds) that have not been touched recently. */
    fun sweep(context: Context, maxAgeMs: Long = STALE_MS) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        runCatching {
            listOf(folder(context, VIEWER), folder(context, HANDOFF)).forEach { f ->
                f.listFiles()?.forEach { child ->
                    if (child.isFile && child.lastModified() < cutoff) child.delete()
                }
            }
        }
    }

    /** Write [bytes] into [into] under an unused name; returns the file. */
    private fun write(into: File, name: String, bytes: ByteArray): File =
        target(into, name).apply { writeBytes(bytes) }

    /** Reserve an unused path for [name]; collisions get a numeric suffix. */
    private fun target(folder: File, name: String): File {
        val safe = sanitize(name)
        val base = safe.substringBeforeLast('.', safe)
        val ext = safe.substringAfterLast('.', "")
        var candidate = File(folder, safe)
        var n = 1
        while (candidate.exists()) {
            candidate = File(folder, if (ext.isEmpty()) "$base-$n" else "$base-$n.$ext")
            n++
        }
        return candidate
    }

    /**
     * Strip path separators and characters that trip some viewers; keep CJK
     * so the receiving app shows a recognisable name.
     */
    private fun sanitize(name: String): String {
        val cleaned = name
            .replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]+"), "_")
            .trim()
            .trim('.')
            .take(96)
        return cleaned.ifBlank { "file" }
    }
}
