package me.erguotou.homehub.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.erguotou.homehub.R
import me.erguotou.homehub.data.Prefs
import me.erguotou.homehub.data.Repository

/**
 * Background uploader.
 *
 * Files are read in chunks and sent one chunk at a time so an interrupted
 * upload can resume from the last acknowledged chunk. The server appends
 * chunks to `<name>.part` and renames on the final chunk.
 */
class UploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val repository by lazy { Repository(appContext) }
    private val prefs by lazy { Prefs(appContext) }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dir = inputData.getString(KEY_DIR).orEmpty()
        val path = inputData.getString(KEY_PATH).orEmpty()
        val uris = inputData.getStringArray(KEY_URIS)?.map { Uri.parse(it) }.orEmpty()
        val deleteLocal = inputData.getBoolean(KEY_DELETE_LOCAL, false)

        if (dir.isBlank() || uris.isEmpty()) return@withContext Result.failure()

        var uploaded = 0
        var duplicates = 0
        var failures = 0

        uris.forEachIndexed { index, uri ->
            if (isStopped) return@withContext Result.success(workDataOf(uploaded, duplicates, failures))

            setForeground(createForegroundInfo(index + 1, uris.size))
            setProgressAsync(
                Data.Builder()
                    .putInt(KEY_PROGRESS_INDEX, index + 1)
                    .putInt(KEY_PROGRESS_TOTAL, uris.size)
                    .build()
            )

            val fileName = fileNameOf(uri)
            val bytes = readAll(uri)
            if (bytes == null) {
                failures++
                return@forEachIndexed
            }

            // Chunked upload: resume from the server-side offset.
            var offset = repository.uploadOffset(dir, path, fileName).getOrNull() ?: 0L
            var lastError: Throwable? = null
            while (offset < bytes.size) {
                if (isStopped) return@withContext Result.success(workDataOf(uploaded, duplicates, failures))
                val end = minOf(offset + CHUNK_SIZE, bytes.size.toLong()).toInt()
                val chunk = bytes.copyOfRange(offset.toInt(), end)
                val result = repository.uploadChunk(
                    dir = dir,
                    path = path,
                    fileName = fileName,
                    chunk = chunk,
                    offset = offset,
                    total = bytes.size.toLong()
                )
                result.fold(
                    onSuccess = { offset = end.toLong() },
                    onFailure = { lastError = it }
                )
                if (lastError != null) break
            }

            if (lastError != null) {
                failures++
            } else {
                val policy = prefs.duplicatePolicy.ifBlank { "skip" }
                val response = repository.uploadComplete(dir, path, fileName, bytes.size.toLong(), policy)
                val outcome = response.getOrNull()?.uploaded?.firstOrNull()
                when {
                    outcome?.skipped == true -> {
                        // Server discarded the duplicate upload; keep the local file.
                        duplicates++
                    }
                    outcome?.duplicateOf != null -> {
                        duplicates++
                        uploaded++
                        if (deleteLocal) deleteLocal(uri)
                    }
                    outcome != null -> {
                        uploaded++
                        if (deleteLocal) deleteLocal(uri)
                    }
                    else -> failures++
                }
            }
        }

        notifyDone(uploaded, duplicates, failures)
        Result.success(workDataOf(uploaded, duplicates, failures))
    }

    private fun workDataOf(uploaded: Int, duplicates: Int, failures: Int) = Data.Builder()
        .putInt(KEY_RESULT_UPLOADED, uploaded)
        .putInt(KEY_RESULT_DUPLICATES, duplicates)
        .putInt(KEY_RESULT_FAILURES, failures)
        .build()

    private fun fileNameOf(uri: Uri): String {
        val resolver = applicationContext.contentResolver
        if (uri.scheme == "content") {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment ?: "upload.bin"
    }

    private fun readAll(uri: Uri): ByteArray? = try {
        applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    private fun deleteLocal(uri: Uri) {
        try {
            if (uri.scheme == "content") {
                applicationContext.contentResolver.delete(uri, null, null)
            }
        } catch (e: Exception) {
            // Best effort only: the user may not have granted the delete.
        }
    }

    private fun createForegroundInfo(current: Int, total: Int): ForegroundInfo {
        ensureChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_upload)
            .setContentTitle("正在上传照片")
            .setContentText("$current / $total")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, current, false)
            .build()
        // targetSdk 34+: WorkManager requires an explicit FGS type, otherwise
        // setForeground throws MissingForegroundServiceTypeException and the
        // app crashes the moment an upload starts.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyDone(uploaded: Int, duplicates: Int, failures: Int) {
        ensureChannel()
        val text = "完成 $uploaded 项" +
            (if (duplicates > 0) " · 重复 $duplicates 项" else "") +
            (if (failures > 0) " · 失败 $failures 项" else "")
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_upload)
            .setContentTitle("上传结束")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "上传", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    companion object {
        private const val CHUNK_SIZE = 4 * 1024 * 1024
        private const val CHANNEL_ID = "homehub_upload"
        private const val NOTIFICATION_ID = 2001

        const val KEY_DIR = "dir"
        const val KEY_PATH = "path"
        const val KEY_URIS = "uris"
        const val KEY_DELETE_LOCAL = "delete_local"
        const val KEY_PROGRESS_INDEX = "progress_index"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_RESULT_UPLOADED = "uploaded"
        const val KEY_RESULT_DUPLICATES = "duplicates"
        const val KEY_RESULT_FAILURES = "failures"

        fun enqueue(context: Context, dir: String, path: String, uris: List<Uri>, deleteLocal: Boolean) {
            if (uris.isEmpty()) return
            val data = Data.Builder()
                .putString(KEY_DIR, dir)
                .putString(KEY_PATH, path)
                .putStringArray(KEY_URIS, uris.map { it.toString() }.toTypedArray())
                .putBoolean(KEY_DELETE_LOCAL, deleteLocal)
                .build()
            val request = androidx.work.OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(data)
                .addTag("upload")
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
