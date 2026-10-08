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
import me.erguotou.homehub.data.PersonCursor
import me.erguotou.homehub.data.PersonGroup
import me.erguotou.homehub.data.PhotoItem
import me.erguotou.homehub.data.PhotoListResponse
import me.erguotou.homehub.data.Repository
import me.erguotou.homehub.data.TagCursor
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

/**
 * 一份「滚到底再要一页」的列表的状态。
 *
 * 四条列表（时间轴 / 分类 / 人物 / 筛选后的照片）共用同一组字段，也共用同一个
 * 底部控件 —— 它们的续拉行为本来就该一致，各写一套迟早会漂成四种。
 * 游标不在这里：它只活在 ViewModel 里，界面不需要知道下一段长什么样。
 */
data class Paging(
    /** 服务端说还有下一页。 */
    val hasMore: Boolean = false,
    /** 下一页正在路上，底部显示转圈。 */
    val loadingMore: Boolean = false,
    /** 上一页失败了，底部换成「点击重试」。 */
    val error: String? = null
)

/**
 * 一页响应换算成续拉状态。
 *
 * 服务端说 `has_more` 却**没给游标**时不能再要：没有游标就接不下去，硬要的话会
 * 把第一页反复当下一页拉 —— 请求全部成功、界面看着正常，内容却永远停在开头。
 * 宁可在这里停住。
 *
 * [cursor] 只用来判空，四条列表的游标形状各不相同（三元组 / 二元组），所以这里
 * 只要 `Any?`，不必为一个空判断给每条列表各写一份。
 */
internal fun pagingFrom(hasMore: Boolean, cursor: Any?): Paging =
    Paging(hasMore = hasMore && cursor != null)

