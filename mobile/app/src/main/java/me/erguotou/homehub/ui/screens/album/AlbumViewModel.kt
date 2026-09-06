package me.erguotou.homehub.ui.screens.album

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.erguotou.homehub.data.GeoPoint
import me.erguotou.homehub.data.PersonGroup
import me.erguotou.homehub.data.PhotoItem
import me.erguotou.homehub.data.Repository
import me.erguotou.homehub.data.TagSummary
import me.erguotou.homehub.data.TimelineGroup
import me.erguotou.homehub.data.TreeGroup

enum class AlbumView { TIMELINE, TREE, TAGS, PEOPLE, GEO }

data class AlbumUiState(
    val view: AlbumView = AlbumView.TIMELINE,
    val loading: Boolean = false,
    val error: String? = null,
    val groups: List<TimelineGroup> = emptyList(),
    val trees: List<TreeGroup> = emptyList(),
    val tags: List<TagSummary> = emptyList(),
    val people: List<PersonGroup> = emptyList(),
    val points: List<GeoPoint> = emptyList(),
    val filtered: List<PhotoItem> = emptyList(),
    val activeFilter: String? = null
)

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
        block = { repository.timeline("month") },
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
        val label = String.format("%.4f, %.4f", point.lat, point.lng)
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
        viewModelScope.launch {
            val result = repository.rotate(photo.id, angle)
            onDone(result.isSuccess)
            if (result.isSuccess) refresh()
        }
    }
}
