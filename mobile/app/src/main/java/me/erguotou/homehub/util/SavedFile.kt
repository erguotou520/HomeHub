package me.erguotou.homehub.util

import android.net.Uri
import android.provider.DocumentsContract

/**
 * Where the system's "save as" dialog just put a file, in words a file manager
 * would use ("内部存储/Download").
 *
 * The picker hands back only an opaque `content://` URI and gives no receipt of
 * its own, so the document id is the sole trace of the destination. Only the
 * folder is reported: the user just picked the name, so repeating it back adds
 * noise.
 *
 * Best effort by design — any provider may be on the other end (ColorOS routes
 * the picker through its own file manager), so an unrecognised shape yields
 * null and the caller says a plain "已保存" rather than showing a URI nobody
 * can act on.
 */
object SavedFile {

    /** "内部存储/Download" / "SD 卡(1A2B)/DCIM" / "下载" — or null when unknown. */
    fun location(uri: Uri): String? {
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        // com.android.externalstorage.documents: "primary:Download/a.jpg" or
        // "1A2B-3C4D:DCIM/Camera/a.jpg". The raw-filesystem provider instead
        // gives an absolute path: "raw:/storage/emulated/0/Download/a.jpg".
        if (id.startsWith("raw:")) return fromAbsolute(id.removePrefix("raw:"))
        if (!id.contains(':')) return null
        val volume = id.substringBefore(':')
        val relative = id.substringAfter(':')
        // com.android.providers.downloads.documents: "msf:1234" is an opaque
        // row id, not a path — the folder is by definition the downloads one.
        if (volume.equals("msf", true) || volume.equals("downloads", true)) return "下载"
        val root = if (volume.equals("primary", true)) "内部存储" else "SD 卡($volume)"
        val dir = parentOf(relative)
        return if (dir.isBlank()) root else "$root/$dir"
    }

    /** "/storage/emulated/0/Download/a.jpg" → "内部存储/Download" */
    private fun fromAbsolute(path: String): String? {
        val trimmed = path.removePrefix("/storage/")
        if (trimmed == path) return null      // some other mount point
        val volume = trimmed.substringBefore('/')
        val rest = trimmed.substringAfter('/', "")
        val root = if (volume == "emulated") "内部存储" else "SD 卡($volume)"
        // "emulated" is followed by the user id ("0") before the real path.
        val afterUser = if (volume == "emulated") rest.substringAfter('/', "") else rest
        val dir = parentOf(afterUser)
        return if (dir.isBlank()) root else "$root/$dir"
    }

    private fun parentOf(path: String): String =
        path.substringBeforeLast('/', "").trim('/')
}
