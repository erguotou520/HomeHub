package me.erguotou.homehub.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Thin repository around [ApiService]: resolves media URLs, turns exceptions
 * into [Result] and keeps the UI free of Retrofit details.
 */
class Repository(private val context: Context) {

    private val prefs by lazy { Prefs(context) }

    private var cachedClientKey = ""
    private var cachedApi: ApiService? = null

    fun prefs(): Prefs = prefs

    /** (Re)build the API client; call again after settings change. */
    fun api(): ApiService {
        val key = "${prefs.baseUrl()}|${prefs.trustCustomCert}|${prefs.serverCertPem}"
        val cached = cachedApi
        if (cached != null && key == cachedClientKey) return cached
        val client = HttpClientFactory.create(prefs, verbose = false)
        val api = HttpClientFactory.retrofit(client, prefs.baseUrl())
            .create(ApiService::class.java)
        cachedApi = api
        cachedClientKey = key
        return api
    }

    fun invalidate() {
        cachedApi = null
        cachedClientKey = ""
    }

    fun absolute(url: String): String {
        if (url.startsWith("http")) return url
        val base = prefs.baseUrl().trimEnd('/')
        val path = if (url.startsWith("/")) url else "/$url"
        return base + path
    }

    // ──────────────────────────── album ────────────────────────────

    suspend fun timeline(
        group: String = "day",
        kind: String? = null
    ): Result<List<TimelineGroup>> = runCatching {
        api().timeline(group, kind).groups
    }

    suspend fun tree(dirId: Long? = null): Result<List<TreeGroup>> = runCatching {
        api().tree(dirId).groups
    }

    suspend fun tags(): Result<List<TagSummary>> = runCatching { api().tags().tags }

    suspend fun people(): Result<List<PersonGroup>> = runCatching { api().people().people }

    suspend fun geo(precision: Double = 0.02): Result<List<GeoPoint>> = runCatching {
        api().geo(precision).points
    }

    suspend fun photos(
        dirId: Long? = null,
        tag: String? = null,
        personId: Long? = null,
        from: Long? = null,
        to: Long? = null,
        /** "photo" | "video" media filter. */
        kind: String? = null,
        /** Explicit ids — used when drilling into one geo cluster on the map. */
        ids: List<Long>? = null
    ): Result<List<PhotoItem>> = runCatching {
        api().list(dirId, tag, personId, from, to, null, kind, ids?.joinToString(",")).items
    }

    /** Range-streaming URL for video playback (server resolves the path). */
    fun mediaUrl(id: Long): String = absolute("api/photos/$id/raw")

    suspend fun photoDetail(id: Long): Result<PhotoDetail> = runCatching { api().photoDetail(id) }

    suspend fun rotate(id: Long, angle: Int): Result<PhotoItem> = runCatching {
        api().rotate(id, RotateRequest(angle)).photo
    }

    // ── file-level image editing ──
    // These take the photo's dir name + dir-relative path, matching every
    // other per-file endpoint (media, documents, files PATCH/DELETE).

    /** Apply rotate/flip/resize steps to a photo's bytes on the server. */
    suspend fun transformImage(photo: PhotoItem, ops: List<ImageOp>): Result<TransformResponse> =
        runCatching {
            api().transform(photo.dirName, photo.relPath, TransformRequest(ops))
        }

    /** Roll the pixels back to the archived pre-edit original. */
    suspend fun restoreImage(photo: PhotoItem): Result<TransformResponse> = runCatching {
        api().restoreImage(photo.dirName, photo.relPath)
    }

    suspend fun imageExif(photo: PhotoItem): Result<ImageExif> = runCatching {
        api().imageExif(photo.dirName, photo.relPath)
    }

    suspend fun renamePerson(id: Long, name: String): Result<Unit> = runCatching {
        api().renamePerson(id, mapOf("name" to name))
    }

    suspend fun search(q: String, type: String? = null): Result<List<SearchHit>> = runCatching {
        api().search(q, type).hits
    }

