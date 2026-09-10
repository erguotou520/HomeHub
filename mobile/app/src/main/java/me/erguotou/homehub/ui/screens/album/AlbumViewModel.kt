package me.erguotou.homehub.ui.screens.album

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.erguotou.homehub.data.GeoPoint
import me.erguotou.homehub.data.ImageOp
import me.erguotou.homehub.data.PersonGroup
import me.erguotou.homehub.data.PhotoItem
import me.erguotou.homehub.data.Repository
import me.erguotou.homehub.data.TagSummary
import me.erguotou.homehub.data.TimelineGroup
import me.erguotou.homehub.data.TreeGroup
import java.util.Locale

enum class AlbumView { TIMELINE, TREE, TAGS, PEOPLE, GEO }

/** Media type filter for the timeline, mirroring the system gallery. */
enum class MediaKind(val apiValue: String?, val label: String) {
    ALL(null, "全部"),
    PHOTOS("photo", "照片"),
    VIDEOS("video", "视频")
}

/**
 * One level of the 目录 drill-down.
 *
 * The server's `tree` listing is flat — each [TreeGroup] is keyed by
 * (dir_name, folder-path-inside-that-dir) — so the hierarchy is rebuilt here
 * rather than adding another endpoint. [path] "" means the dir's own root.
 */
data class TreeNode(
    val label: String,
    val dirName: String,
    val path: String,
    val count: Int
)

data class AlbumUiState(
    val view: AlbumView = AlbumView.TIMELINE,
    val loading: Boolean = false,
    val error: String? = null,
    val kind: MediaKind = MediaKind.ALL,
    val groups: List<TimelineGroup> = emptyList(),
    val trees: List<TreeGroup> = emptyList(),
    val tags: List<TagSummary> = emptyList(),
    val people: List<PersonGroup> = emptyList(),
    val points: List<GeoPoint> = emptyList(),
    val filtered: List<PhotoItem> = emptyList(),
    val activeFilter: String? = null,
    /** Breadcrumb of the 目录 view; empty = the list of album dirs. */
    val treeStack: List<TreeNode> = emptyList()
) {
    /** Child folders of the current 目录 level. */
    val treeFolders: List<TreeNode> get() = childrenOf(trees, treeStack)

    /** Photos that sit directly in the current 目录 level. */
    val treePhotos: List<PhotoItem> get() = photosAt(trees, treeStack)
}

/**
 * Folders one level below [stack].
 *
 * With an empty stack this is the list of configured album dirs; otherwise the
 * direct child folders of `stack.last()`. A folder's count aggregates every
 * photo at or below it, so the number stays meaningful before drilling in.
 */
private fun childrenOf(trees: List<TreeGroup>, stack: List<TreeNode>): List<TreeNode> {
    if (stack.isEmpty()) {
        return trees.groupBy { it.dirName }
            .map { (dir, groups) ->
                TreeNode(dir, dir, "", groups.sumOf { it.count.toInt() })
            }
            .sortedBy { it.label }
    }
    val cur = stack.last()
    val prefix = if (cur.path.isEmpty()) "" else cur.path + "/"
    val counts = linkedMapOf<String, Int>()
    trees.filter { it.dirName == cur.dirName && it.path.startsWith(prefix) && it.path != cur.path }
        .forEach { group ->
            val segment = group.path.removePrefix(prefix).substringBefore('/')
            if (segment.isNotEmpty()) counts[segment] = (counts[segment] ?: 0) + group.count.toInt()
        }
    return counts.map { (segment, count) ->
        TreeNode(segment, cur.dirName, if (cur.path.isEmpty()) segment else "${cur.path}/$segment", count)
    }
}

/** Photos stored directly in the current 目录 level. */
private fun photosAt(trees: List<TreeGroup>, stack: List<TreeNode>): List<PhotoItem> {
    if (stack.isEmpty()) return emptyList()
    val cur = stack.last()
    return trees.firstOrNull { it.dirName == cur.dirName && it.path == cur.path }?.items ?: emptyList()
}

class AlbumViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = Repository(app)

    private val _state = MutableStateFlow(AlbumUiState())
    val state: StateFlow<AlbumUiState> = _state.asStateFlow()

    fun url(relative: String): String = repository.absolute(relative)

    init {
        loadTimeline()
    }

    fun select(view: AlbumView) {
        _state.value = _state.value.copy(view = view, activeFilter = null, error = null)
        when (view) {
            AlbumView.TIMELINE -> loadTimeline()
            AlbumView.TREE -> loadTree()
            AlbumView.TAGS -> loadTags()
            AlbumView.PEOPLE -> loadPeople()
            AlbumView.GEO -> loadGeo()
        }
    }

    /** Timeline media-type filter (全部 / 照片 / 视频). */
    fun selectKind(kind: MediaKind) {
        _state.value = _state.value.copy(kind = kind)
        loadTimeline()
    }

    fun refresh() = select(_state.value.view)

    private fun <T> load(block: suspend () -> Result<T>, apply: (T) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            block().fold(
                onSuccess = {
                    apply(it)
                    _state.value = _state.value.copy(loading = false)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = e.message ?: e.javaClass.simpleName
                    )
                }
            )
        }
    }

    private fun loadTimeline() = load(
        block = { repository.timeline("day", _state.value.kind.apiValue) },
        apply = { _state.value = _state.value.copy(groups = it) }
    )

    private fun loadTree() = load(
        block = { repository.tree() },
        apply = { _state.value = _state.value.copy(trees = it) }
    )

    private fun loadTags() = load(
        block = { repository.tags() },
        apply = { _state.value = _state.value.copy(tags = it) }
    )

    private fun loadPeople() = load(
        block = { repository.people() },
        apply = { _state.value = _state.value.copy(people = it) }
    )

    private fun loadGeo() = load(
        block = { repository.geo() },
        apply = { _state.value = _state.value.copy(points = it) }
    )

    /** Drill into a folder in the 目录 view. */
    fun treeEnter(node: TreeNode) {
        _state.value = _state.value.copy(treeStack = _state.value.treeStack + node)
    }

    /** Go up one folder. Returns false when already at the top level. */
    fun treeUp(): Boolean {
        val stack = _state.value.treeStack
        if (stack.isEmpty()) return false
        _state.value = _state.value.copy(treeStack = stack.dropLast(1))
        return true
    }

    fun filterByTag(tag: String) {
        _state.value = _state.value.copy(activeFilter = tag)
        load(block = { repository.photos(tag = tag) },
            apply = { _state.value = _state.value.copy(filtered = it) })
    }

    fun filterByPerson(id: Long, name: String?) {
        _state.value = _state.value.copy(activeFilter = name ?: "人物 #$id")
        load(block = { repository.photos(personId = id) },
            apply = { _state.value = _state.value.copy(filtered = it) })
    }

    fun filterByTree(group: TreeGroup) {
        _state.value = _state.value.copy(activeFilter = "${group.dirName}/${group.path}")
        load(block = { repository.photos(dirId = group.dirId) },
            apply = { _state.value = _state.value.copy(filtered = it) })
    }

    /** Drill into one map cluster: the server filters by the cluster's ids. */
    fun filterByGeo(point: GeoPoint) {
        val label = String.format(Locale.US, "%.4f, %.4f", point.lat, point.lng)
        _state.value = _state.value.copy(activeFilter = "地点 $label")
        load(
            block = { repository.photos(ids = point.photoIds) },
            apply = { _state.value = _state.value.copy(filtered = it) }
        )
    }

    fun clearFilter() {
        // Go back to the view the filter came from (the map when drilling into
        // a geo cluster) and reload it.
        val view = _state.value.view
        _state.value = _state.value.copy(activeFilter = null, filtered = emptyList())
        select(view)
    }

    fun rotate(photo: PhotoItem, angle: Int, onDone: (Boolean) -> Unit) {
        editImage(photo, listOf(ImageOp(op = "rotate", angle = angle)), onDone)
    }

    fun flip(photo: PhotoItem, vertical: Boolean, onDone: (Boolean) -> Unit) {
        val op = if (vertical) "flip-v" else "flip-h"
        editImage(photo, listOf(ImageOp(op = op)), onDone)
    }

    fun restore(photo: PhotoItem, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = repository.restoreImage(photo)
            onDone(result.isSuccess)
            if (result.isSuccess) refresh()
        }
    }

    private fun editImage(photo: PhotoItem, ops: List<ImageOp>, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = repository.transformImage(photo, ops)
            onDone(result.isSuccess)
            if (result.isSuccess) refresh()
        }
    }
}
