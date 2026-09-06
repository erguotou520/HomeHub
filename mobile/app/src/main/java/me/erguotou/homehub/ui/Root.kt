package me.erguotou.homehub.ui

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import me.erguotou.homehub.ui.screens.album.AlbumScreen
import me.erguotou.homehub.ui.screens.files.FilesScreen
import me.erguotou.homehub.ui.screens.monitor.MonitorScreen
import me.erguotou.homehub.ui.screens.settings.SettingsScreen

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
    Scaffold(
        bottomBar = {
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
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController = navController, startDestination = Tab.Album.route) {
                composable(Tab.Album.route) { AlbumScreen() }
                composable(Tab.Files.route) { FilesScreen() }
                composable(Tab.Monitor.route) { MonitorScreen() }
                composable(Tab.Settings.route) {
                    SettingsScreen(onOpenSetup = { /* handled by MainActivity gate */ })
                }
            }
        }
    }
}
