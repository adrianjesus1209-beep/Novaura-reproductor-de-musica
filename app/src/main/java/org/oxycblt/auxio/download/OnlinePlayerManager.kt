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
import android.content.Intent
import androidx.core.content.ContextCompat
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
import org.oxycblt.auxio.R
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import timber.log.Timber as L

data class OnlinePlayerState(
    val song: OnlineSong? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val isShuffle: Boolean = false,
    val isRepeat: Boolean = false,
    val queue: List<OnlineSong> = emptyList(),
    val queueIndex: Int = -1,
    val sourceTitle: String? = null,
)

@Singleton
class OnlinePlayerManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val downloadRepository: DownloadRepository,
    private val onlinePlaylistManager: OnlinePlaylistManager,
    private val playbackManager: PlaybackStateManager,
) : Player.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var exoPlayer: ExoPlayer? = null
    private var positionJob: Job? = null
    private var streamExtractionJob: Job? = null

    private val queue = mutableListOf<OnlineSong>()
    private var currentIndex = -1
    private var currentSourceTitle: String? = null

    private var isShuffle = false
    private var isRepeat = false
    @Volatile private var pendingPlayWhenReady = true
    private var lastLocalPlaying = false

    private val _playerState = MutableStateFlow(OnlinePlayerState())
    val playerState: StateFlow<OnlinePlayerState> = _playerState.asStateFlow()

    private val _playerError = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val playerError: SharedFlow<String> = _playerError.asSharedFlow()

    private val localPlaybackListener =
        object : PlaybackStateManager.Listener {
            override fun onProgressionChanged(progression: Progression) {
                val nowLocalPlaying = progression.isPlaying
                if (!lastLocalPlaying && nowLocalPlaying) {
                    val current = _playerState.value
                    if (current.isPlaying || (current.isLoading && pendingPlayWhenReady)) {
                        pause()
                    }
                }
                lastLocalPlaying = nowLocalPlaying
            }
        }

    init {
        lastLocalPlaying = playbackManager.progression.isPlaying
        playbackManager.addListener(localPlaybackListener)
    }

    private fun computeHasNext(): Boolean =
        queue.isNotEmpty() &&
            (currentIndex in 0 until queue.lastIndex || (isShuffle && queue.size > 1) || isRepeat)

    private fun computeHasPrevious(): Boolean =
        queue.isNotEmpty() && (currentIndex > 0 || (isShuffle && queue.size > 1) || isRepeat)

    private fun pauseLocalPlaybackIfActive() {
        if (playbackManager.progression.isPlaying) {
            playbackManager.playing(false)
        }
    }

    private fun ensureOnlinePlaybackService() {
        if (OnlinePlaybackService.isRunning) return
        val intent =
            Intent(context, OnlinePlaybackService::class.java).apply {
                action = OnlinePlaybackService.ACTION_START
            }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            L.w(e, "Unable to start OnlinePlaybackService")
        }
    }

    private fun getOrCreatePlayer(): ExoPlayer {
        val existing = exoPlayer
        if (existing != null) return existing

        val audioAttributes =
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build()

        return ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also {
                it.addListener(this)
                exoPlayer = it
            }
    }

    fun playQueue(
        songs: List<OnlineSong>,
        startIndex: Int = 0,
        sourceTitle: String? = null,
    ) {
        if (songs.isEmpty()) return
        currentSourceTitle = sourceTitle
        queue.clear()
        queue.addAll(songs)
        val validIndex = startIndex.coerceIn(0, songs.lastIndex)
        currentIndex = validIndex
        startPlayingSong(songs[validIndex])
    }

    fun play(song: OnlineSong) {
        val current = _playerState.value
        if (current.song?.webUrl == song.webUrl) {
            if (current.isPlaying || (current.isLoading && pendingPlayWhenReady)) {
                pause()
            } else {
                resume()
            }
            return
        }

        // Update queue indexing if song is in queue, otherwise append to session queue
        val foundIndex = queue.indexOfFirst { it.webUrl == song.webUrl }
        if (foundIndex >= 0) {
            currentIndex = foundIndex
        } else {
            currentSourceTitle = null
            queue.add(song)
            while (queue.size > 50) {
                queue.removeAt(0)
            }
            currentIndex = queue.lastIndex
        }

        startPlayingSong(song)
    }

    private fun startPlayingSong(song: OnlineSong) {
        pauseLocalPlaybackIfActive()
        onlinePlaylistManager.recordPlay(song)

        streamExtractionJob?.cancel()
        positionJob?.cancel()
        pendingPlayWhenReady = true

        _playerState.value =
            OnlinePlayerState(
                song = song,
                isPlaying = false,
                isLoading = true,
                durationMs = song.durationSeconds * 1000L,
                hasNext = computeHasNext(),
                hasPrevious = computeHasPrevious(),
                isShuffle = isShuffle,
                isRepeat = isRepeat,
                queue = queue.toList(),
                queueIndex = currentIndex,
                sourceTitle = currentSourceTitle,
            )
        ensureOnlinePlaybackService()

        streamExtractionJob = scope.launch {
            val stream =
                withContext(Dispatchers.IO) {
                    downloadRepository.extractAudioStreamUrl(song.webUrl)
                }

            if (stream == null || stream.content.isBlank()) {
                L.e("Failed to extract online stream for ${song.title}")
                _playerState.value =
                    _playerState.value.copy(
                        isPlaying = false,
                        isLoading = false,
                        hasNext = computeHasNext(),
                        hasPrevious = computeHasPrevious(),
                    )
                _playerError.emit("${context.getString(R.string.lbl_stream_error)}: ${song.title}")
                return@launch
            }

            val player = getOrCreatePlayer()
            val shouldPlayNow = pendingPlayWhenReady
            if (shouldPlayNow) {
                pauseLocalPlaybackIfActive()
                ensureOnlinePlaybackService()
            }
            player.playWhenReady = shouldPlayNow
            val mediaItem = MediaItem.fromUri(stream.content)
            player.setMediaItem(mediaItem)
            player.prepare()
            if (shouldPlayNow) {
                player.play()
            }

            _playerState.value =
                _playerState.value.copy(
                    isPlaying = shouldPlayNow,
                    isLoading = false,
                    durationMs =
                        if (player.duration > 0) player.duration else song.durationSeconds * 1000L,
                    hasNext = computeHasNext(),
                    hasPrevious = computeHasPrevious(),
                    queue = queue.toList(),
                    queueIndex = currentIndex,
                )

            if (shouldPlayNow) {
                startPositionTracker()
            }
        }
    }

    fun playQueueIndex(index: Int) {
        if (index in queue.indices) {
            currentIndex = index
            startPlayingSong(queue[index])
        }
    }

    fun toggleShuffle(): Boolean {
        isShuffle = !isShuffle
        _playerState.value =
            _playerState.value.copy(
                isShuffle = isShuffle,
                hasNext = computeHasNext(),
                hasPrevious = computeHasPrevious(),
            )
        return isShuffle
    }

    fun toggleRepeat(): Boolean {
        isRepeat = !isRepeat
        _playerState.value =
            _playerState.value.copy(
                isRepeat = isRepeat,
                hasNext = computeHasNext(),
                hasPrevious = computeHasPrevious(),
            )
        return isRepeat
    }

    fun playNext() {
        if (queue.isEmpty()) return
        if (isShuffle && queue.size > 1) {
            val candidates = queue.indices.filter { it != currentIndex }
            val nextIndex = candidates.randomOrNull() ?: currentIndex
            currentIndex = nextIndex
            startPlayingSong(queue[currentIndex])
        } else if (currentIndex < queue.lastIndex) {
            currentIndex++
            startPlayingSong(queue[currentIndex])
        } else if (isRepeat) {
            currentIndex = 0
            startPlayingSong(queue[currentIndex])
        }
    }

    fun playPrevious() {
        if (queue.isEmpty()) return
        if (_playerState.value.positionMs > 3000L) {
            seekTo(0)
        } else if (currentIndex > 0) {
            currentIndex--
            startPlayingSong(queue[currentIndex])
        } else if (isRepeat) {
            currentIndex = queue.lastIndex
            startPlayingSong(queue[currentIndex])
        } else if (isShuffle && queue.size > 1) {
            val candidates = queue.indices.filter { it != currentIndex }
            val prevIndex = candidates.randomOrNull() ?: currentIndex
            currentIndex = prevIndex
            startPlayingSong(queue[currentIndex])
        }
    }

    fun pause() {
        pendingPlayWhenReady = false
        exoPlayer?.pause()
        _playerState.value = _playerState.value.copy(isPlaying = false)
        positionJob?.cancel()
    }

    fun resume() {
        val currentSong = _playerState.value.song
        pauseLocalPlaybackIfActive()
        pendingPlayWhenReady = true
        ensureOnlinePlaybackService()

        val player = exoPlayer
        if (
            player == null ||
                player.playbackState == Player.STATE_IDLE ||
                player.mediaItemCount == 0
        ) {
            if (currentSong != null && !_playerState.value.isLoading) {
                startPlayingSong(currentSong)
            }
            return
        }

        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
        }
        player.play()
        _playerState.value = _playerState.value.copy(isPlaying = true)
        startPositionTracker()
    }

    fun seekTo(positionMs: Long) {
        val player = exoPlayer ?: return
        val dur = if (player.duration > 0) player.duration else _playerState.value.durationMs
        val safePos = positionMs.coerceIn(0L, dur.coerceAtLeast(0L))
        player.seekTo(safePos)
        _playerState.value = _playerState.value.copy(positionMs = safePos)
    }

    fun stop() {
        pendingPlayWhenReady = false
        streamExtractionJob?.cancel()
        positionJob?.cancel()
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        exoPlayer?.release()
        exoPlayer = null
        _playerState.value = OnlinePlayerState(isShuffle = isShuffle, isRepeat = isRepeat)
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
                            hasNext = computeHasNext(),
                            hasPrevious = computeHasPrevious(),
                        )
                }
                delay(100)
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
                if (isRepeat && queue.size == 1) {
                    seekTo(0)
                    resume()
                } else if (
                    currentIndex < queue.lastIndex ||
                        (isRepeat && queue.isNotEmpty()) ||
                        (isShuffle && queue.size > 1)
                ) {
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
        pendingPlayWhenReady = false
        streamExtractionJob?.cancel()
        positionJob?.cancel()
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        _playerState.value =
            _playerState.value.copy(
                isPlaying = false,
                isLoading = false,
                hasNext = computeHasNext(),
                hasPrevious = computeHasPrevious(),
            )
        if (songTitle.isNotEmpty()) {
            scope.launch {
                _playerError.emit("${context.getString(R.string.lbl_stream_error)}: $songTitle")
            }
        }
    }
}
