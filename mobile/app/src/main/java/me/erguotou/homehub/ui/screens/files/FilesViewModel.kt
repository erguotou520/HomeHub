package me.erguotou.homehub.ui.screens.files

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.erguotou.homehub.data.DirEntry
import me.erguotou.homehub.data.FileEntry
import me.erguotou.homehub.data.Repository
import me.erguotou.homehub.work.UploadWorker

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
    val preview: String? = null,
    val imageUrl: String? = null
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
    }

    fun open(dir: String, path: String) {
        _state.value = _state.value.copy(
            loading = true,
            error = null,
            currentDir = dir,
            currentPath = path,
            selected = emptySet(),
            preview = null,
            imageUrl = null
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
    private fun relPath(entry: FileEntry): String {
        val base = _state.value.currentPath.trimEnd('/')
        return if (base.isEmpty()) entry.name else "$base/${entry.name}"
    }

    /** URL to the raw file bytes, used for image preview and media intents. */
    fun fileUrl(entry: FileEntry): String {
        val dir = _state.value.currentDir ?: return ""
        return repository.absolute("/api/files/${dir}/${relPath(entry)}")
    }

    fun navigateTo(entry: FileEntry) {
        if (entry.isDir) {
            open(_state.value.currentDir ?: return, relPath(entry))
            return
        }
        when (entry.mediaKind) {
            "image" -> _state.value = _state.value.copy(imageUrl = fileUrl(entry))
            "video", "music" -> openMedia(entry)
            else -> previewFile(entry)
        }
    }

    fun navigateUp() {
        val path = _state.value.currentPath
        val dir = _state.value.currentDir ?: return
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        open(dir, parent)
    }

    /** Jump directly to a breadcrumb segment. */
    fun navigateToBreadcrumb(index: Int) {
        val dir = _state.value.currentDir ?: return
        val segments = breadcrumbSegments()
        val target = segments.take(index + 1).joinToString("/")
        open(dir, target)
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

    fun mkdir(name: String) {
        val dir = _state.value.currentDir ?: return
        viewModelScope.launch {
            repository.mkdir(dir, _state.value.currentPath, name)
                .onSuccess { open(dir, _state.value.currentPath) }
        }
    }

    fun rename(entry: FileEntry, newName: String) {
        val dir = _state.value.currentDir ?: return
        val source = relPath(entry)
        viewModelScope.launch {
            repository.rename(dir, source, newName)
                .onSuccess { open(dir, _state.value.currentPath) }
        }
    }

    fun delete(entries: List<FileEntry>) {
        val dir = _state.value.currentDir ?: return
        viewModelScope.launch {
            entries.forEach { repository.delete(dir, relPath(it)) }
            open(dir, _state.value.currentPath)
        }
    }

    fun copyOrMove(entries: List<FileEntry>, op: String, toDir: String, toPath: String) {
        val dir = _state.value.currentDir ?: return
        viewModelScope.launch {
            entries.forEach {
                val target = if (toPath.isBlank()) it.name else "${toPath.trimEnd('/')}/${it.name}"
                repository.copyOrMove(dir, relPath(it), op, toDir, target)
            }
            open(dir, _state.value.currentPath)
        }
    }

    fun previewFile(entry: FileEntry) {
        val dir = _state.value.currentDir ?: return
        viewModelScope.launch {
            val result = repository.readDocument(dir, relPath(entry))
            _state.value = _state.value.copy(
                preview = result.getOrDefault("（无法预览该文件）")
            )
        }
    }

    /** Hand audio/video off to a system player via the device-wide WG tunnel. */
    private fun openMedia(entry: FileEntry) {
        val url = fileUrl(entry)
        if (url.isBlank()) return
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
            val mime = entry.mimeType ?: guessMime(entry.name)
            setDataAndType(data, mime)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            getApplication<Application>().startActivity(intent)
        } catch (_: Exception) {
            // No handler installed; fall back to text preview.
            previewFile(entry)
        }
    }

    fun dismissPreview() = _state.value.let { _state.value = it.copy(preview = null) }
    fun dismissImage() = _state.value.let { _state.value = it.copy(imageUrl = null) }

    /** Kick off the WorkManager uploader for the picked URIs. */
    fun upload(uris: List<Uri>, deleteLocal: Boolean) {
        val dir = _state.value.currentDir ?: return
        UploadWorker.enqueue(getApplication(), dir, _state.value.currentPath, uris, deleteLocal)
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

    fun absolute(url: String): String = repository.absolute(url)

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            else -> "*/*"
        }
    }
}
