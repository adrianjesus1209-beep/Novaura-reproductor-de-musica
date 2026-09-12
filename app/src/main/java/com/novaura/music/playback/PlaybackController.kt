package com.novaura.music.playback

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.novaura.music.data.PlaybackState
import com.novaura.music.data.Song
import com.novaura.music.service.MusicService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Gestiona el ciclo de vida del [MediaController] que controla el
 * MediaSession del [MusicService]: conexión perezosa, comandos de
 * reproducción, observación del estado y polling de posición.
 *
 * Es la única pieza que toca Media3; el ViewModel solo orquesta.
 */
class PlaybackController(
    private val app: Application,
    private val scope: kotlinx.coroutines.CoroutineScope
) {

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var pendingPlaySong: Song? = null
    private var isConnecting = false
    private var catalog: List<Song> = emptyList()
    private var positionJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = syncFromController()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = syncFromController()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncFromController()
        }

        override fun onPlaybackStateChanged(playbackState: Int) = syncFromController()

        override fun onRepeatModeChanged(repeatMode: Int) = syncFromController()

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = syncFromController()
    }

    /**
     * Actualiza el catálogo en memoria que nos permite resolver el
     * [Song] actual a partir del [MediaItem.mediaId].
     */
    fun updateCatalog(songs: List<Song>) {
        catalog = songs
    }

    fun ensureConnected() {
        if (controller != null || isConnecting) return
        isConnecting = true
        connectToService()
    }

    private fun connectToService() {
        val future = MediaController.Builder(
            app,
            SessionToken(app, ComponentName(app, MusicService::class.java))
        ).buildAsync()

        future.addListener(
            {
                isConnecting = false
                val mediaController = try {
                    future.get()
                } catch (e: Exception) {
                    null
                }
                if (mediaController == null) return@addListener

                controller = mediaController
                mediaController.addListener(playerListener)

                pendingPlaySong?.let {
                    pendingPlaySong = null
                    playSong(it)
                }
                restorePlaylistIfNeeded(mediaController)
                syncFromController()
            },
            ContextCompat.getMainExecutor(app)
        )
    }

    fun playSong(song: Song, playlist: List<Song> = catalog) {
        catalog = playlist
        ensureConnected()
        val c = controller
        if (c == null) {
            pendingPlaySong = song
            return
        }
        if (playlist.isEmpty()) return

        val startIndex = playlist.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        c.setMediaItems(MediaItemMapper.toMediaItems(playlist), startIndex, 0L)
        c.prepare()
        c.play()
    }

    fun playPause() {
        ensureConnected()
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun nextSong() {
        ensureConnected()
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
        ensureConnected()
        val c = controller ?: return
        if (c.currentPosition > 3_000L || !c.hasPreviousMediaItem()) {
            c.seekTo(c.currentMediaItemIndex, 0L)
            if (!c.isPlaying) c.play()
        } else {
            c.seekToPreviousMediaItem()
            c.play()
        }
    }

    fun seekTo(positionMs: Long) {
        ensureConnected()
        val c = controller ?: return
        c.seekTo(c.currentMediaItemIndex, positionMs)
        _state.update { it.copy(currentPositionMs = positionMs) }
    }

    fun stop() {
        ensureConnected()
        val c = controller
        c?.stop()
        c?.clearMediaItems()
        stopPositionPolling()
        _state.update {
            it.copy(
                currentSong = null,
                isPlaying = false,
                currentPositionMs = 0L,
                durationMs = 0L
            )
        }
    }

    fun setShuffle(enabled: Boolean) {
        ensureConnected()
        controller?.setShuffleModeEnabled(enabled)
    }

    fun cycleRepeatMode() {
        ensureConnected()
        val c = controller ?: return
        c.repeatMode = nextRepeatMode(c.repeatMode)
    }

    /**
     * Si el proceso del servicio se reinició y nosotros tenemos el
     * catálogo en memoria, restauramos la lista de reproducción.
     */
    private fun restorePlaylistIfNeeded(c: MediaController) {
        if (c.mediaItemCount == 0 && catalog.isNotEmpty()) {
            c.setMediaItems(MediaItemMapper.toMediaItems(catalog), 0, 0L)
            c.prepare()
        }
    }

    /**
     * Fuente de verdad única: lee todo el estado del controlador y lo
     * vuelca en [PlaybackState]. Todos los callbacks del listener pasan
     * por aquí.
     */
    private fun syncFromController() {
        val c = controller ?: return
        val mediaId = c.currentMediaItem?.mediaId
        val currentSong = catalog.firstOrNull { it.id.toString() == mediaId }

        _state.update {
            it.copy(
                currentSong = currentSong,
                currentPositionMs = c.currentPosition,
                durationMs = c.duration.coerceAtLeast(0L),
                isPlaying = c.isPlaying,
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                repeatMode = c.repeatMode,
                shuffleEnabled = c.shuffleModeEnabled
            )
        }

        if (c.isPlaying) startPositionPolling() else stopPositionPolling()
    }

    private fun startPositionPolling() {
        if (positionJob?.isActive == true) return
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                delay(500L)
                val c = controller ?: break
                _state.update {
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

    fun release() {
        stopPositionPolling()
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
    }
}