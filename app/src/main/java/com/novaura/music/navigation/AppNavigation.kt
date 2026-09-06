package com.novaura.music.navigation

import androidx.compose.runtime.Composable
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

    NavHost(
        navController = navController,
        startDestination = "song_list"
    ) {
        composable("song_list") {
            SongListScreen(
                viewModel = viewModel,
                onSongClick = { song ->
                    viewModel.playSong(song)
                    navController.navigate("now_playing")
                },
                onNowPlayingClick = {
                    navController.navigate("now_playing")
                }
            )
        }

        composable("now_playing") {
            NowPlayingScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}