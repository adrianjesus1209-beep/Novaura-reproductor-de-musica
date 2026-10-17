/*
 * Copyright (c) 2026 Auxio Project
 * OnlinePlayerManager.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.download

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber as L

data class OnlinePlayerState(
    val song: OnlineSong? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
)

@Singleton
class OnlinePlayerManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val downloadRepository: DownloadRepository,
) : Player.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var exoPlayer: ExoPlayer? = null
    private var positionJob: Job? = null
    private var streamExtractionJob: Job? = null

    private val queue = mutableListOf<OnlineSong>()
    private var currentIndex = -1

    private val _playerState = MutableStateFlow(OnlinePlayerState())
    val playerState: StateFlow<OnlinePlayerState> = _playerState.asStateFlow()

    private val _playerError = MutableSharedFlow<String>()
    val playerError: SharedFlow<String> = _playerError.asSharedFlow()

    private fun getOrCreatePlayer(): ExoPlayer {
        val existing = exoPlayer
        if (existing != null) return existing

        val audioAttributes =
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build()

        return ExoPlayer.Builder(context).setAudioAttributes(audioAttributes, true).build().also {
            it.addListener(this)
            exoPlayer = it
        }
    }

    fun playQueue(songs: List<OnlineSong>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        queue.clear()
        queue.addAll(songs)
        val validIndex = startIndex.coerceIn(0, songs.lastIndex)
        currentIndex = validIndex
        play(songs[validIndex])
    }

    fun play(song: OnlineSong) {
        val current = _playerState.value
        if (current.song?.webUrl == song.webUrl) {
            // Same song: toggle play/pause
            if (current.isPlaying) {
                pause()
            } else {
                resume()
            }
            return
        }

        // Update queue indexing if song is in queue
        val foundIndex = queue.indexOfFirst { it.webUrl == song.webUrl }
        if (foundIndex >= 0) {
            currentIndex = foundIndex
        } else {
            queue.clear()
            queue.add(song)
            currentIndex = 0
        }

        // New song to play
        streamExtractionJob?.cancel()
        positionJob?.cancel()

        _playerState.value =
            OnlinePlayerState(
                song = song,
                isPlaying = false,
                isLoading = true,
                durationMs = song.durationSeconds * 1000L,
                hasNext = currentIndex < queue.lastIndex,
                hasPrevious = currentIndex > 0,
            )

        streamExtractionJob = scope.launch {
            val stream =
                withContext(Dispatchers.IO) {
                    downloadRepository.extractAudioStreamUrl(song.webUrl)
                }

            if (stream == null || stream.content.isBlank()) {
                L.e("Failed to extract online stream for ${song.title}")
                _playerState.value = OnlinePlayerState()
                _playerError.emit(song.title)
                return@launch
            }

            val player = getOrCreatePlayer()
            val mediaItem = MediaItem.fromUri(stream.content)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()

            _playerState.value =
                _playerState.value.copy(
                    isPlaying = true,
                    isLoading = false,
                    durationMs =
                        if (player.duration > 0) player.duration else song.durationSeconds * 1000L,
                    hasNext = currentIndex < queue.lastIndex,
                    hasPrevious = currentIndex > 0,
                )

            startPositionTracker()
        }
    }

    fun playNext() {
        if (currentIndex < queue.lastIndex) {
            currentIndex++
            play(queue[currentIndex])
        }
    }

    fun playPrevious() {
        if (currentIndex > 0) {
            currentIndex--
            play(queue[currentIndex])
        } else if (_playerState.value.positionMs > 3000L) {
            seekTo(0)
        }
    }

    fun pause() {
        exoPlayer?.pause()
        _playerState.value = _playerState.value.copy(isPlaying = false)
        positionJob?.cancel()
    }

    fun resume() {
        val player = exoPlayer ?: return
        player.play()
        _playerState.value = _playerState.value.copy(isPlaying = true)
        startPositionTracker()
    }

    fun seekTo(positionMs: Long) {
        val player = exoPlayer ?: return
        player.seekTo(positionMs)
        _playerState.value = _playerState.value.copy(positionMs = positionMs)
    }

    fun stop() {
        streamExtractionJob?.cancel()
        positionJob?.cancel()
        exoPlayer?.stop()
        _playerState.value = OnlinePlayerState()
    }

    private fun startPositionTracker() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                val player = exoPlayer
                if (player != null && player.isPlaying) {
                    val pos = player.currentPosition
                    val dur =
                        if (player.duration > 0) player.duration else _playerState.value.durationMs
                    _playerState.value =
                        _playerState.value.copy(
                            positionMs = pos,
                            durationMs = dur,
                            isPlaying = true,
                            isLoading = false,
                            hasNext = currentIndex < queue.lastIndex,
                            hasPrevious = currentIndex > 0,
                        )
                }
                delay(300)
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val current = _playerState.value
        if (current.song != null) {
            _playerState.value = current.copy(isPlaying = isPlaying)
            if (isPlaying) {
                startPositionTracker()
            } else {
                positionJob?.cancel()
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> {
                val player = exoPlayer ?: return
                _playerState.value =
                    _playerState.value.copy(
                        isLoading = false,
                        durationMs =
                            if (player.duration > 0) player.duration
                            else _playerState.value.durationMs,
                    )
            }
            Player.STATE_BUFFERING -> {
                _playerState.value = _playerState.value.copy(isLoading = true)
            }
            Player.STATE_ENDED -> {
                positionJob?.cancel()
                if (currentIndex < queue.lastIndex) {
                    playNext()
                } else {
                    _playerState.value =
                        _playerState.value.copy(
                            isPlaying = false,
                            isLoading = false,
                            positionMs = _playerState.value.durationMs,
                        )
                }
            }
            Player.STATE_IDLE -> {}
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        L.e(error, "Online player error: ${error.message}")
        val songTitle = _playerState.value.song?.title.orEmpty()
        stop()
        if (songTitle.isNotEmpty()) {
            scope.launch { _playerError.emit(songTitle) }
        }
    }
}
