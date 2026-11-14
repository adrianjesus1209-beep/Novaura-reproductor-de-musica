/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackStateManagerTest.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.state

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song

class PlaybackStateManagerTest {
    private fun fakeSong(id: String): Song {
        val uidValue =
            requireNotNull(Music.UID.fromString("uas${UUID.nameUUIDFromBytes(id.toByteArray())}"))
        return mockk<Song>(relaxed = true) { every { uid } returns uidValue }
    }

    @Test
    fun applySavedState_recoversOutOfBoundsIndexAndNullHeapEntries() {
        val s0 = fakeSong("s0")
        val s1 = fakeSong("s1")
        val s2 = fakeSong("s2")
        val holder = mockk<PlaybackStateHolder>(relaxed = true)
        val manager = PlaybackStateManagerImpl()
        manager.registerStateHolder(holder)

        val rawQueueSlot = slot<RawQueue>()
        every {
            holder.applySavedState(any(), capture(rawQueueSlot), any(), any(), any())
        } returns Unit

        // Index 99 is out of bounds, s1 sits after a null gap in persisted heap.
        val saved =
            PlaybackStateManager.SavedState(
                positionMs = 15_000L,
                repeatMode = RepeatMode.ALL,
                parent = null,
                heap = listOf(s0, null, s1, s2),
                shuffledMapping = listOf(3, 2, 0),
                index = 99,
                songUid = s1.uid,
            )

        manager.applySavedState(saved, destructive = true)

        verify(exactly = 1) {
            holder.applySavedState(null, any(), 15_000L, RepeatMode.ALL, StateAck.NewPlayback)
        }
        // Surviving heap has [s0, s1, s2]; shuffled indices [3, 2, 0] shift across null gap to [2,
        // 1, 0].
        assertEquals(listOf(s0, s1, s2), rawQueueSlot.captured.heap)
        assertEquals(listOf(2, 1, 0), rawQueueSlot.captured.shuffledMapping)
        assertEquals(1, rawQueueSlot.captured.heapIndex)
    }

    @Test
    fun applySavedState_resetsSafelyWhenSongMissingOrMappingCorrupt() {
        val s0 = fakeSong("s0")
        val missing = fakeSong("missing")
        val holder = mockk<PlaybackStateHolder>(relaxed = true)
        val manager = PlaybackStateManagerImpl()
        manager.registerStateHolder(holder)

        val missingSongState =
            PlaybackStateManager.SavedState(
                positionMs = 0L,
                repeatMode = RepeatMode.NONE,
                parent = null,
                heap = listOf(s0),
                shuffledMapping = emptyList(),
                index = 0,
                songUid = missing.uid,
            )

        manager.applySavedState(missingSongState, destructive = true)
        verify(exactly = 1) { holder.reset(StateAck.NewPlayback) }

        val badShuffleState =
            PlaybackStateManager.SavedState(
                positionMs = 0L,
                repeatMode = RepeatMode.NONE,
                parent = null,
                heap = listOf(s0),
                shuffledMapping = listOf(42),
                index = 0,
                songUid = s0.uid,
            )
        manager.applySavedState(badShuffleState, destructive = true)
        verify(exactly = 2) { holder.reset(StateAck.NewPlayback) }
    }

    @Test
    fun moveQueueItemAndToSavedState_reflectReorderedQueueAndState() {
        val s0 = fakeSong("s0")
        val s1 = fakeSong("s1")
        val s2 = fakeSong("s2")
        val holder = mockk<PlaybackStateHolder>(relaxed = true)
        val manager = PlaybackStateManagerImpl()

        assertNull(manager.toSavedState())
        manager.registerStateHolder(holder)

        every { holder.parent } returns mockk<MusicParent>(relaxed = true)
        every { holder.progression } returns
            Progression.from(isPlaying = false, isAdvancing = false, positionMs = 12_345L)
        every { holder.repeatMode } returns RepeatMode.TRACK
        every { holder.resolveQueue() } returns
            RawQueue(heap = listOf(s0, s1, s2), shuffledMapping = emptyList(), heapIndex = 0)

        manager.ack(holder, StateAck.NewPlayback)
        manager.ack(holder, StateAck.ProgressionChanged)
        manager.ack(holder, StateAck.RepeatModeChanged)

        manager.moveQueueItem(0, 2)
        verify(exactly = 1) { holder.move(0, 2, StateAck.Move(0, 2)) }

        every { holder.resolveQueue() } returns
            RawQueue(heap = listOf(s1, s2, s0), shuffledMapping = emptyList(), heapIndex = 2)
        manager.ack(holder, StateAck.Move(0, 2))

        val savedState = manager.toSavedState()
        assertNotNull(savedState)
        assertEquals(listOf(s1, s2, s0), savedState!!.heap)
        assertEquals(2, savedState.index)
        assertEquals(s0.uid, savedState.songUid)
        assertEquals(RepeatMode.TRACK, savedState.repeatMode)
    }
}
