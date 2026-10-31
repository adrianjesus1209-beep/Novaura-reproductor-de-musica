/*
 * Copyright (c) 2023 Auxio Project
 * PersistenceRepository.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.persist

import androidx.room.withTransaction
import javax.inject.Inject
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.musikr.MusicParent
import timber.log.Timber as L

/**
 * Manages the persisted playback state in a structured manner.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
interface PersistenceRepository {
    /** Read the previously persisted [PlaybackStateManager.SavedState]. */
    suspend fun readState(): PlaybackStateManager.SavedState?

    /**
     * Persist a new [PlaybackStateManager.SavedState].
     *
     * @param state The [PlaybackStateManager.SavedState] to persist.
     */
    suspend fun saveState(state: PlaybackStateManager.SavedState?): Boolean
}

class PersistenceRepositoryImpl
@Inject
constructor(
    private val database: PersistenceDatabase,
    private val playbackStateDao: PlaybackStateDao,
    private val queueDao: QueueDao,
    private val musicRepository: MusicRepository,
) : PersistenceRepository {

    override suspend fun readState(): PlaybackStateManager.SavedState? {
        val library = musicRepository.library?.takeIf { !it.empty() } ?: return null
        val playbackState: PlaybackState
        val heapItems: List<QueueHeapItem>
        val mappingItems: List<QueueShuffledMappingItem>
        try {
            playbackState = playbackStateDao.getState() ?: return null
            heapItems = queueDao.getHeap()
            mappingItems = queueDao.getShuffledMapping()
        } catch (e: Exception) {
            L.e("Unable read playback state")
            L.e(e.stackTraceToString())
            return null
        }

        val heap = heapItems.map { library.findSong(it.uid) }
        val shuffledMapping = mappingItems.map { it.index }
        val parent = playbackState.parentUid?.let { musicRepository.find(it) as? MusicParent }

        return PlaybackStateManager.SavedState(
            positionMs = playbackState.positionMs,
            repeatMode = playbackState.repeatMode,
            parent = parent,
            heap = heap,
            shuffledMapping = shuffledMapping,
            index = playbackState.index,
            songUid = playbackState.songUid,
        )
    }

    override suspend fun saveState(state: PlaybackStateManager.SavedState?): Boolean {
        return try {
            database.withTransaction {
                playbackStateDao.nukeState()
                queueDao.nukeHeap()
                queueDao.nukeShuffledMapping()
                if (state != null) {
                    val playbackState =
                        PlaybackState(
                            id = 0,
                            index = state.index,
                            positionMs = state.positionMs,
                            repeatMode = state.repeatMode,
                            songUid = state.songUid,
                            parentUid = state.parent?.uid,
                        )
                    val heap =
                        state.heap.mapIndexed { i, song ->
                            QueueHeapItem(i, requireNotNull(song).uid)
                        }
                    val shuffledMapping =
                        state.shuffledMapping.mapIndexed { i, index ->
                            QueueShuffledMappingItem(i, index)
                        }

                    playbackStateDao.insertState(playbackState)
                    queueDao.insertHeap(heap)
                    queueDao.insertShuffledMapping(shuffledMapping)
                }
            }
            L.d("Successfully saved state")
            true
        } catch (e: Exception) {
            L.e("Unable to save playback state")
            L.e(e.stackTraceToString())
            false
        }
    }
}
