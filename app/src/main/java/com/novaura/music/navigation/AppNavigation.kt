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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.novaura.music.data.Song
import com.novaura.music.data.ThemePreferences
import com.novaura.music.ui.components.BottomNavBar
import com.novaura.music.ui.components.CustomBackgroundBox
import com.novaura.music.ui.components.NowPlayingBar
import com.novaura.music.ui.components.OtrosMenuBottomSheet
import com.novaura.music.ui.components.SongDetailsModal
import com.novaura.music.ui.components.ThemeCustomizerModal
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
    val context = LocalContext.current
    val themePrefs = remember { ThemePreferences(context) }

    var bgImageUri by remember { mutableStateOf(themePrefs.bgImageUri) }
    var bgDarkness by remember { mutableStateOf(themePrefs.bgDarkness) }
    var bgBlur by remember { mutableStateOf(themePrefs.bgBlur) }
    var isDarkMode by remember { mutableStateOf(themePrefs.isDarkMode) }

    var showOtrosMenu by remember { mutableStateOf(false) }
    var showThemeCustomizer by remember { mutableStateOf(false) }
    var dialogTitle by remember { mutableStateOf<String?>(null) }
    var dialogMessage by remember { mutableStateOf<String?>(null) }

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

    if (showOtrosMenu) {
        OtrosMenuBottomSheet(
            isDarkMode = isDarkMode,
            onSortClick = {
                dialogTitle = "Ordenado por"
                dialogMessage = "Próximamente podrás cambiar el orden predeterminado desde aquí."
            },
            onDarkModeToggle = {
                val newMode = !isDarkMode
                isDarkMode = newMode
                themePrefs.isDarkMode = newMode
            },
            onThemesClick = { showThemeCustomizer = true },
            onCutsClick = {
                dialogTitle = "Lista de cortes"
                dialogMessage = "No tienes ningún tono o corte de canción guardado en esta sección."
            },
            onHiddenListClick = {
                dialogTitle = "Lista oculta"
                dialogMessage = "No hay canciones ni carpetas ocultas en tu biblioteca."
            },
            onRemoveAdsClick = {
                dialogTitle = "Eliminar anuncios"
                dialogMessage = "¡Novaura Music Player v1.0.0 es 100% gratuito y sin anuncios!"
            },
            onSettingsClick = {
                dialogTitle = "Configuración"
                dialogMessage = "Novaura v1.0.0 - Todos los ajustes están optimizados para el mejor rendimiento."
            },
            onDeveloperAppsClick = {
                dialogTitle = "Más aplicaciones"
                dialogMessage = "¡Gracias por usar Novaura Music Player! Disfruta de la mejor experiencia musical."
            },
            onDismiss = { showOtrosMenu = false }
        )
    }

    if (showThemeCustomizer) {
        ThemeCustomizerModal(
            currentImageUri = bgImageUri,
            currentDarkness = bgDarkness,
            currentBlur = bgBlur,
            onImageSelected = { uri ->
                val uriStr = uri.toString()
                bgImageUri = uriStr
                themePrefs.bgImageUri = uriStr
            },
            onDarknessChanged = { darkness ->
                bgDarkness = darkness
                themePrefs.bgDarkness = darkness
            },
            onBlurChanged = { blur ->
                bgBlur = blur
                themePrefs.bgBlur = blur
            },
            onResetTheme = {
                themePrefs.resetTheme()
                bgImageUri = null
                bgDarkness = 0.5f
                bgBlur = 10.0f
            },
            onDismiss = { showThemeCustomizer = false }
        )
    }

    dialogMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = {
                dialogTitle = null
                dialogMessage = null
            },
            title = { Text(dialogTitle ?: "Información") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    dialogTitle = null
                    dialogMessage = null
                }) {
                    Text("Aceptar")
                }
            }
        )
    }

    CustomBackgroundBox(
        bgImageUri = bgImageUri,
        darkness = bgDarkness,
        blurRadius = bgBlur,
        modifier = Modifier.fillMaxSize()
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = if (bgImageUri.isNullOrEmpty()) MaterialTheme.colorScheme.background else Color.Transparent
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
                                onRefresh = viewModel::refreshSongs,
                                onOpenOtrosMenu = { showOtrosMenu = true }
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
}