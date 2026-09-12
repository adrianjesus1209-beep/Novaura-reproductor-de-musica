package com.novaura.music.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.novaura.music.data.Song
import com.novaura.music.ui.components.BottomNavBar
import com.novaura.music.ui.components.NowPlayingBar
import com.novaura.music.ui.components.SongDetailsModal
import com.novaura.music.ui.screens.AlbumListScreen
import com.novaura.music.ui.screens.ArtistListScreen
import com.novaura.music.ui.screens.FolderListScreen
import com.novaura.music.ui.screens.NowPlayingScreen
import com.novaura.music.ui.screens.PlaceholderScreen
import com.novaura.music.ui.screens.PlaylistListScreen
import com.novaura.music.ui.screens.SongListScreen
import com.novaura.music.viewmodel.PlayerViewModel

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val viewModel: PlayerViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var activeTab by rememberSaveable { mutableStateOf(HomeTab.SONGS) }
    var selectedSongForDetails by remember { mutableStateOf<Song?>(null) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val inPlayer = backStackEntry?.destination?.hasRoute(NowPlayingRoute::class) == true

    fun selectTab(tab: HomeTab) {
        if (tab == activeTab) return
        activeTab = tab
        navController.navigate(tab.routeObject()) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    selectedSongForDetails?.let { song ->
        SongDetailsModal(
            song = song,
            onDismiss = { selectedSongForDetails = null },
            onPlay = { viewModel.playSong(it) }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                NavHost(
                    navController = navController,
                    startDestination = SongListRoute,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable<SongListRoute> {
                        SongListScreen(
                            uiState = uiState,
                            onSongClick = { song ->
                                viewModel.playSong(song)
                                navController.navigate(NowPlayingRoute)
                            },
                            onRefresh = viewModel::refreshSongs
                        )
                    }

                    composable<ArtistListRoute> {
                        ArtistListScreen(
                            uiState = uiState,
                            onSongClick = { song ->
                                viewModel.playSong(song)
                                navController.navigate(NowPlayingRoute)
                            },
                            onMoreClick = { selectedSongForDetails = it }
                        )
                    }

                    composable<AlbumListRoute> {
                        AlbumListScreen(
                            uiState = uiState,
                            onSongClick = { song ->
                                viewModel.playSong(song)
                                navController.navigate(NowPlayingRoute)
                            },
                            onMoreClick = { selectedSongForDetails = it }
                        )
                    }

                    composable<PlaylistListRoute> {
                        PlaylistListScreen(
                            uiState = uiState,
                            onSongClick = { song ->
                                viewModel.playSong(song)
                                navController.navigate(NowPlayingRoute)
                            },
                            onMoreClick = { selectedSongForDetails = it }
                        )
                    }

                    composable<GenreListRoute> { PlaceholderScreen(HomeTab.GENRES) }

                    composable<FolderListRoute> {
                        FolderListScreen(
                            uiState = uiState,
                            onSongClick = { song ->
                                viewModel.playSong(song)
                                navController.navigate(NowPlayingRoute)
                            },
                            onMoreClick = { selectedSongForDetails = it }
                        )
                    }

                    composable<NowPlayingRoute> {
                        NowPlayingScreen(
                            uiState = uiState,
                            onBack = { navController.popBackStack() },
                            onPlayPause = viewModel::playPause,
                            onNext = viewModel::nextSong,
                            onPrevious = viewModel::previousSong,
                            onSeek = viewModel::seekTo,
                            onShuffleChange = viewModel::setShuffle,
                            onRepeatClick = viewModel::cycleRepeatMode
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = !inPlayer && uiState.currentSong != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                uiState.currentSong?.let { current ->
                    val progressFraction = if (uiState.durationMs > 0) {
                        uiState.currentPositionMs.toFloat() / uiState.durationMs.toFloat()
                    } else 0f

                    NowPlayingBar(
                        song = current,
                        isPlaying = uiState.isPlaying,
                        progressFraction = progressFraction,
                        onPlayPause = viewModel::playPause,
                        onNext = viewModel::nextSong,
                        onPrevious = viewModel::previousSong,
                        onClick = { navController.navigate(NowPlayingRoute) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (!inPlayer) {
                BottomNavBar(
                    selectedTab = activeTab,
                    onTabSelected = ::selectTab
                )
            }
        }
    }
}