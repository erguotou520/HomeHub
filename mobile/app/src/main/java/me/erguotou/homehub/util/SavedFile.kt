package me.erguotou.homehub.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

/**
 * Human-readable location of a file the system "save as" dialog just wrote.
 *
 * The picker hands back an opaque `content://` URI, which says nothing to a
 * user who wants to know *where* their photo went. This turns the document id
 * back into the wording a file manager would use ("内部存储/Download/xxx.jpg").
 *
 * Best effort by design: any provider may be on the other end (ColorOS routes
 * the picker through its own file manager), so an unrecognised shape falls back
 * to the display name alone instead of printing a URI nobody can act on.
 */
object SavedFile {

    fun describe(context: Context, uri: Uri): String {
        val name = displayName(context, uri)?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment.orEmpty()
        val folder = folder(uri)
        return if (folder.isNullOrBlank()) name else "$folder/$name"
    }

    private fun folder(uri: Uri): String? {
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        // com.android.externalstorage.documents: "primary:Download/a.jpg" or
        // "1A2B-3C4D:DCIM/Camera/a.jpg". The raw-filesystem provider instead
        // gives an absolute path: "raw:/storage/emulated/0/Download/a.jpg".
        if (id.startsWith("raw:")) return folderOfAbsolute(id.removePrefix("raw:"))
        if (!id.contains(':')) return null
        val volume = id.substringBefore(':')
        val relative = id.substringAfter(':')
        // com.android.providers.downloads.documents: "msf:1234" is an opaque
        // row id, not a path — the directory is by definition 下载.
        if (volume.equals("msf", true) || volume.equals("downloads", true)) return "下载"
        val root = if (volume.equals("primary", true)) "内部存储" else "SD 卡($volume)"
        val dir = parentOf(relative)
        return if (dir.isBlank()) root else "$root/$dir"
    }

    /** "/storage/emulated/0/Download/a.jpg" → "内部存储/Download" */
    private fun folderOfAbsolute(path: String): String {
        val trimmed = path.removePrefix("/storage/")
        if (trimmed == path) return parentOf(path)      // some other mount point
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

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
}