data class AlbumUiState(
    val view: AlbumView = AlbumView.TIMELINE,
    val loading: Boolean = false,
    val error: String? = null,
    val kind: MediaKind = MediaKind.ALL,
    val groups: List<TimelineGroup> = emptyList(),
    val timelinePaging: Paging = Paging(),
    val trees: List<TreeGroup> = emptyList(),
    val tags: List<TagSummary> = emptyList(),
    val tagPaging: Paging = Paging(),
    val people: List<PersonGroup> = emptyList(),
    val personPaging: Paging = Paging(),
    val points: List<GeoPoint> = emptyList(),
    val filtered: List<PhotoItem> = emptyList(),
    val filterPaging: Paging = Paging(),
    val activeFilter: String? = null,
    /** Breadcrumb of the 目录 view; empty = the list of album dirs. */
    val treeStack: List<TreeNode> = emptyList()
) {
    /** Child folders of the current 目录 level. */
    val treeFolders: List<TreeNode> get() = childrenOf(trees, treeStack)

    /** Photos that sit directly in the current 目录 level. */
    val treePhotos: List<PhotoItem> get() = photosAt(trees, treeStack)

    /**
     * 当前视图（含筛选态）手上是否已经有能显示的东西。
     *
     * 全屏 spinner 只能顶替「什么都没有」的屏幕：列表里已经有数据时（切回一个
     * 已在内存里的视图）旧内容要留在原地，刷新在它下面进行 —— 这就是缓存优先。
     * 判据必须取屏幕上真正显示的那份数据，不能取一个共用的标志位：切到「分类」
     * 时时间轴的照片还在 [groups] 里，用它去判「有没有内容」会得真，于是守卫放
     * 行、落到空的标签列表上，先闪一个「还没有标签」再跳成数据。
     *
     * 反过来，请求回来确实为空（`loading == false` 且这里仍然为假）时，空状态
     * 才是对的答案 —— 所以空列表不在这里兜底。
     */
    val hasContent: Boolean
        get() {
            if (activeFilter != null) return filtered.isNotEmpty()
            return when (view) {
                AlbumView.TIMELINE -> groups.isNotEmpty()
                AlbumView.TREE -> trees.isNotEmpty()
                AlbumView.TAGS -> tags.isNotEmpty()
                AlbumView.PEOPLE -> people.isNotEmpty()
                AlbumView.GEO -> points.isNotEmpty()
            }
        }
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

    /** 每条分页列表各自的游标，原样取自服务端、原样回传。 */
    private var timelineCursor: Pair<Long, Long>? = null
    private var tagCursor: TagCursor? = null
    private var personCursor: PersonCursor? = null
    private var filterCursor: Pair<Long, Long>? = null

    /**
     * 当前筛选怎么取下一页。
     *
     * 光靠 [AlbumUiState.activeFilter] 那个标签字符串是重建不出查询的 —— 人物
     * 筛选要的是 id、地点筛选要的是那一串 photo id、"目录/子目录" 要的是 dir_id。
     * 所以把「发这一次请求」这件事本身记下来。
     */
    private var filterFetch: (suspend (Pair<Long, Long>?) -> Result<PhotoListResponse>)? = null

    /**
     * 每开始一次「从头加载」就 +1；路上的一页回来时对不上号就丢掉。
     *
     * 丢掉是必须的，后果很具体：下拉刷新时若恰好有一页在路上，旧的那页会追加到
     * 新的第一页后面，同一张照片出现两次 —— 而 LazyVerticalGrid 遇到重复 key
     * 直接崩。时间轴还需要它来区分「切了 全部/照片/视频」，那是同一个 view 换了
     * 筛选，光看 view 认不出来。
     */
    private var listGeneration = 0

    fun url(relative: String): String = repository.absolute(relative)

    init {
        loadTimeline()
    }

    fun select(view: AlbumView) {
        _state.value = _state.value.copy(view = view, activeFilter = null, error = null)
        filterFetch = null
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

    /**
     * 起一次加载，并把 `loading` 立刻打开。
     *
     * 标志位写在 `launch` **外面**：视图/筛选可能刚换过，而新旧数据并存于同一个
     * state 里，屏幕此刻按新视图去取只会取到空。若 `loading` 要等到协程被调度
     * 才为真，中间那一帧就会闪一次空状态 —— 与 [loadTimeline] 的做法保持一致。
     */
    private fun <T> load(block: suspend () -> Result<T>, apply: (T) -> Unit) {
        val generation = ++listGeneration
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            block().fold(
                onSuccess = { value ->
                    // 换过列表了：这份结果描述的是屏幕上已经没有的那份数据。
                    // 不碰 loading —— 接手的那次加载会自己收尾。
                    if (generation == listGeneration) {
                        apply(value)
                        _state.value = _state.value.copy(loading = false)
                    }
                },
                onFailure = { e ->
                    if (generation == listGeneration) {
                        _state.value = _state.value.copy(
                            loading = false,
                            error = e.message ?: e.javaClass.simpleName
                        )
                    }
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
        val generation = ++listGeneration
        timelineCursor = null
        _state.value = _state.value.copy(
            loading = true,
            error = null,
            timelinePaging = Paging()
        )
        viewModelScope.launch {
            val result = repository.timelinePage(TIMELINE_GROUP, _state.value.kind.apiValue)
            if (generation != listGeneration) return@launch
            result.fold(
                onSuccess = { page ->
                    timelineCursor = page.cursor
                    _state.value = _state.value.copy(
                        loading = false,
                        groups = page.groups,
                        timelinePaging = pagingFrom(page.hasMore, page.cursor)
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
        val paging = current.timelinePaging
        if (current.loading || paging.loadingMore || !paging.hasMore) return
        if (current.view != AlbumView.TIMELINE || current.activeFilter != null) return
        val from = timelineCursor ?: return
        val generation = listGeneration
        _state.value = current.copy(timelinePaging = paging.copy(loadingMore = true, error = null))
        viewModelScope.launch {
            val result = repository.timelinePage(
                group = TIMELINE_GROUP,
                kind = _state.value.kind.apiValue,
                before = from
            )
            if (generation != listGeneration) return@launch
            result.fold(
                onSuccess = { page ->
                    page.cursor?.let { timelineCursor = it }
                    val state = _state.value
                    _state.value = state.copy(
                        groups = mergeTimeline(state.groups, page.groups),
                        timelinePaging = pagingFrom(page.hasMore, page.cursor)
                    )
                },
                onFailure = { e ->
                    // 已经上屏的照片留在原地，底部换成「点击重试」。
                    _state.value = _state.value.copy(
                        timelinePaging = _state.value.timelinePaging.copy(
                            loadingMore = false,
                            error = e.message ?: e.javaClass.simpleName
                        )
                    )
                }
            )
        }
    }

    /** 分类列表的下一页。角色与 [loadMore] 相同，只是换了条列表。 */
    fun loadTagsMore() {
        val current = _state.value
        val paging = current.tagPaging
        if (current.loading || paging.loadingMore || !paging.hasMore) return
        if (current.view != AlbumView.TAGS || current.activeFilter != null) return
        val from = tagCursor ?: return
        val generation = listGeneration
        _state.value = current.copy(tagPaging = paging.copy(loadingMore = true, error = null))
        viewModelScope.launch {
            val result = repository.tagsPage(after = from)
            if (generation != listGeneration) return@launch
            result.fold(
                onSuccess = { page ->
                    page.cursor?.let { tagCursor = it }
                    val state = _state.value
                    // 直接追加即可：服务端的游标保证两页不重叠，所以不需要时间轴
                    // 那种「把同一天缝回同一个标题」的处理。
                    _state.value = state.copy(
                        tags = state.tags + page.tags,
                        tagPaging = pagingFrom(page.hasMore, page.cursor)
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        tagPaging = _state.value.tagPaging.copy(
                            loadingMore = false,
                            error = e.message ?: e.javaClass.simpleName
                        )
                    )
                }
            )
        }
    }

    /** 人物列表的下一页。 */
    fun loadPeopleMore() {
        val current = _state.value
        val paging = current.personPaging
        if (current.loading || paging.loadingMore || !paging.hasMore) return
        if (current.view != AlbumView.PEOPLE || current.activeFilter != null) return
        val from = personCursor ?: return
        val generation = listGeneration
        _state.value = current.copy(personPaging = paging.copy(loadingMore = true, error = null))
        viewModelScope.launch {
            val result = repository.peoplePage(after = from)
            if (generation != listGeneration) return@launch
            result.fold(
                onSuccess = { page ->
                    page.cursor?.let { personCursor = it }
                    val state = _state.value
                    _state.value = state.copy(
                        people = state.people + page.people,
                        personPaging = pagingFrom(page.hasMore, page.cursor)
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        personPaging = _state.value.personPaging.copy(
                            loadingMore = false,
                            error = e.message ?: e.javaClass.simpleName
                        )
                    )
                }
            )
        }
    }

    /** 筛选视图（分类 / 人物 / 目录 / 地点）里的下一页照片。 */
    fun loadFilteredMore() {
        val current = _state.value
        val paging = current.filterPaging
        if (current.loading || paging.loadingMore || !paging.hasMore) return
        if (current.activeFilter == null) return
        val from = filterCursor ?: return
        val fetch = filterFetch ?: return
        val generation = listGeneration
        _state.value = current.copy(filterPaging = paging.copy(loadingMore = true, error = null))
        viewModelScope.launch {
            val result = fetch(from)
            if (generation != listGeneration) return@launch
            result.fold(
                onSuccess = { page ->
                    page.cursor?.let { filterCursor = it }
                    val state = _state.value
                    _state.value = state.copy(
                        filtered = state.filtered + page.items,
                        filterPaging = pagingFrom(page.hasMore, page.cursor)
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        filterPaging = _state.value.filterPaging.copy(
                            loadingMore = false,
                            error = e.message ?: e.javaClass.simpleName
                        )
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
        block = { repository.tagsPage() },
        apply = { page ->
            tagCursor = page.cursor
            _state.value = _state.value.copy(
                tags = page.tags,
                tagPaging = pagingFrom(page.hasMore, page.cursor)
            )
        }
    )

    private fun loadPeople() = load(
        block = { repository.peoplePage() },
        apply = { page ->
            personCursor = page.cursor
            _state.value = _state.value.copy(
                people = page.people,
                personPaging = pagingFrom(page.hasMore, page.cursor)
            )
        }
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

    /**
     * 进一个筛选：把标签和**怎么取下一页**一起记下来，再拉第一页。
     *
     * 两者必须同时更新。只更新标签的话，在筛选里滚到底会拿上一条筛选的谓词去要
     * 下一页，等于把别的分类的照片接在当前列表后面。
     */
    private fun setFilter(
        label: String,
        fetch: suspend (Pair<Long, Long>?) -> Result<PhotoListResponse>
    ) {
        filterFetch = fetch
        _state.value = _state.value.copy(activeFilter = label)
        load(
            block = { fetch(null) },
            apply = { page ->
                filterCursor = page.cursor
                _state.value = _state.value.copy(
                    filtered = page.items,
                    filterPaging = pagingFrom(page.hasMore, page.cursor)
                )
            }
        )
    }

    fun filterByTag(tag: String) = setFilter(tag) { before ->
        repository.photos(tag = tag, before = before)
    }

    fun filterByPerson(id: Long, name: String?) = setFilter(name ?: "人物 #$id") { before ->
        repository.photos(personId = id, before = before)
    }

    fun filterByTree(group: TreeGroup) = setFilter("${group.dirName}/${group.path}") { before ->
        repository.photos(dirId = group.dirId, before = before)
    }

    /** Drill into one map cluster: the server filters by the cluster's ids. */
    fun filterByGeo(point: GeoPoint) {
        val label = String.format(Locale.US, "%.4f, %.4f", point.lat, point.lng)
        setFilter("地点 $label") { before ->
            repository.photos(ids = point.photoIds, before = before)
        }
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
        filterFetch = null
        filterCursor = null
        _state.value = _state.value.copy(
            activeFilter = null,
            filtered = emptyList(),
            filterPaging = Paging()
        )
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
