package com.novaura.music.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.novaura.music.ui.screens.NowPlayingScreen
import com.novaura.music.ui.screens.SongListScreen
import com.novaura.music.viewmodel.PlayerViewModel

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val viewModel: PlayerViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    NavHost(
        navController = navController,
        startDestination = SongListRoute
    ) {
        composable<SongListRoute> {
            SongListScreen(
                uiState = uiState,
                onSongClick = { song ->
                    viewModel.playSong(song)
                    navController.navigate(NowPlayingRoute)
                },
                onNowPlayingClick = { navController.navigate(NowPlayingRoute) },
                onPlayPause = viewModel::playPause,
                onRefresh = viewModel::refreshSongs
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