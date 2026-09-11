package me.erguotou.homehub.ui.screens.files

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import me.erguotou.homehub.data.DirEntry
import me.erguotou.homehub.data.FileEntry
import me.erguotou.homehub.data.ImageOp
import me.erguotou.homehub.data.Repository
import me.erguotou.homehub.util.FileKind
import me.erguotou.homehub.util.FileKinds
import me.erguotou.homehub.util.TempFiles
import me.erguotou.homehub.work.UploadWorker

/** The viewer (or the system hand-off) a file should be opened with. */
fun kindOf(entry: FileEntry): FileKind =
    FileKinds.of(entry.name, entry.mimeType, entry.mediaKind)

/**
 * One page of the full screen viewer: a file plus the `<dir>/<rel-path>`
 * address every file endpoint is keyed by.
 */
data class ViewerItem(
    val dir: String,
    val relPath: String,
    val entry: FileEntry,
    /**
     * Set when the viewer was opened with a kind worked out at open time rather
     * than from the name — an unknown extension we just proved to be text.
     */
    val overrideKind: FileKind? = null
) {
    val name: String get() = entry.name
    val kind: FileKind get() = overrideKind ?: kindOf(entry)
}

/**
 * Snapshot handed to the viewer: the openable siblings of one directory, in
 * listing order, so swiping moves through the folder like the album pager.
 */
data class ViewerSession(
    val items: List<ViewerItem>,
    val index: Int
)

data class FilesUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val dirs: List<DirEntry> = emptyList(),
    val currentDir: String? = null,
    val currentPath: String = "",
    val entries: List<FileEntry> = emptyList(),
    val sort: String = "name",
    val desc: Boolean = false,
    val selected: Set<String> = emptySet(),
    /** Open full screen viewer, or null. */
    val viewer: ViewerSession? = null,
    /** An upload / delete / copy / external hand-off is in flight. */
    val busy: Boolean = false,
    /** One-shot status line surfaced as a snackbar. */
    val message: String? = null,
    /**
     * Bumped after a server-side image edit. Raw URLs carry it as `?v=`, the
     * only way to make Coil/ExoPlayer forget the pre-edit bytes.
     */
    val version: Long = 0,
    /** A background upload (WorkManager tag "upload") is running. */
    val uploadActive: Boolean = false,
    /** Progress of that upload: file `uploadIndex` of `uploadTotal`. */
    val uploadIndex: Int = 0,
    val uploadTotal: Int = 0
)

class FilesViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = Repository(app)

    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.dirs().onSuccess { list ->
                _state.value = _state.value.copy(dirs = list)
                list.firstOrNull()?.let { open(it.name, "") }
            }.onFailure { e ->
                _state.value = _state.value.copy(error = e.message)
            }
        }
        observeUploads()
    }

    /**
     * The uploader runs in WorkManager, out of this screen's lifetime. Mirror
     * its per-file progress into the UI, and when the last worker finishes,
     * refresh the listing so freshly uploaded files show up without the user
     * pulling the folder open again.
     */
    private fun observeUploads() {
        viewModelScope.launch {
            WorkManager.getInstance(getApplication())
                .getWorkInfosByTagFlow("upload")
                .collect { infos ->
                    val active = infos.filter { !it.state.isFinished }
                    val running = active.firstOrNull()
                    val progress = running?.progress
                    val wasActive = _state.value.uploadActive
                    _state.value = _state.value.copy(
                        uploadActive = active.isNotEmpty(),
                        uploadIndex = progress?.getInt(UploadWorker.KEY_PROGRESS_INDEX, 0) ?: 0,
                        uploadTotal = progress?.getInt(UploadWorker.KEY_PROGRESS_TOTAL, 0) ?: 0
                    )
                    if (wasActive && active.isEmpty()) {
                        val done = infos.firstOrNull { it.state == WorkInfo.State.SUCCEEDED }
                        val out = done?.outputData
                        val uploaded = out?.getInt(UploadWorker.KEY_RESULT_UPLOADED, 0) ?: 0
                        val dup = out?.getInt(UploadWorker.KEY_RESULT_DUPLICATES, 0) ?: 0
                        val failed = out?.getInt(UploadWorker.KEY_RESULT_FAILURES, 0) ?: 0
                        _state.value = _state.value.copy(
                            message = "上传完成 $uploaded 项" +
                                (if (dup > 0) " · 重复 $dup" else "") +
                                (if (failed > 0) " · 失败 $failed" else "")
                        )
                        // Re-list whatever folder is on screen now.
                        _state.value.currentDir?.let { d ->
                            open(d, _state.value.currentPath)
                        }
                    }
                }
        }
    }

    fun open(dir: String, path: String) {
        _state.value = _state.value.copy(
            loading = true,
            error = null,
            currentDir = dir,
            currentPath = path,
            selected = emptySet()
        )
        viewModelScope.launch {
            repository.listFiles(dir, path, _state.value.sort, _state.value.desc)
                .fold(
                    onSuccess = { _state.value = _state.value.copy(loading = false, entries = it) },
                    onFailure = { e ->
                        _state.value = _state.value.copy(loading = false, error = e.message)
                    }
                )
        }
    }

    /** Relative path of an entry (the server addresses files by `<dir>/<rel>`). */
    private fun relPath(entry: FileEntry): String = relOf(_state.value.currentPath, entry.name)

    private fun relOf(base: String, name: String): String {
        val trimmed = base.trimEnd('/')
        return if (trimmed.isEmpty()) name else "$trimmed/$name"
    }

    /** URL to the raw file bytes (images, video/audio streaming, downloads). */
    fun fileUrl(entry: FileEntry): String {
        val dir = _state.value.currentDir ?: return ""
        return rawUrl(dir, relPath(entry))
    }

    /** Raw URL of a viewer page, tagged with the edit version. */
    fun urlFor(item: ViewerItem): String = rawUrl(item.dir, item.relPath)

    private fun rawUrl(dir: String, relPath: String): String {
        val base = repository.absolute("/api/files/$dir/$relPath")
        val version = _state.value.version
        return if (version == 0L) base else "$base?v=$version"
    }

    // ─────────────────────────── opening ───────────────────────────

    /**
     * Handle a tap on a row: directories drill in, viewable files open the
     * full screen viewer, everything else goes to the system.
     */
    fun navigateTo(entry: FileEntry) {
        if (entry.isDir) {
            open(_state.value.currentDir ?: return, relPath(entry))
            return
        }
        when (kindOf(entry)) {
            FileKind.OTHER -> openUnknown(entry)
            else -> openViewer(entry)
        }
    }

    /**
     * An extension we do not recognise is not automatically a binary: `*.dat`,
     * `*.bak`, a bare `notes` or a `.conf` on a NAS are plain text more often
     * than not. Take one look before giving up — read the head, and if it
     * decodes cleanly open the same text reader `*.txt` gets. Anything else
     * still goes to the system app, exactly as before.
     */
    private fun openUnknown(entry: FileEntry) {
        val dir = _state.value.currentDir ?: return
        val rel = relPath(entry)
        // The server's /api/documents preview stops at 1 MiB, so a bigger file
        // could never be shown in full. Don't spend a request on it.
        if ((entry.size ?: 0) > MaxTextPreviewBytes) {
            openExternally(entry)
            return
        }
        viewModelScope.launch {
            val text = readDocument(dir, rel)
            if (text != null &&
                _state.value.currentDir == dir &&
                !FileKinds.looksBinary(text) &&
                text.isNotBlank()
            ) {
                openViewer(entry, textRead = true)
            } else {
                openExternally(entry)
            }
        }
    }

    /**
     * Open the full screen viewer on [entry], paging over its siblings. When
     * [textRead] is set the entry joins the pager as a text page even though
     * its extension would normally rule it out — see [openUnknown].
     */
    fun openViewer(entry: FileEntry, textRead: Boolean = false) {
        val dir = _state.value.currentDir ?: return
        val base = _state.value.currentPath
        val items = _state.value.entries
            .filter { !it.isDir && (kindOf(it).viewable || (textRead && it.path == entry.path)) }
            .map { e ->
                ViewerItem(
                    dir = dir,
                    relPath = relOf(base, e.name),
                    entry = e,
                    overrideKind = if (textRead && e.path == entry.path) FileKind.TEXT else null
                )
            }
        if (items.isEmpty()) return
        val index = items.indexOfFirst { it.entry.path == entry.path }.coerceAtLeast(0)
        _state.value = _state.value.copy(viewer = ViewerSession(items, index))
    }

    fun dismissViewer() {
        textCache.clear()
        _state.value = _state.value.copy(viewer = null)
        // The viewer's own scratch copies (PDF/Office) are nobody else's
        // business — drop them now. Hand-off copies are aged out instead.
        TempFiles.clearViewer(getApplication())
    }

    suspend fun loadText(item: ViewerItem): String? =
        readDocument(item.dir, item.relPath)

    /**
     * `/api/documents` body, memoised per path: a probed file is fetched once to
     * decide it is text and again to render it, which this collapses into one.
     */
    private val textCache = mutableMapOf<String, String>()

    private suspend fun readDocument(dir: String, rel: String): String? {
        val key = "$dir/$rel"
        textCache[key]?.let { return it }
        val text = repository.readDocument(dir, rel).getOrNull() ?: return null
        textCache[key] = text
        return text
    }

    /**
     * Bytes for the parses that need random access (PDF, Office). Bounded by
     * the listing size so a huge file cannot OOM the viewer.
     */
    suspend fun loadBytes(item: ViewerItem, maxBytes: Long = 96L * 1024 * 1024): ByteArray? {
        val size = item.entry.size ?: 0
        if (size > maxBytes) return null
        return repository.download(item.dir, item.relPath).getOrNull()
    }

    // ───────────────── serving to another app ─────────────────

    /**
     * Hand the file to whatever app claims its type.
     *
     * Office viewers, WPS, archives and OEM players only accept `file:` /
     * `content:` URIs, so this is the one path that must materialise the bytes.
     * It writes into our own cache folder (never the shared Downloads) and
     * [TempFiles.sweep] drops abandoned copies on every hand-off, so "打开后
     * 下载、看完删除" holds without the user managing anything.
     */
    fun openExternally(entry: FileEntry, item: ViewerItem? = null) {
        val dir = item?.dir ?: _state.value.currentDir ?: return
        val path = item?.relPath ?: relPath(entry)
        val context = getApplication<Application>()
        val size = entry.size ?: 0
        if (size > MaxHandoffBytes) {
            _state.value = _state.value.copy(
                message = "文件较大（${size / 1024 / 1024} MB），请先“下载到本机”再打开"
            )
            return
        }
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            // Reclaim scratch space from earlier sessions before adding to it.
            TempFiles.sweep(context)
            val bytes = repository.download(dir, path).getOrNull()
            _state.value = _state.value.copy(busy = false)
            if (bytes == null) {
                _state.value = _state.value.copy(message = "读取文件失败，请检查连接")
                return@launch
            }
            val file = runCatching { TempFiles.writeHandoff(context, entry.name, bytes) }.getOrNull()
            if (file == null) {
                _state.value = _state.value.copy(message = "无法写入临时文件")
                return@launch
            }
            launchExternal(context, file, FileKinds.mimeOf(entry.name, entry.mimeType))
        }
    }

    private fun launchExternal(context: Application, file: java.io.File, mime: String) {
        val uri: Uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            _state.value = _state.value.copy(message = "无法共享文件：${e.message}")
            return
        }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(view)
        } catch (_: ActivityNotFoundException) {
            // Nothing renders this type directly — offer the share sheet,
            // which can still reach file managers, backup tools, mail…
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(
                    Intent.createChooser(send, "打开方式")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                _state.value = _state.value.copy(message = "没有可打开该类型的应用")
            }
        }
    }

    // ───────────────────── image editing (viewer) ─────────────────────

    fun rotate(item: ViewerItem, angle: Int, onDone: (Boolean) -> Unit) =
        editImage(item, listOf(ImageOp(op = "rotate", angle = angle)), onDone)

    fun flip(item: ViewerItem, vertical: Boolean, onDone: (Boolean) -> Unit) =
        editImage(item, listOf(ImageOp(op = if (vertical) "flip-v" else "flip-h")), onDone)

    fun restore(item: ViewerItem, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = repository.restoreFile(item.dir, item.relPath)
            onDone(result.isSuccess)
            if (result.isSuccess) refreshAfterEdit()
        }
    }

    private fun editImage(item: ViewerItem, ops: List<ImageOp>, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = repository.transformFile(item.dir, item.relPath, ops)
            onDone(result.isSuccess)
            if (result.isSuccess) refreshAfterEdit()
        }
    }

    /** Bump the cache token and re-read the listing (sizes change on edit). */
    private fun refreshAfterEdit() {
        val dir = _state.value.currentDir ?: return
        val path = _state.value.currentPath
        _state.value = _state.value.copy(version = _state.value.version + 1)
        viewModelScope.launch {
            repository.listFiles(dir, path, _state.value.sort, _state.value.desc)
                .onSuccess { _state.value = _state.value.copy(entries = it) }
        }
    }

    // ───────────────────── navigation / sort ─────────────────────

    fun navigateUp() {
        val path = _state.value.currentPath
        val dir = _state.value.currentDir ?: return
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        open(dir, parent)
    }

    fun breadcrumbSegments(): List<String> =
        _state.value.currentPath.split('/').filter { it.isNotBlank() }

    fun setSort(sort: String) {
        _state.value = _state.value.copy(sort = sort)
        val dir = _state.value.currentDir ?: return
        open(dir, _state.value.currentPath)
    }

    fun toggleDesc() {
        _state.value = _state.value.copy(desc = !_state.value.desc)
        val dir = _state.value.currentDir ?: return
        open(dir, _state.value.currentPath)
    }

    fun toggleSelect(entry: FileEntry) {
        val key = entry.path
        val selected = _state.value.selected.toMutableSet()
        if (!selected.add(key)) selected.remove(key)
        _state.value = _state.value.copy(selected = selected)
    }

    fun clearSelection() = _state.value.let { _state.value = it.copy(selected = emptySet()) }

    // ─────────────────────────── mutations ───────────────────────────

    fun rename(entry: FileEntry, newName: String) {
        val dir = _state.value.currentDir ?: return
        val source = relPath(entry)
        viewModelScope.launch {
            repository.rename(dir, source, newName)
                .onSuccess { open(dir, _state.value.currentPath) }
                .onFailure { e ->
                    _state.value = _state.value.copy(message = e.message ?: "重命名失败")
                }
        }
    }

    /** Soft delete into the recycle bin; the UI confirms first. */
    fun delete(entries: List<FileEntry>) {
        val dir = _state.value.currentDir ?: return
        if (entries.isEmpty()) return
        val paths = entries.map { relPath(it) }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val failed = paths.count { repository.delete(dir, it).isFailure }
            open(dir, _state.value.currentPath)
            _state.value = _state.value.copy(
                busy = false,
                message = if (failed == 0) {
                    "已移入回收站（${paths.size} 项）"
                } else {
                    "有 $failed 项删除失败"
                }
            )
        }
    }

    fun copyOrMove(entries: List<FileEntry>, op: String, toDir: String, toPath: String) {
        val dir = _state.value.currentDir ?: return
        if (entries.isEmpty()) return
        val names = entries.map { it.name }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            var failed = 0
            names.forEach { name ->
                val target = if (toPath.isBlank()) name else "${toPath.trimEnd('/')}/$name"
                val source = relOf(_state.value.currentPath, name)
                if (repository.copyOrMove(dir, source, op, toDir, target).isFailure) failed++
            }
            open(dir, _state.value.currentPath)
            _state.value = _state.value.copy(
                busy = false,
                message = if (failed == 0) {
                    (if (op == "copy") "已复制到 " else "已移动到 ") + if (toPath.isBlank()) toDir else "$toDir/$toPath"
                } else {
                    "有 $failed 项操作失败"
                }
            )
        }
    }

    /**
     * Browse a registered directory for the shared directory picker — returns
     * null when the folder cannot be listed.
     */
    suspend fun listAt(dir: String, path: String): List<FileEntry>? =
        repository.listFiles(dir, path, "name", false).getOrNull()

    /** Create a subfolder from inside the picker. */
    suspend fun createDirAt(dir: String, path: String, name: String): Boolean =
        repository.mkdir(dir, path, name).isSuccess

    // ─────────────────────────── misc ───────────────────────────

    /**
     * Kick off the WorkManager uploader for the picked URIs into `dir/path`.
     * The target comes from the directory picker, which defaults to the folder
     * the user is standing in but lets them drop the files anywhere.
     */
    fun upload(dir: String, path: String, uris: List<Uri>, deleteLocal: Boolean) {
        if (dir.isBlank() || uris.isEmpty()) return
        UploadWorker.enqueue(
            getApplication(),
            dir,
            path.trim().trim('/'),
            uris,
            deleteLocal
        )
    }

    /** Fetch the file bytes so the UI can write them through the SAF. */
    fun download(entry: FileEntry, onReady: (ByteArray?) -> Unit) {
        val dir = _state.value.currentDir ?: return
        viewModelScope.launch {
            repository.download(dir, relPath(entry)).fold(
                onSuccess = { onReady(it) },
                onFailure = { onReady(null) }
            )
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private companion object {
        /** Above this a whole-file byte array is not safe to hold in memory. */
        const val MaxHandoffBytes = 80L * 1024 * 1024

        /** Mirror of the server's `/api/documents` preview cap. */
        const val MaxTextPreviewBytes = 1L * 1024 * 1024
    }
}
