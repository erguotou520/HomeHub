package me.erguotou.homehub.work

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns

/**
 * 把「选择文件」拿到的 URI 换算成 MediaStore 的 item URI。
 *
 * MediaStore.createDeleteRequest() 只接受 MediaStore URI（content://media/...），
 * 而 ACTION_GET_CONTENT 返回的是 SAF document URI 或别家 provider 的 URI。
 * 所以选择时在这里把 URI 解析成可删除的 MediaStore URI 交给上传链路；
 * 真正的删除由 MainActivity 拿这个 URI 弹系统确认框完成，不需要本应用有写权限。
 *
 * 注意：查询 DISPLAY_NAME / OpenableColumns 需要 URI 的临时读授权，
 * 因此必须在选择回调里（前台、授权仍有效时）调用，不能丢到后台 Worker 里做。
 */
object DeleteTargetResolver {

    /**
     * @return MediaStore item URI 的字符串；无法定位到 MediaStore 条目时返回 null。
     */
    fun resolve(context: Context, uri: Uri): String? = try {
        when (uri.authority) {
            // 已经是 MediaStore item URI（content://media/external/images/media/123）
            MediaStore.AUTHORITY -> uri.toString()

            // SAF 的媒体 document：content://com.android.providers.media.documents/document/image%3A123
            "com.android.providers.media.documents" -> {
                val docId = DocumentsContract.getDocumentId(uri)
                val pieces = docId.split(':', limit = 2)
                if (pieces.size != 2) null else {
                    val id = pieces[1].toLongOrNull()
                    if (id == null) null else {
                        val base = when (pieces[0]) {
                            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            else -> MediaStore.Files.getContentUri("external")
                        }
                        ContentUris.withAppendedId(base, id).toString()
                    }
                }
            }

            // 别家 provider（下载管理器、第三方相册等）：按显示名在 MediaStore 里找同名条目。
            else -> resolveByName(context, uri)
        }
    } catch (e: Exception) {
        null
    }

    private fun resolveByName(context: Context, uri: Uri): String? {
        val name = displayNameOf(context, uri) ?: return null
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            arrayOf(name),
            null
        )?.use { cursor ->
            val idx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            if (cursor.moveToFirst()) {
                return ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri("external"), cursor.getLong(idx)
                ).toString()
            }
        }
        return null
    }

    private fun displayNameOf(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) return cursor.getString(idx)
        }
        return null
    }
}
