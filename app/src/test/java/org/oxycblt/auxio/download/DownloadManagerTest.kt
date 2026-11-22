/*
 * Copyright (c) 2026 Auxio Project
 * DownloadManagerTest.kt is part of Auxio.
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
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DownloadManagerTest {
    private lateinit var manager: DownloadManager

    private fun song(index: Int) =
        OnlineSong(
            id = "id-$index",
            title = "Song $index",
            artist = "Artist $index",
            durationText = "3:00",
            durationSeconds = 180L,
            thumbnailUrl = null,
            webUrl = "https://www.youtube.com/watch?v=song$index",
        )

    @Before
    fun setUp() {
        manager = DownloadManager(mockk<Context>(relaxed = true))
    }

    @Test
    fun enqueue_startsQueuedThenTransitionsDownloadingPauseCancelAndClear() {
        val s1 = song(1)
        val s2 = song(2)

        manager.enqueueDownload(s1)
        manager.enqueueDownload(s2)
        // Duplicate enqueue while active/queued is ignored
        manager.enqueueDownload(s1)

        assertEquals(2, manager.downloadItems.value.size)
        assertEquals(2, manager.activeCount.value)
        assertEquals(
            DownloadStatus.QUEUED,
            manager.downloadItems.value.first { it.song.webUrl == s1.webUrl }.status,
        )

        manager.setDownloadingFromService(s1.webUrl)
        manager.updateProgress(s1.webUrl, 45)
        val updated1 = manager.downloadItems.value.first { it.song.webUrl == s1.webUrl }
        assertEquals(DownloadStatus.DOWNLOADING, updated1.status)
        assertEquals(45, updated1.progress)

        // Pause queued item s2 cleanly before it even begins downloading
        manager.pauseDownload(s2.webUrl)
        assertEquals(1, manager.activeCount.value)
        assertEquals(
            DownloadStatus.PAUSED,
            manager.downloadItems.value.first { it.song.webUrl == s2.webUrl }.status,
        )

        // Complete s1 and clear completed items keeps paused s2
        manager.onDownloadFinished(s1.webUrl, success = true, title = s1.title)
        assertEquals(
            DownloadStatus.COMPLETED,
            manager.downloadItems.value.first { it.song.webUrl == s1.webUrl }.status,
        )
        assertEquals(0, manager.activeCount.value)

        manager.clearCompleted()
        assertNull(manager.downloadItems.value.find { it.song.webUrl == s1.webUrl })
        assertEquals(1, manager.downloadItems.value.size)
        assertEquals(DownloadStatus.PAUSED, manager.downloadItems.value.first().status)
    }
}
