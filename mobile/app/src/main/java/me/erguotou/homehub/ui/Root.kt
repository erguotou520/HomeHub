package me.erguotou.homehub.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import me.erguotou.homehub.data.Repository
import me.erguotou.homehub.ui.components.NoticeHost
import me.erguotou.homehub.ui.components.rememberPhotoDownloader
import me.erguotou.homehub.ui.components.rememberPhotoSharer
import me.erguotou.homehub.ui.screens.album.AlbumScreen
import me.erguotou.homehub.ui.screens.album.PhotoViewerScreen
import me.erguotou.homehub.ui.screens.album.SemanticSearchScreen
import me.erguotou.homehub.ui.screens.files.FilesScreen
import me.erguotou.homehub.ui.screens.monitor.MonitorScreen
import me.erguotou.homehub.ui.screens.settings.SettingsScreen
import me.erguotou.homehub.ui.screens.setup.SetupScreen

sealed class Tab(val route: String, val label: String, val icon: ImageVector, val iconSelected: ImageVector) {
    data object Album : Tab("album", "相册", Icons.Outlined.PhotoLibrary, Icons.Filled.PhotoLibrary)
    data object Files : Tab("files", "文件", Icons.Outlined.Folder, Icons.Filled.Folder)
    data object Monitor : Tab("monitor", "监控", Icons.Outlined.Videocam, Icons.Filled.Videocam)
    data object Settings : Tab("settings", "设置", Icons.Outlined.Settings, Icons.Filled.Settings)
}

private val TABS = listOf(Tab.Album, Tab.Files, Tab.Monitor, Tab.Settings)

@Composable
fun HomeHubRoot() {
    val navController = rememberNavController()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        bottomBar = {
            if (!fullscreen) {
                NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                TABS.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                if (selected) tab.iconSelected else tab.icon,
                                contentDescription = tab.label
                            )
                        },
                        label = { Text(tab.label) }
                    )
                }
                }
            }
        },
        // Every screen owns a Scaffold + TopAppBar, and those already apply the
        // status-bar inset themselves. Leaving the default (systemBars) here
        // would inset the content twice and — more importantly — make it
        // impossible for the album's fullscreen viewer overlay to reach the
        // status bar. The bottom inset is still contributed by the bottom bar.
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController = navController, startDestination = Tab.Album.route) {
                composable(Tab.Album.route) {
                    AlbumScreen(
                        onFullscreenChange = { fullscreen = it },
                        onOpenSemantic = { navController.navigate("album/semantic") }
                    )
                }
                composable("album/semantic") {
                    val semanticVm: me.erguotou.homehub.ui.screens.album.AlbumViewModel =
                        androidx.lifecycle.viewmodel.compose.viewModel()
                    val context = LocalContext.current
                    val repository = remember { Repository(context) }
                    val snackbar = remember { SnackbarHostState() }
                    val downloadPhoto = rememberPhotoDownloader(repository, snackbar)
                    val sharePhoto = rememberPhotoSharer(repository, snackbar)
                    var viewerPhoto by remember { mutableStateOf<me.erguotou.homehub.data.PhotoItem?>(null) }
                    var viewerList by remember { mutableStateOf(listOf<me.erguotou.homehub.data.PhotoItem>()) }
                    Box(modifier = Modifier.fillMaxSize()) {
                        SemanticSearchScreen(
                            onBack = { navController.popBackStack() },
                            onFullscreenChange = { fullscreen = it },
                            onOpen = { photo, list ->
                                viewerList = list
                                viewerPhoto = photo
                            },
                            onDownload = downloadPhoto,
                            onShare = sharePhoto
                        )
                        viewerPhoto?.let { photo ->
                            val list = viewerList
                            val index = list.indexOfFirst { it.id == photo.id }.coerceAtLeast(0)
                            LaunchedEffect(photo.id) { fullscreen = true }
                            DisposableEffect(Unit) {
                                onDispose { fullscreen = false }
                            }
                            PhotoViewerScreen(
                                photos = list,
                                initialIndex = index,
                                urlResolver = { semanticVm.url(it) },
                                mediaUrlResolver = { id -> semanticVm.url("api/photos/$id/raw") },
                                onRotate = { p, angle, done -> semanticVm.rotate(p, angle, done) },
                                onFlip = { p, vertical, done -> semanticVm.flip(p, vertical, done) },
                                onRestore = { p, done -> semanticVm.restore(p, done) },
                                onDownload = downloadPhoto,
                                onShare = sharePhoto,
                                onDismiss = { viewerPhoto = null }
                            )
                        }
                        // Last child of the Box: a download failure stays visible
                        // even while the full-screen viewer covers the results.
                        NoticeHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
                composable(Tab.Files.route) { FilesScreen(onFullscreenChange = { fullscreen = it }) }
                composable(Tab.Monitor.route) { MonitorScreen() }
                composable(Tab.Settings.route) {
                    SettingsScreen(onOpenSetup = { navController.navigate("setup") })
                }
                composable("setup") {
                    SetupScreen(onFinished = { navController.popBackStack() })
                }
            }
        }
    }
}
