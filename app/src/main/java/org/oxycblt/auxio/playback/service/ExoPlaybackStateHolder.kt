/*
 * Copyright (c) 2024 Auxio Project
 * ExoPlaybackStateHolder.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.service

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.oxycblt.auxio.image.ImageSettings
import org.oxycblt.auxio.music.FavoritesManager
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.playback.EqualizerController
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.playback.audio.AudioLevelProcessor
import org.oxycblt.auxio.playback.persist.PersistenceRepository
import org.oxycblt.auxio.playback.replaygain.ReplayGainAudioProcessor
import org.oxycblt.auxio.playback.state.DeferredPlayback
import org.oxycblt.auxio.playback.state.PlaybackCommand
import org.oxycblt.auxio.playback.state.PlaybackStateHolder
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.auxio.playback.state.RawQueue
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.playback.state.ShuffleMode
import org.oxycblt.auxio.playback.state.StateAck
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

@OptIn(UnstableApi::class)
class ExoPlaybackStateHolder(
    private val context: Context,
    private val player: ExoPlayer,
    private val playbackManager: PlaybackStateManager,
    private val persistenceRepository: PersistenceRepository,
    private val playbackSettings: PlaybackSettings,
    private val commandFactory: PlaybackCommand.Factory,
    private val replayGainProcessor: ReplayGainAudioProcessor,
    private val musicRepository: MusicRepository,
    private val imageSettings: ImageSettings,
    private val favoritesManager: FavoritesManager,
    private val equalizerController: EqualizerController,
) :
    PlaybackStateHolder,
    Player.Listener,
    MusicRepository.UpdateListener,
    PlaybackSettings.Listener,
    ImageSettings.Listener {
    private val saveJob = Job()
    private val saveScope = CoroutineScope(Dispatchers.IO + saveJob)
    private val restoreScope = CoroutineScope(Dispatchers.IO + saveJob)
    private var currentSaveJob: Job? = null
    private var openAudioEffectSession = false
    private val failedMediaItemIndices = mutableSetOf<Int>()

    var sessionOngoing = false
        private set

    fun attach() {
        playbackManager.registerStateHolder(this)
        musicRepository.addUpdateListener(this)
        player.addListener(this)
        replayGainProcessor.attach()
        playbackSettings.registerListener(this)
        imageSettings.registerListener(this)
        equalizerController.attachSession(player.audioSessionId)
    }

    fun release() {
        val pendingSnapshot = if (sessionOngoing) playbackManager.toSavedState() else null
        currentSaveJob?.cancel()
        currentSaveJob = null
        saveJob.cancel()
        if (pendingSnapshot != null) {
            CoroutineScope(Dispatchers.IO + NonCancellable).launch {
                persistenceRepository.saveState(pendingSnapshot)
            }
        }
        equalizerController.releaseSession()
        playbackManager.unregisterStateHolder(this)
        musicRepository.removeUpdateListener(this)
        player.removeListener(this)
        replayGainProcessor.release()
        imageSettings.unregisterListener(this)
        playbackSettings.unregisterListener(this)
        player.release()
    }

    override var parent: MusicParent? = null
        private set

    override val progression: Progression
        get() {
            val mediaItem = player.currentMediaItem ?: return Progression.nil()
            val extras = mediaItem.mediaMetadata.extras
            val duration =
                if (extras != null && extras.containsKey(EXTRA_DURATION_MS)) {
                    extras.getLong(EXTRA_DURATION_MS).coerceAtLeast(0L)
                } else {
                    Long.MAX_VALUE
                }
            val clampedPosition = player.currentPosition.coerceIn(0L, duration)
            return Progression.from(player.playWhenReady, player.isPlaying, clampedPosition)
        }

    override val repeatMode
        get() =
            when (val repeatMode = player.repeatMode) {
                Player.REPEAT_MODE_OFF -> RepeatMode.NONE
                Player.REPEAT_MODE_ONE -> RepeatMode.TRACK
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> throw IllegalStateException("Unknown repeat mode: $repeatMode")
            }

    override val audioSessionId: Int
        get() = player.audioSessionId

    override fun resolveQueue(): RawQueue {
        if (musicRepository.library == null) {
            // No library, cannot do anything.
            return RawQueue(emptyList(), emptyList(), 0)
        }
        val heap = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val shuffledMapping =
            if (player.shuffleModeEnabled) {
                player.unscrambleQueueIndices()
            } else {
                emptyList()
            }
        return RawQueue(heap.mapNotNull { it.song }, shuffledMapping, player.currentMediaItemIndex)
    }

    override fun handleDeferred(action: DeferredPlayback): Boolean {
        val library =
            musicRepository.library?.takeIf { !it.empty() }
                // No library, cannot do anything.
                ?: return false

        when (action) {
            // Restore state -> Start a new restoreState job
            is DeferredPlayback.RestoreState -> {
                L.d("Restoring playback state")
                restoreScope.launch {
                    val state = persistenceRepository.readState()
                    withContext(Dispatchers.Main) {
                        if (state != null) {
                            // Apply the saved state on the main thread to prevent code expecting
                            // state updates on the main thread from crashing.
                            playbackManager.applySavedState(state, false)
                            if (action.play) {
                                playbackManager.playing(true)
                            }
                        } else if (action.fallback != null) {
                            playbackManager.playDeferred(action.fallback)
                        }
                    }
                }
            }
            // Shuffle all -> Start new playback from all songs
            is DeferredPlayback.ShuffleAll -> {
                L.d("Shuffling all tracks")
                playbackManager.play(
                    requireNotNull(commandFactory.all(ShuffleMode.ON)) {
                        "Invalid playback parameters"
                    }
                )
            }
            // Smart play -> Play favorites if present, otherwise play all songs
            is DeferredPlayback.PlaySmart -> {
                L.d("Smart playback: Trying favorites, then all songs")
                val favs = favoritesManager.favoriteSongs.value
                if (favs.isNotEmpty()) {
                    playbackManager.play(
                        requireNotNull(commandFactory.songs(favs, ShuffleMode.OFF)) {
                            "Invalid playback parameters"
                        }
                    )
                } else {
                    playbackManager.play(
                        requireNotNull(commandFactory.all(ShuffleMode.OFF)) {
                            "Invalid playback parameters"
                        }
                    )
                }
            }
            // Open -> Try to find the Song for the given file and then play it from all songs
            is DeferredPlayback.Open -> {
                L.d("Opening specified file")
                context.applicationContext.contentResolver
                    .query(
                        action.uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    )
                    ?.use { cursor ->
                        val displayNameIndex =
                            cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            val displayName = cursor.getString(displayNameIndex)
                            val size = cursor.getLong(sizeIndex)
                            val song =
                                library.songs.find {
                                    it.path.name == displayName && it.size == size
                                }
                            if (song != null) {
                                val command =
                                    requireNotNull(
                                        commandFactory.songFromAll(song, ShuffleMode.IMPLICIT)
                                    ) {
                                        "Invalid playback command"
                                    }
                                playbackManager.play(command)
                            }
                        }
                    }
            }
        }

        return true
    }

    override fun playing(playing: Boolean) {
        player.playWhenReady = playing
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        deferSave()
        // Ack handled w/ExoPlayer events
    }

    override fun repeatMode(repeatMode: RepeatMode) {
        player.repeatMode =
            when (repeatMode) {
                RepeatMode.NONE -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.TRACK -> Player.REPEAT_MODE_ONE
            }
        updatePauseOnRepeat()
        playbackManager.ack(this, StateAck.RepeatModeChanged)
        deferSave()
    }

    override fun newPlayback(command: PlaybackCommand) {
        parent = command.parent
        failedMediaItemIndices.clear()
        val items = command.queue.map { it.buildMediaItem() }
        runCatching {
                // Force a clean player lifecycle per track, mirroring a MediaPlayer
                // stop() -> reset() -> setDataSource() -> prepareAsync() -> onPrepared -> start().
                // stop() drops the player back to the idle state so no residual error/prepared
                // state from a previous session can ever block the new queue.
                player.stop()
                player.clearMediaItems()
                player.shuffleModeEnabled = command.shuffled
                player.setMediaItems(items)
                val startIndex =
                    command.song
                        ?.let { command.queue.indexOf(it) }
                        .also { check(it != -1) { "Start song not in queue" } }
                if (command.shuffled) {
                    player.setShuffleOrder(BetterShuffleOrder(command.queue.size, startIndex ?: -1))
                }
                val target =
                    startIndex ?: player.currentTimeline.getFirstWindowIndex(command.shuffled)
                player.seekTo(target, C.TIME_UNSET)
                player.prepare()
                player.play()
            }
            .onFailure {
                L.e("[PLAYBACK_ERROR] Failed to start a new playback session", it)
                runCatching {
                    player.stop()
                    player.setMediaItems(listOf())
                }
            }
        playbackManager.ack(this, StateAck.NewPlayback)
        deferSave()
    }

    override fun shuffled(shuffled: Boolean) {
        player.setShuffleModeEnabled(shuffled)
        if (player.shuffleModeEnabled) {
            // Have to manually refresh the shuffle seed and anchor it to the new current songs
            player.setShuffleOrder(
                BetterShuffleOrder(player.mediaItemCount, player.currentMediaItemIndex)
            )
        }
        playbackManager.ack(this, StateAck.QueueReordered)
        deferSave()
    }

    override fun next() {
        // Replicate the old pseudo-circular queue behavior when no repeat option is implemented.
        // Basically, you can't skip back and wrap around the queue, but you can skip forward and
        // wrap around the queue, albeit playback will be paused.
        if (player.repeatMode == Player.REPEAT_MODE_ALL || player.hasNextMediaItem()) {
            player.seekToNext()
            if (!playbackSettings.rememberPause) {
                player.play()
            }
        } else {
            player.seekTo(
                player.currentTimeline.getFirstWindowIndex(player.shuffleModeEnabled),
                C.TIME_UNSET,
            )
            // TODO: Dislike the UX implications of this, I feel should I bite the bullet
            //  and switch to dynamic skip enable/disable?
            if (!playbackSettings.rememberPause) {
                player.pause()
            }
        }
        playbackManager.ack(this, StateAck.IndexMoved)
        deferSave()
    }

    override fun prev() {
        if (playbackSettings.rewindWithPrev) {
            player.seekToPrevious()
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            player.seekTo(0)
        }
        if (!playbackSettings.rememberPause) {
            player.play()
        }
        playbackManager.ack(this, StateAck.IndexMoved)
        deferSave()
    }

    override fun goto(index: Int) {
        val indices = player.unscrambleQueueIndices()
        if (index !in indices.indices) {
            return
        }

        val trueIndex = indices[index]
        player.seekTo(trueIndex, C.TIME_UNSET) // Handles remaining custom logic
        if (!playbackSettings.rememberPause) {
            player.play()
        }
        playbackManager.ack(this, StateAck.IndexMoved)
        deferSave()
    }

    override fun playNext(songs: List<Song>, ack: StateAck.PlayNext) {
        val nextIndex =
            if (player.mediaItemCount == 0 || player.currentMediaItemIndex == C.INDEX_UNSET) {
                C.INDEX_UNSET
            } else {
                (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
            }

        if (nextIndex == C.INDEX_UNSET) {
            player.addMediaItems(songs.map { it.buildMediaItem() })
        } else {
            player.addMediaItems(nextIndex, songs.map { it.buildMediaItem() })
        }
        playbackManager.ack(this, ack)
        deferSave()
    }

    override fun addToQueue(songs: List<Song>, ack: StateAck.AddToQueue) {
        player.addMediaItems(songs.map { it.buildMediaItem() })
        playbackManager.ack(this, ack)
        deferSave()
    }

    override fun move(from: Int, to: Int, ack: StateAck.Move) {
        val indices = player.unscrambleQueueIndices()
        if (from !in indices.indices || to !in indices.indices || from == to) {
            return
        }

        if (player.shuffleModeEnabled) {
            val reordered = indices.toMutableList()
            val moved = reordered.removeAt(from)
            reordered.add(to, moved)
            player.setShuffleOrder(BetterShuffleOrder(reordered.toIntArray()))
        } else {
            player.moveMediaItem(indices[from], indices[to])
        }
        playbackManager.ack(this, ack)
        deferSave()
    }

    override fun remove(at: Int, ack: StateAck.Remove) {
        val indices = player.unscrambleQueueIndices()
        if (at !in indices.indices) {
            return
        }

        val trueIndex = indices[at]
        val songWillChange = player.currentMediaItemIndex == trueIndex
        player.removeMediaItem(trueIndex)
        failedMediaItemIndices.clear()
        if (songWillChange && !playbackSettings.rememberPause) {
            player.play()
        }
        playbackManager.ack(this, ack)
        deferSave()
    }

    override fun applySavedState(
        parent: MusicParent?,
        rawQueue: RawQueue,
        positionMs: Long,
        repeatMode: RepeatMode,
        ack: StateAck.NewPlayback?,
    ) {
        var sendNewPlaybackEvent = false
        var shouldSeek = false
        if (this.parent != parent) {
            this.parent = parent
            sendNewPlaybackEvent = true
        }
        if (rawQueue != resolveQueue()) {
            val shouldKeepPlaying = player.mediaItemCount > 0 && player.playWhenReady
            failedMediaItemIndices.clear()
            player.setMediaItems(rawQueue.heap.map { it.buildMediaItem() })
            if (rawQueue.isShuffled) {
                player.shuffleModeEnabled = true
                player.setShuffleOrder(BetterShuffleOrder(rawQueue.shuffledMapping.toIntArray()))
            } else {
                player.shuffleModeEnabled = false
            }
            if (rawQueue.heapIndex in rawQueue.heap.indices) {
                player.seekTo(rawQueue.heapIndex, C.TIME_UNSET)
            }
            player.prepare()
            player.playWhenReady = shouldKeepPlaying
            sendNewPlaybackEvent = true
            shouldSeek = true
        }

        repeatMode(repeatMode)
        // See if we differ by more than a second. This allows us to avoid a meaningless seek
        // in the case of a "tight restore" (i.e music was reloaded).
        // In the case that this is a false positive, it's not very percievable (at least compared
        // to skipping when updating the library).
        // TODO: Introduce a better state management system rather than do something finicky like
        // this.
        if (shouldSeek || abs(player.currentPosition - positionMs) > 1000L) {
            player.seekTo(positionMs)
        }

        if (sendNewPlaybackEvent) {
            ack?.let { playbackManager.ack(this, it) }
        }
    }

    override fun endSession() {
        // This session has ended, so we need to reset this flag for when the next
        // session starts.
        playbackManager.playing(false)
        save {
            // User could feasibly start playing again if they were fast enough, so
            // we need to avoid stopping the foreground state if that's the case.
            if (!playbackManager.progression.isPlaying) {
                // Completely close out the session instead of merely pausing: stop the
                // player (releasing the audio focus) and clear the queue so that the
                // current track is removed from the UI as well.
                player.stop()
                player.setMediaItems(listOf())
                sessionOngoing = false
                playbackManager.ack(this, StateAck.NewPlayback)
                playbackManager.ack(this, StateAck.SessionEnded)
            }
        }
    }

    override fun reset(ack: StateAck.NewPlayback) {
        failedMediaItemIndices.clear()
        player.setMediaItems(listOf())
        playbackManager.ack(this, ack)
        deferSave()
    }

    // --- PLAYER OVERRIDES ---

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        super.onAudioSessionIdChanged(audioSessionId)
        equalizerController.attachSession(audioSessionId)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        super.onPlayWhenReadyChanged(playWhenReady, reason)

        if (player.playWhenReady) {
            // Mark that we have started playing so that the notification can now be posted.
            L.d("Player has started playing")
            sessionOngoing = true
            if (!openAudioEffectSession) {
                // Convention to start an audioeffect session on play/pause rather than
                // start/stop
                L.d("Opening audio effect session")
                broadcastAudioEffectAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                openAudioEffectSession = true
            }
        } else if (openAudioEffectSession) {
            // Make sure to close the audio session when we stop playback.
            L.d("Closing audio effect session")
            broadcastAudioEffectAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            openAudioEffectSession = false
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)

        if (playbackState == Player.STATE_READY) {
            failedMediaItemIndices.clear()
        } else if (
            playbackState == Player.STATE_ENDED && player.repeatMode == Player.REPEAT_MODE_OFF
        ) {
            player.pause()
            val firstQueueIndex = player.unscrambleQueueIndices().firstOrNull()
            if (firstQueueIndex != null && firstQueueIndex in 0 until player.mediaItemCount) {
                player.seekTo(firstQueueIndex, C.TIME_UNSET)
                playbackManager.ack(this, StateAck.IndexMoved)
                deferSave()
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)

        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playbackManager.ack(this, StateAck.IndexMoved)
            deferSave()
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        super.onEvents(player, events)

        // So many actions trigger progression changes that it becomes easier just to handle it
        // in an ExoPlayer callback anyway. This doesn't really cause issues anywhere.
        if (
            events.containsAny(
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
            )
        ) {
            L.d("Player state changed, must synchronize state")
            playbackManager.ack(this, StateAck.ProgressionChanged)
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val failingIndex = player.currentMediaItemIndex
        val failingUri = player.currentMediaItem?.localConfiguration?.uri

        // Log the failing item + reason so playback failures are diagnosable instead of
        // skipping silently.
        L.e("[PLAYBACK_ERROR] code=${error.errorCodeName} (${error.errorCode})")
        L.e("[PLAYBACK_ERROR] message=${error.message}")
        failingUri?.let {
            L.e("[PLAYBACK_ERROR] uri=$it scheme=${it.scheme ?: "null"}")
        }
        L.e("[PLAYBACK_ERROR] stacktrace:\n${error.stackTraceToString()}")

        // Avoid getting stuck retrying failed items infinitely (including under REPEAT_MODE_ALL
        // when >= 2 tracks fail consecutively).
        val repeatedFailure =
            failingIndex == C.INDEX_UNSET ||
                !failedMediaItemIndices.add(failingIndex) ||
                failedMediaItemIndices.size >= player.mediaItemCount
        if (repeatedFailure) {
            L.e("[PLAYBACK_ERROR] Repeated or total queue failure, stopping playback")
            stopAfterError()
            return
        }

        // If there's any issue, try advancing to the next item if available.
        if (player.hasNextMediaItem()) {
            L.d("[PLAYBACK_ERROR] Skipping to the next media item after failure")
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
            playbackManager.ack(this, StateAck.IndexMoved)
        } else {
            L.d("[PLAYBACK_ERROR] Last item failed with no next item, stopping playback")
            stopAfterError()
        }
    }

    private fun stopAfterError() {
        player.stop()
        player.setMediaItems(listOf())
        failedMediaItemIndices.clear()
        sessionOngoing = false
        playbackManager.ack(this, StateAck.NewPlayback)
        playbackManager.ack(this, StateAck.SessionEnded)
    }

    private fun broadcastAudioEffectAction(event: String) {
        L.d("Broadcasting AudioEffect event: $event")
        context.sendBroadcast(
            Intent(event)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        )
    }

    // --- MUSICREPOSITORY METHODS ---

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        if (changes.deviceLibrary && musicRepository.library?.takeIf { !it.empty() } != null) {
            // We now have a library, see if we have anything we need to do.
            L.d("Library obtained, requesting action")
            playbackManager.requestAction(this)
        }
    }

    // --- PLAYBACKSETTINGS OVERRIDES ---

    override fun onPauseOnRepeatChanged() {
        super.onPauseOnRepeatChanged()
        updatePauseOnRepeat()
    }

    private fun updatePauseOnRepeat() {
        player.pauseAtEndOfMediaItems =
            player.repeatMode == Player.REPEAT_MODE_ONE && playbackSettings.pauseOnRepeat
    }

    private fun save(cb: () -> Unit) {
        saveJob {
            if (sessionOngoing) {
                val snapshot = withContext(Dispatchers.Main) { playbackManager.toSavedState() }
                withContext(NonCancellable) { persistenceRepository.saveState(snapshot) }
            }
            withContext(Dispatchers.Main) { cb() }
        }
    }

    private fun deferSave() {
        saveJob {
            L.d("Waiting for save buffer")
            delay(SAVE_BUFFER.milliseconds)
            yield()
            L.d("Committing saved state")
            if (sessionOngoing) {
                val snapshot = withContext(Dispatchers.Main) { playbackManager.toSavedState() }
                withContext(NonCancellable) { persistenceRepository.saveState(snapshot) }
            }
        }
    }

    private fun saveJob(block: suspend () -> Unit) {
        currentSaveJob?.let {
            L.d("Discarding prior save job")
            it.cancel()
        }
        currentSaveJob = saveScope.launch { block() }
    }

    private fun Song.buildMediaItem() =
        MediaItem.Builder()
            .setUri(uri)
            .setTag(this)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setExtras(Bundle().apply { putLong(EXTRA_DURATION_MS, durationMs) })
                    .build()
            )
            .build()
            .also {
                val scheme = uri.scheme
                if (scheme != "content") {
                    // The player only accepts Content URIs built from MediaStore IDs. Flag any
                    // legacy file:///absolute-path URI so the blocking failure is diagnosable.
                    L.w(
                        "[PLAYBACK_ERROR] NON_CONTENT_URI song=\"$name\" uri=$uri " +
                            "scheme=${scheme ?: "null"}"
                    )
                }
            }

    private val MediaItem.song: Song?
        get() = this.localConfiguration?.tag as? Song?

    private fun Player.unscrambleQueueIndices(): List<Int> {
        val timeline = currentTimeline
        val currentMediaItemIndex = currentMediaItemIndex
        if (timeline.isEmpty || currentMediaItemIndex !in 0 until timeline.windowCount) {
            return emptyList()
        }
        val queue = mutableListOf<Int>()

        // Add the active queue item.
        queue.add(currentMediaItemIndex)

        // Fill queue alternating with next and/or previous queue items.
        var firstMediaItemIndex = currentMediaItemIndex
        var lastMediaItemIndex = currentMediaItemIndex
        val shuffleModeEnabled = shuffleModeEnabled
        while ((firstMediaItemIndex != C.INDEX_UNSET || lastMediaItemIndex != C.INDEX_UNSET)) {
            // Begin with next to have a longer tail than head if an even sized queue needs to be
            // trimmed.
            if (lastMediaItemIndex != C.INDEX_UNSET) {
                lastMediaItemIndex =
                    timeline.getNextWindowIndex(
                        lastMediaItemIndex,
                        Player.REPEAT_MODE_OFF,
                        shuffleModeEnabled,
                    )
                if (lastMediaItemIndex != C.INDEX_UNSET) {
                    queue.add(lastMediaItemIndex)
                }
            }
            if (firstMediaItemIndex != C.INDEX_UNSET) {
                firstMediaItemIndex =
                    timeline.getPreviousWindowIndex(
                        firstMediaItemIndex,
                        Player.REPEAT_MODE_OFF,
                        shuffleModeEnabled,
                    )
                if (firstMediaItemIndex != C.INDEX_UNSET) {
                    queue.add(0, firstMediaItemIndex)
                }
            }
        }

        return queue
    }

    class Factory
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val playbackManager: PlaybackStateManager,
        private val persistenceRepository: PersistenceRepository,
        private val playbackSettings: PlaybackSettings,
        private val commandFactory: PlaybackCommand.Factory,
        private val mediaSourceFactory: MediaSource.Factory,
        private val replayGainProcessor: ReplayGainAudioProcessor,
        private val audioLevelProcessor: AudioLevelProcessor,
        private val musicRepository: MusicRepository,
        private val imageSettings: ImageSettings,
        private val favoritesManager: FavoritesManager,
        private val equalizerController: EqualizerController,
    ) {
        fun create(): ExoPlaybackStateHolder {
            // Since Auxio is a music player, only specify an audio renderer to save
            // battery/apk size/cache size]
            val audioRenderer = RenderersFactory { handler, _, audioListener, _, _ ->
                arrayOf<BaseRenderer>(
                    MediaCodecAudioRenderer(
                        context,
                        MediaCodecSelector.DEFAULT,
                        handler,
                        audioListener,
                        DefaultAudioSink.Builder(context)
                            // ReplayGain first so the level meter reads the signal the user
                            // actually hears, gain adjustment included. The explicit type argument
                            // is required: without it Kotlin infers an intersection type from the
                            // two very different processor supertypes.
                            .setAudioProcessors(
                                arrayOf<AudioProcessor>(replayGainProcessor, audioLevelProcessor)
                            )
                            .build(),
                    )
                )
            }

            val exoPlayer =
                ExoPlayer.Builder(context, audioRenderer)
                    .setMediaSourceFactory(mediaSourceFactory)
                    // Enable automatic WakeLock support
                    .setWakeMode(C.WAKE_MODE_LOCAL)
                    .setAudioAttributes(
                        // Signal that we are a music player.
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        true,
                    )
                    .build()

            return ExoPlaybackStateHolder(
                context,
                exoPlayer,
                playbackManager,
                persistenceRepository,
                playbackSettings,
                commandFactory,
                replayGainProcessor,
                musicRepository,
                imageSettings,
                favoritesManager,
                equalizerController,
            )
        }
    }

    private companion object {
        const val SAVE_BUFFER = 5000L
        const val EXTRA_DURATION_MS = "durationMs"
    }
}
