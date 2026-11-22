/*
 * Copyright (c) 2026 Auxio Project
 * BetterShuffleOrderTest.kt is part of Auxio.
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

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BetterShuffleOrderTest {
    private fun collectForward(order: BetterShuffleOrder): List<Int> {
        val result = mutableListOf<Int>()
        var current = order.firstIndex
        while (current != C.INDEX_UNSET) {
            result.add(current)
            current = order.getNextIndex(current)
        }
        return result
    }

    private fun collectBackward(order: BetterShuffleOrder): List<Int> {
        val result = mutableListOf<Int>()
        var current = order.lastIndex
        while (current != C.INDEX_UNSET) {
            result.add(current)
            current = order.getPreviousIndex(current)
        }
        return result
    }

    @Test
    fun constructor_placesStartIndexFirstAndCreatesValidPermutation() {
        val order = BetterShuffleOrder(length = 6, startIndex = 3)
        val forward = collectForward(order)
        val backward = collectBackward(order)

        assertEquals(6, order.length)
        assertEquals(3, order.firstIndex)
        assertEquals(6, forward.size)
        assertEquals((0 until 6).toSet(), forward.toSet())
        assertEquals(forward.reversed(), backward)
    }

    @Test
    fun cloneAndInsert_placesInsertedIndicesSequentiallyWithoutCollisions() {
        val initial = BetterShuffleOrder(intArrayOf(2, 0, 4, 1, 3))
        val updated =
            initial.cloneAndInsert(insertionIndex = 2, insertionCount = 3) as BetterShuffleOrder
        val forward = collectForward(updated)

        assertEquals(8, updated.length)
        assertEquals((0 until 8).toSet(), forward.toSet())
        val pos2 = forward.indexOf(2)
        assertTrue(pos2 >= 0)
        assertEquals(3, forward[pos2 + 1])
        assertEquals(4, forward[pos2 + 2])
    }

    @Test
    fun cloneAndRemove_decrementsHigherIndicesAndPreservesRelativeOrder() {
        val initial = BetterShuffleOrder(intArrayOf(2, 0, 4, 1, 3))
        val updated =
            initial.cloneAndRemove(indexFrom = 1, indexToExclusive = 3) as BetterShuffleOrder
        val forward = collectForward(updated)

        assertEquals(3, updated.length)
        assertEquals((0 until 3).toSet(), forward.toSet())
        assertEquals(listOf(0, 2, 1), forward)
        assertEquals(forward.reversed(), collectBackward(updated))
    }

    @Test
    fun cloneAndClear_returnsEmptyOrder() {
        val cleared = BetterShuffleOrder(intArrayOf(1, 0, 2)).cloneAndClear()
        assertEquals(0, cleared.length)
        assertEquals(C.INDEX_UNSET, cleared.firstIndex)
        assertEquals(C.INDEX_UNSET, cleared.lastIndex)
    }

    @Test
    fun cloneAndInsert_playNextPlacesNewTrackImmediatelyAfterCurrentShuffledTrack() {
        // Shuffled order: 2 -> 0 -> 4 -> 1 -> 3
        // Suppose track 4 (shuffled pos 2) is currently playing; playNext inserts at heap index
        // 5? No: suppose track 0 (shuffled pos 1) is playing -> playNext inserts at heap index 1.
        val order = BetterShuffleOrder(intArrayOf(2, 0, 4, 1, 3))
        val afterPlaying0 =
            order.cloneAndInsert(insertionIndex = 1, insertionCount = 2) as BetterShuffleOrder
        val forward = collectForward(afterPlaying0)

        assertEquals((0 until 7).toSet(), forward.toSet())
        val pos0 = forward.indexOf(0)
        // New items (1, 2) must play immediately after 0, before old next item (adjusted 4->6).
        assertEquals(1, forward[pos0 + 1])
        assertEquals(2, forward[pos0 + 2])
    }

    @Test
    fun cloneAndInsert_appendAtEndLeavesExistingOrderIntactAndAppendsAtTail() {
        val order = BetterShuffleOrder(intArrayOf(2, 0, 4, 1, 3))
        val appended =
            order.cloneAndInsert(insertionIndex = 5, insertionCount = 2) as BetterShuffleOrder
        assertEquals(listOf(2, 0, 4, 1, 3, 5, 6), collectForward(appended))
    }

    @Test
    fun cloneAndInsertAfter_handlesPlayNextFromLastHeapElement() {
        // Last heap element (4) is in the middle of the shuffled order.
        val order = BetterShuffleOrder(intArrayOf(2, 0, 4, 1, 3))
        val anchored =
            order.cloneAndInsertAfter(anchorHeapIndex = 4, insertionIndex = 5, insertionCount = 1)
        val forward = collectForward(anchored)
        assertEquals(listOf(2, 0, 4, 5, 1, 3), forward)
    }
}