    suspend fun duplicates(): Result<List<DuplicateGroup>> = runCatching { api().duplicates().groups }

    // ──────────────────────────── files ────────────────────────────

    suspend fun dirs(): Result<List<DirEntry>> = runCatching { api().dirs().entries }

    suspend fun listFiles(
        dir: String,
        path: String,
        sort: String = "name",
        desc: Boolean = false
    ): Result<List<FileEntry>> = runCatching {
        if (path.isBlank()) api().listRoot(dir, sort, desc).entries
        else api().listPath(dir, path, sort, desc).entries
    }

    suspend fun mkdir(dir: String, path: String, name: String): Result<Unit> = runCatching {
        if (path.isBlank()) api().mkdirRoot(dir, RenameRequest(name))
        else api().mkdir(dir, path, RenameRequest(name))
        Unit
    }

    suspend fun rename(dir: String, path: String, newName: String): Result<Unit> = runCatching {
        api().rename(dir, path, RenameRequest(newName))
        Unit
    }

    suspend fun copyOrMove(dir: String, path: String, op: String, toDir: String, toPath: String) =
        runCatching {
            api().copyOrMove(dir, path, CopyMoveRequest(op, toDir, toPath))
            Unit
        }

    suspend fun delete(dir: String, path: String): Result<Unit> = runCatching {
        api().delete(dir, path)
        Unit
    }

    suspend fun readDocument(dir: String, path: String): Result<String> = runCatching {
        api().readDocument(dir, path).content
    }

    /** Download a file's bytes (for the SAF "下载" action). */
    suspend fun download(dir: String, path: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = api().download(dir, path)
                if (resp.isSuccessful) {
                    resp.body()?.bytes() ?: throw IllegalStateException("empty body")
                } else {
                    throw IllegalStateException("HTTP ${resp.code()}")
                }
            }
        }

    /** Upload a single file (used by the WorkManager uploader). */
    suspend fun upload(
        dir: String,
        path: String,
        fileName: String,
        bytes: ByteArray,
        mimeType: String = "application/octet-stream"
    ): Result<UploadResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val body = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", fileName, body)
            if (path.isBlank()) api().uploadRoot(dir, part)
            else api().uploadPath(dir, path, part)
        }
    }

    // ──────────────────── resumable chunked upload ─────────────────

    suspend fun uploadOffset(dir: String, path: String, fileName: String): Result<Long> =
        runCatching { api().uploadOffset(dir, path, fileName).offset }

    suspend fun uploadChunk(
        dir: String,
        path: String,
        fileName: String,
        chunk: ByteArray,
        offset: Long,
        total: Long
    ): Result<Unit> = runCatching {
        val body = chunk.toRequestBody("application/octet-stream".toMediaTypeOrNull())
        api().uploadChunk(dir, path, fileName, offset, total, body)
        Unit
    }

    suspend fun uploadComplete(dir: String, path: String, fileName: String, total: Long, onDuplicate: String = "keep") =
        runCatching {
            api().uploadComplete(UploadCompleteRequest(dir, path, fileName, total, onDuplicate))
        }

    // ──────────────────────────── trash ────────────────────────────

    suspend fun trash(): Result<List<TrashEntry>> = runCatching { api().trash().entries }
    suspend fun trashStats(): Result<TrashStats> = runCatching { api().trashStats() }
    suspend fun restoreTrash(id: Long): Result<Unit> = runCatching { api().restoreTrash(id); Unit }
    suspend fun purgeTrash(id: Long): Result<Unit> = runCatching { api().purgeTrash(id); Unit }
    suspend fun emptyTrash(): Result<Unit> = runCatching { api().emptyTrash(); Unit }

    // ──────────────────────────── monitor ──────────────────────────

    suspend fun monitorConfig(): Result<MonitorConfig> = runCatching { api().monitorConfig() }

    suspend fun saveMonitorConfig(url: String): Result<Unit> = runCatching {
        api().saveMonitorConfig(mapOf("frigate_url" to url))
        Unit
    }

    suspend fun health(): Result<Health> = runCatching { api().health() }
}
