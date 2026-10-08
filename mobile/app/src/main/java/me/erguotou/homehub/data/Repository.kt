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

    /**
     * One page of the album timeline.
     *
     * [limit] is what switches the server to paged mode — the album always sets
     * it. The whole-library response this replaces is ~700 bytes per photo, so
     * a 4000-photo library meant a 3 MB body: on a slow link that alone blew
     * past the client's 30 s read timeout and the album never painted.
     *
     * [before] is the `(taken_at, id)` cursor from the previous page.
     */
    suspend fun timelinePage(
        group: String = "day",
        kind: String? = null,
        limit: Int = TIMELINE_PAGE,
        before: Pair<Long, Long>? = null
    ): Result<TimelineResponse> = runCatching {
        api().timeline(group, kind, null, limit, before?.first, before?.second)
    }

    suspend fun tree(dirId: Long? = null): Result<List<TreeGroup>> = runCatching {
        api().tree(dirId).groups
    }

    suspend fun tags(): Result<List<TagSummary>> = runCatching { api().tags().tags }

    suspend fun people(): Result<List<PersonGroup>> = runCatching { api().people().people }

    suspend fun geo(precision: Double = 0.02): Result<List<GeoPoint>> = runCatching {
        api().geo(precision).points
    }

    /**
     * Place name for map coordinates ("北京市 东城区 …"), resolved and
     * cached by the server. Null label means the provider failed — callers
     * keep the coordinate fallback.
     */
    suspend fun geoReverse(lat: Double, lng: Double): Result<String?> = runCatching {
        api().geoReverse(lat, lng).label
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

    /** Natural-language semantic photo search (Chinese-CLIP). */
    suspend fun semantic(query: String, limit: Int = 60): Result<SemanticResponse> =
        runCatching { api().semantic(query = query, limit = limit) }

    suspend fun photoDetail(id: Long): Result<PhotoDetail> = runCatching { api().photoDetail(id) }

    // ── file-level image editing ──
    // These take the photo's dir name + dir-relative path, matching every
    // other per-file endpoint (media, documents, files PATCH/DELETE).

    /** Apply rotate/flip steps to a photo's bytes on the server. */
    suspend fun transformImage(photo: PhotoItem, ops: List<ImageOp>): Result<TransformResponse> =
        transformFile(photo.dirName, photo.relPath, ops)

    /** Roll the pixels back to the archived pre-edit original. */
    suspend fun restoreImage(photo: PhotoItem): Result<TransformResponse> =
        restoreFile(photo.dirName, photo.relPath)

    suspend fun imageExif(photo: PhotoItem): Result<ImageExif> =
        imageExifAt(photo.dirName, photo.relPath)

    // The same endpoints address *any* image in a registered directory, so the
    // 文件 tab's viewer edits files that were never indexed as album photos.

    suspend fun transformFile(
        dir: String,
        path: String,
        ops: List<ImageOp>
    ): Result<TransformResponse> = runCatching {
        api().transform(dir, path, TransformRequest(ops))
    }

    suspend fun restoreFile(dir: String, path: String): Result<TransformResponse> = runCatching {
        api().restoreImage(dir, path)
    }

    suspend fun imageExifAt(dir: String, path: String): Result<ImageExif> = runCatching {
        api().imageExif(dir, path)
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

    companion object {
        /**
         * Timeline page size.
         *
         * A timeline item serialises to roughly 700 bytes, so 120 photos is
         * ~80 KB per page: about a second on the ~70 KB/s the WireGuard tunnel
         * measured, and ~40 grid rows to scroll before the next page is wanted.
         */
        const val TIMELINE_PAGE = 120
    }
}
