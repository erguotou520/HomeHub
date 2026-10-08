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

/** Timeline bucket size requested from the server (one header per day). */
private const val TIMELINE_GROUP = "day"

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
    /** Timeline paging: another page exists / one is in flight / the last failed. */
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val moreError: String? = null,
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
 * Append [page] to the timeline already on screen.
 *
 * A page boundary lands wherever the item count runs out, so it regularly falls
 * inside a day: the new page then opens with the very group the list already
 * ends with. Those two are stitched into one — appending them as-is would show
 * the day twice and hand the grid two items with the same key, which
 * LazyVerticalGrid treats as a crash.
 */
fun mergeTimeline(loaded: List<TimelineGroup>, page: List<TimelineGroup>): List<TimelineGroup> {
    if (loaded.isEmpty()) return page
    if (page.isEmpty()) return loaded
    val tail = loaded.last()
    val head = page.first()
    if (tail.key != head.key) return loaded + page
    // Both pages report the server's full-day count, so `count` needs no merge.
    return loaded.dropLast(1) + tail.copy(items = tail.items + head.items) + page.drop(1)
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

    /** Keyset cursor of the last timeline page; null until one has landed. */
    private var timelineCursor: Pair<Long, Long>? = null

    /**
     * Bumped on every from-scratch timeline load. A page that lands after one
     * (the user switched 全部/照片/视频 mid-flight) belongs to a list that is no
     * longer on screen, so it is dropped instead of being appended to a
     * different filter's photos.
     */
    private var timelineGeneration = 0

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

    /**
     * Load the newest timeline page from scratch.
     *
     * Deliberately not routed through [load]: it has to invalidate the cursor
     * and any in-flight page before the request goes out, and to ignore the
     * answer if a newer reload has started meanwhile.
     */
    private fun loadTimeline() {
        val generation = ++timelineGeneration
        timelineCursor = null
        _state.value = _state.value.copy(
            loading = true,
            error = null,
            hasMore = false,
            loadingMore = false,
            moreError = null
        )
        viewModelScope.launch {
            val result = repository.timelinePage(TIMELINE_GROUP, _state.value.kind.apiValue)
            if (generation != timelineGeneration) return@launch
            result.fold(
                onSuccess = { page ->
                    timelineCursor = page.cursor
                    _state.value = _state.value.copy(
                        loading = false,
                        groups = page.groups,
                        hasMore = page.hasMore && page.cursor != null
                    )
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

    /**
     * Fetch the next timeline page and append it.
     *
     * Called from the grid's scroll callback, so it is a no-op unless the
     * timeline is the visible list, a page is not already in flight and the
     * server said there is more.
     */
    fun loadMore() {
        val current = _state.value
        if (current.loading || current.loadingMore || !current.hasMore) return
        if (current.view != AlbumView.TIMELINE || current.activeFilter != null) return
        val from = timelineCursor ?: return
        val generation = timelineGeneration
        _state.value = current.copy(loadingMore = true, moreError = null)
        viewModelScope.launch {
            val result = repository.timelinePage(
                group = TIMELINE_GROUP,
                kind = _state.value.kind.apiValue,
                before = from
            )
            if (generation != timelineGeneration) return@launch
            result.fold(
                onSuccess = { page ->
                    page.cursor?.let { timelineCursor = it }
                    val state = _state.value
                    _state.value = state.copy(
                        groups = mergeTimeline(state.groups, page.groups),
                        hasMore = page.hasMore && page.cursor != null,
                        loadingMore = false
                    )
                },
                onFailure = { e ->
                    // Keep the photos already on screen; the footer offers a retry.
                    _state.value = _state.value.copy(
                        loadingMore = false,
                        moreError = e.message ?: e.javaClass.simpleName
                    )
                }
            )
        }
    }

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

    /**
     * Jump straight to a breadcrumb level: index 0 is the first dir under
     * Home, negative goes back to the top level (全部目录).
     */
    fun treeJumpTo(index: Int) {
        val stack = _state.value.treeStack
        _state.value = _state.value.copy(
            treeStack = if (index < 0) emptyList() else stack.take(index + 1)
        )
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
        // Upgrade the header to a place name once the server resolves it
        // (cached in its DB, so repeat taps are instant).
        viewModelScope.launch {
            repository.geoReverse(point.lat, point.lng).getOrNull()?.let { place ->
                if (place != null) renameActiveFilter("地点 $place")
            }
        }
    }

    /**
     * Replace the active filter's label once extra detail arrives — e.g. the
     * reverse-geocoded place name for a map cluster. No-op when the filter
     * was already cleared (stale callback).
     */
    fun renameActiveFilter(label: String) {
        if (_state.value.activeFilter != null) {
            _state.value = _state.value.copy(activeFilter = label)
        }
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
