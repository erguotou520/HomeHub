package me.erguotou.homehub.ui.screens.album

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.erguotou.homehub.data.PhotoItem
import me.erguotou.homehub.data.Repository
import me.erguotou.homehub.data.SemanticResponse
import me.erguotou.homehub.ui.components.Empty
import me.erguotou.homehub.ui.components.ErrorText
import me.erguotou.homehub.ui.components.Loading
import me.erguotou.homehub.ui.components.PhotoActionMenu
import me.erguotou.homehub.ui.components.PhotoTile

data class SemanticUiState(
    val loading: Boolean = false,
    val query: String = "",
    val results: List<PhotoItem> = emptyList(),
    val scores: Map<String, String> = emptyMap(),
    val error: String? = null
)

class SemanticSearchViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val repository = Repository(app)
    private val _state = MutableStateFlow(SemanticUiState())
    /** UI-facing state; `SemanticUiState` is public so the delegate works. */
    val state: StateFlow<SemanticUiState> = _state.asStateFlow()

    fun url(relative: String): String = repository.absolute(relative)
    fun mediaUrl(id: Long): String = repository.mediaUrl(id)

    fun search(query: String) {
        if (query.trim().isEmpty()) {
            _state.value = _state.value.copy(query = query, results = emptyList(), scores = emptyMap())
            return
        }
        _state.value = _state.value.copy(query = query, loading = true, error = null)
        viewModelScope.launch {
            repository.semantic(query.trim()).onSuccess { res ->
                _state.value = _state.value.copy(
                    loading = false,
                    results = res.items,
                    scores = res.scores,
                    error = null
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(loading = false, error = e.message ?: "搜索失败")
            }
        }
    }
}

/** Natural-language photo search (Chinese-CLIP) — opened from the album header. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemanticSearchScreen(
    onBack: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    onOpen: (PhotoItem, List<PhotoItem>) -> Unit,
    onDownload: (PhotoItem) -> Unit = {},
    vm: SemanticSearchViewModel = viewModel()
) {
    // No lifecycle-compose dependency: collect with an explicit initial value.
    val state by vm.state.collectAsState(initial = SemanticUiState())
    var input by remember { mutableStateOf(state.query) }
    // Long-press target for the shared album action sheet (下载到本机).
    var menuPhoto by remember { mutableStateOf<PhotoItem?>(null) }
    // Debounce so typing Chinese doesn't fire a request per keystroke.
    LaunchedEffect(input) {
        kotlinx.coroutines.delay(400)
        if (input != state.query) vm.search(input)
    }

    LaunchedEffect(Unit) { onFullscreenChange(true) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onFullscreenChange(false) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("语义搜索") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("用自然语言描述照片，如：海边玩水 / 两个白发老人") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索") },
                trailingIcon = {
                    if (input.isNotEmpty()) {
                        IconButton(onClick = { input = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "清除")
                        }
                    }
                },
                singleLine = true
            )

            state.error?.let { ErrorText(it, onRetry = { vm.search(input) }) }

            if (state.loading && state.results.isEmpty()) {
                Loading()
            } else if (input.trim().isEmpty()) {
                Empty("输入描述开始语义搜索")
            } else if (!state.loading && state.results.isEmpty() && state.error == null) {
                Empty("没有语义匹配的照片")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.results, key = { it.id }) { photo ->
                        Box {
                            PhotoTile(
                                photo = photo,
                                urlResolver = vm::url,
                                onClick = { onOpen(photo, state.results) },
                                onLongClick = { menuPhoto = photo }
                            )
                            if (photo.mediaKind == "video") {
                                val label = photo.durationMs?.let { ms ->
                                    val s = (ms / 1000).toInt()
                                    "${s / 60}:${String.format(java.util.Locale.US, "%02d", s % 60)}"
                                } ?: "视频"
                                Text(
                                    text = label,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            state.scores[photo.id.toString()]?.let { s ->
                                Text(
                                    text = s,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    PhotoActionMenu(
        target = menuPhoto,
        onDownload = onDownload,
        onDismiss = { menuPhoto = null }
    )
}
