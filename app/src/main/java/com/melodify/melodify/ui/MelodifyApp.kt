package com.melodify.melodify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.melodify.melodify.MelodifyApp
import com.melodify.melodify.data.repository.MusicRepository
import com.melodify.melodify.ui.screens.EqualizerScreen
import com.melodify.melodify.ui.screens.LibraryScreen
import com.melodify.melodify.ui.screens.PlaylistsScreen
import com.melodify.melodify.ui.screens.QueueScreen
import com.melodify.melodify.ui.components.MiniPlayerBar
import com.melodify.melodify.ui.screens.NowPlayingScreen

enum class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
) {
    Library("library", "Library", Icons.Outlined.LibraryMusic, Icons.Filled.LibraryMusic),
    Playlists("playlists", "Playlists", Icons.Outlined.QueueMusic, Icons.Filled.QueueMusic),
    Equalizer("equalizer", "Equalizer", Icons.Outlined.Tune, Icons.Filled.Tune),
    NowPlaying("now", "Now Playing", Icons.Outlined.Album, Icons.Filled.Album),
    Queue("queue", "Up Next", Icons.Outlined.QueueMusic, Icons.Filled.QueueMusic)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MelodifyApp() {
    val navController = rememberNavController()
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as MelodifyApp
    val playerController = remember { app.playerController }
    val playerState by playerController.state.collectAsState()
    val lyrics by playerController.lyrics.collectAsState()

    var showEqualizer by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }

    // Top-level navigation route -> bottom bar visibility.
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in listOf(
        Screen.Library.route, Screen.Playlists.route, Screen.Equalizer.route
    )

    // Keep the player state flowing even when no composable reads it directly.
    LaunchedEffect(playerState.isPlaying) { /* observe */ }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    Column {
                        if (playerState.currentTrack != null) {
                            MiniPlayerBar(
                                state = playerState,
                                onClick = {
                                    navController.navigate(Screen.NowPlaying.route) {
                                        launchSingleTop = true
                                    }
                                },
                                onPlayPause = { playerController.togglePlayPause() },
                                onSkipNext = { playerController.next() }
                            )
                        }
                        MelodifyBottomBar(
                            currentRoute = currentRoute,
                            onSelect = { route ->
                                navController.navigate(route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Library.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                composable(Screen.Library.route) {
                    LibraryScreen(
                        repository = app.musicRepository,
                        onPlayTrack = { track, all ->
                            playerController.playQueue(all, all.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
                        },
                        onPlayNext = { playerController.playNext(it) },
                        onEnqueue = { playerController.enqueue(listOf(it)) },
                        onAddToPlaylist = { track ->
                            navController.navigate("${Screen.Playlists.route}?pickFor=${track.id}")
                        },
                        onSyncLyrics = {
                            playerController.refreshLyrics()
                        }
                    )
                }
                composable(Screen.Playlists.route) { entry ->
                    val pickFor = entry.arguments?.getString("pickFor")?.toLongOrNull()
                    PlaylistsScreen(
                        repository = app.musicRepository,
                        pickTrackId = pickFor,
                        onDismissPicker = {
                            navController.popBackStack()
                        },
                        onOpenPlaylist = { id ->
                            navController.navigate("playlistDetail/$id")
                        }
                    )
                }
                composable("playlistDetail/{playlistId}") { entry ->
                    val id = entry.arguments?.getString("playlistId")?.toLongOrNull() ?: return@composable
                    PlaylistDetailRoute(
                        playlistId = id,
                        repository = app.musicRepository,
                        playerController = playerController,
                        onBack = { navController.popBackStack() },
                        onAddSongs = { playlistId ->
                            navController.navigate("addToPlaylist/$playlistId")
                        }
                    )
                }
                composable("addToPlaylist/{playlistId}") { entry ->
                    val id = entry.arguments?.getString("playlistId")?.toLongOrNull() ?: return@composable
                    AddToPlaylistRoute(
                        playlistId = id,
                        repository = app.musicRepository,
                        onDone = { navController.popBackStack() }
                    )
                }
                composable(Screen.Equalizer.route) {
                    EqualizerScreen(playerController = playerController)
                }
                composable(Screen.NowPlaying.route) {
                    NowPlayingScreen(
                        playerController = playerController,
                        onClose = { navController.popBackStack() },
                        onOpenQueue = { showQueue = true },
                        onOpenEqualizer = { showEqualizer = true }
                    )
                }
            }
        }
    }

    if (showEqualizer) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showEqualizer = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                EqualizerScreen(
                    playerController = playerController,
                    embedded = true,
                    onClose = { showEqualizer = false }
                )
            }
        }
    }

    if (showQueue) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showQueue = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                QueueScreen(
                    playerController = playerController,
                    onClose = { showQueue = false }
                )
            }
        }
    }
}

@Composable
private fun MelodifyBottomBar(
    currentRoute: String?,
    onSelect: (String) -> Unit
) {
    val items = listOf(Screen.Library, Screen.Playlists, Screen.Equalizer)
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp
    ) {
        items.forEach { screen ->
            val selected = currentRoute == screen.route
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(screen.route) },
                icon = {
                    Icon(
                        imageVector = if (selected) screen.selectedIcon else screen.icon,
                        contentDescription = screen.label
                    )
                },
                label = { Text(screen.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
