package com.novaura.music.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.novaura.music.ui.components.BottomNavBar
import com.novaura.music.ui.components.NowPlayingBar
import com.novaura.music.ui.screens.NowPlayingScreen
import com.novaura.music.ui.screens.PlaceholderScreen
import com.novaura.music.ui.screens.SongListScreen
import com.novaura.music.viewmodel.PlayerViewModel

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val viewModel: PlayerViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var activeTab by rememberSaveable { mutableStateOf(HomeTab.SONGS) }
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

    Column(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = SongListRoute,
            modifier = Modifier.weight(1f).fillMaxWidth()
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

            composable<ArtistListRoute> { PlaceholderScreen(HomeTab.ARTISTS) }
            composable<AlbumListRoute> { PlaceholderScreen(HomeTab.ALBUMS) }
            composable<PlaylistListRoute> { PlaceholderScreen(HomeTab.PLAYLISTS) }
            composable<GenreListRoute> { PlaceholderScreen(HomeTab.GENRES) }
            composable<FolderListRoute> { PlaceholderScreen(HomeTab.FOLDERS) }

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

        if (!inPlayer) {
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
                    onClick = { navController.navigate(NowPlayingRoute) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        BottomNavBar(
            selectedTab = activeTab,
            onTabSelected = ::selectTab
        )
    }
}