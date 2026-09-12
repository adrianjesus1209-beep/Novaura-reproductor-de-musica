package com.novaura.music.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.novaura.music.data.LibraryState
import com.novaura.music.data.MusicRepository
import com.novaura.music.data.PlaybackState
import com.novaura.music.data.Song
import com.novaura.music.playback.PlaybackController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlayerUiState(
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = true,
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isBuffering: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false
)

/**
 * Orquestador entre la biblioteca ([MusicRepository]) y el reproductor
 * ([PlaybackController]). No contiene lógica de negocio: combina ambos
 * flujos en un único [PlayerUiState] para la UI.
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository()
    private val playback = PlaybackController(application, viewModelScope)

    val uiState: StateFlow<PlayerUiState> =
        combine(repository.state, playback.state) { library, player ->
            library.merge(player)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PlayerUiState()
        )

    init {
        refreshSongs()
    }

    fun refreshSongs() {
        viewModelScope.launch {
            repository.refresh(getApplication())
            playback.updateCatalog(repository.state.value.songs)
        }
    }

    fun playSong(song: Song) {
        playback.playSong(song, uiState.value.songs)
    }

    fun playPause() = playback.playPause()

    fun stopPlayback() = playback.stop()

    fun nextSong() = playback.nextSong()

    fun previousSong() = playback.previousSong()

    fun seekTo(positionMs: Long) = playback.seekTo(positionMs)

    fun setShuffle(enabled: Boolean) = playback.setShuffle(enabled)

    fun cycleRepeatMode() = playback.cycleRepeatMode()

    override fun onCleared() {
        playback.release()
        super.onCleared()
    }
}

private fun LibraryState.merge(player: PlaybackState) = PlayerUiState(
    songs = songs,
    isLoading = isLoading,
    currentSong = player.currentSong,
    isPlaying = player.isPlaying,
    currentPositionMs = player.currentPositionMs,
    durationMs = player.durationMs,
    isBuffering = player.isBuffering,
    repeatMode = player.repeatMode,
    shuffleEnabled = player.shuffleEnabled
)