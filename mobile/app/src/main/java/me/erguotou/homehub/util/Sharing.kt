package me.erguotou.homehub.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hand a local file to whatever app the user picks from the system share sheet.
 *
 * This is the platform's own share mechanism (`ACTION_SEND`), so every receiver
 * registered for the type shows up: 微信 / QQ / 邮件 / 云盘 / 蓝牙 … No app is
 * special-cased and none is required.
 *
 * The file must live under `cache/preview/` (see [TempFiles] and
 * `res/xml/file_paths.xml`) because that is the only path the app's
 * FileProvider exposes; receivers read it through the granted `content://` URI
 * and never need a storage permission.
 */
object Sharing {

    /** `content://` URI for a scratch file, or null if it sits outside the provider. */
    fun uriFor(context: Context, file: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()

    /**
     * Open the share sheet for [file]. Returns false when nothing could take it
     * (no provider entry, or no app registered for [mime]).
     *
     * [mime] must be concrete — a wildcard chooser looks broken to most
     * receivers, which is why callers derive it from the extension via
     * [FileKinds.mimeOf].
     */
    fun share(context: Context, file: File, mime: String, title: String? = null): Boolean {
        val uri = uriFor(context, file) ?: return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            // Some receivers (mail clients, IM) only ever look at ClipData, and
            // it is also what carries the read grant through the chooser.
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { context.startActivity(chooser) }.isSuccess
    }
}
