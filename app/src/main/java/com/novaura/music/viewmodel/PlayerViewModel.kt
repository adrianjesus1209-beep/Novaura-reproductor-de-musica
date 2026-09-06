package com.novaura.music.viewmodel

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.novaura.music.data.LibraryRepository
import com.novaura.music.data.Song
import com.novaura.music.scanner.MusicScanner
import com.novaura.music.service.MusicService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerUiState(
    val songs: List<Song> = emptyList(),
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isLoading: Boolean = true
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var controller: MediaController? = null
    private var pendingPlaySong: Song? = null
    private var positionJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            syncPlayerInfo()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startPositionPolling() else stopPositionPolling()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _uiState.update {
                it.copy(
                    isLoading = playbackState == Player.STATE_BUFFERING,
                    durationMs = (controller?.duration ?: 0L).coerceAtLeast(0L)
                )
            }
            if (playbackState != Player.STATE_READY && playbackState != Player.STATE_BUFFERING) {
                stopPositionPolling()
            }
        }
    }

    init {
        connectToService()
        refreshSongs()
    }

    fun refreshSongs() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val songs = withContext(Dispatchers.IO) {
                runCatching { MusicScanner.getAllSongs(getApplication()) }
                    .getOrDefault(emptyList())
            }
            LibraryRepository.songs = songs
            _uiState.update { it.copy(songs = songs, isLoading = false) }
            controller?.let { restorePlaylistIfNeeded(it) }
            syncPlayerInfo()
        }
    }

    fun playSong(song: Song) {
        val c = controller
        if (c == null) {
            pendingPlaySong = song
            return
        }
        val songs = LibraryRepository.songs.ifEmpty { _uiState.value.songs }
        if (songs.isEmpty()) return

        val startIndex = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        c.setMediaItems(songs.toMediaItems(), startIndex, 0L)
        c.prepare()
        c.play()
    }

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun nextSong() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) {
            c.seekToNextMediaItem()
            c.play()
        } else {
            c.seekTo(0, 0L)
            c.pause()
        }
    }

    fun previousSong() {
        val c = controller ?: return
        if (c.currentPosition > 3_000L || !c.hasPreviousMediaItem()) {
            c.seekTo(c.currentMediaItemIndex, 0L)
            c.play()
        } else {
            c.seekToPreviousMediaItem()
            c.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        c.seekTo(c.currentMediaItemIndex, positionMs)
        _uiState.update { it.copy(currentPositionMs = positionMs) }
    }

    // ------------------------------------------------------------------
    // Conexión con el MusicService (MediaSession)
    // ------------------------------------------------------------------

    private fun connectToService() {
        val sessionToken = SessionToken(
            getApplication(),
            ComponentName(getApplication(), MusicService::class.java)
        )

        val future = MediaController.Builder(getApplication(), sessionToken).buildAsync()
        future.addListener(
            {
                val mediaController = try {
                    future.get()
                } catch (e: Exception) {
                    null
                }
                if (mediaController == null) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@addListener
                }

                controller = mediaController
                mediaController.addListener(playerListener)

                pendingPlaySong?.let {
                    pendingPlaySong = null
                    playSong(it)
                }
                restorePlaylistIfNeeded(mediaController)
                syncPlayerInfo()
            },
            ContextCompat.getMainExecutor(getApplication())
        )
    }

    /**
     * Si el proceso del servicio se reinició y nosotros tenemos el catálogo
     * en memoria, restauramos la lista de reproducción.
     */
    private fun restorePlaylistIfNeeded(c: MediaController) {
        if (c.mediaItemCount == 0 && LibraryRepository.songs.isNotEmpty()) {
            c.setMediaItems(LibraryRepository.songs.toMediaItems(), 0, 0L)
            c.prepare()
        }
    }

    private fun syncPlayerInfo() {
        val c = controller ?: return
        val mediaId = c.currentMediaItem?.mediaId
        val currentSong = (LibraryRepository.songs + _uiState.value.songs)
            .firstOrNull { it.id.toString() == mediaId }

        _uiState.update {
            it.copy(
                currentSong = currentSong,
                currentPositionMs = c.currentPosition,
                durationMs = c.duration.coerceAtLeast(0L),
                isPlaying = c.isPlaying,
                isLoading = c.playbackState == Player.STATE_BUFFERING
            )
        }

        if (c.isPlaying) startPositionPolling() else stopPositionPolling()
    }

    private fun startPositionPolling() {
        if (positionJob?.isActive == true) return
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (isActive) {
                delay(500L)
                val c = controller ?: break
                _uiState.update {
                    it.copy(
                        currentPositionMs = c.currentPosition,
                        durationMs = c.duration.coerceAtLeast(0L)
                    )
                }
            }
        }
    }

    private fun stopPositionPolling() {
        positionJob?.cancel()
        positionJob = null
    }

    override fun onCleared() {
        stopPositionPolling()
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        super.onCleared()
    }

    private fun List<Song>.toMediaItems(): List<MediaItem> =
        map { song ->
            MediaItem.Builder()
                .setUri(song.uri)
                .setMediaId(song.id.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .setArtworkUri(song.albumArtUri)
                        .build()
                )
                .build()
        }
}