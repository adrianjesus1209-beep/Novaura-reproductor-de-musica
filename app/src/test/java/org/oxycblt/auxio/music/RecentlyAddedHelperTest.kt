/*
 * Copyright (c) 2026 Auxio Project
 * RecentlyAddedHelperTest.kt is part of Auxio.
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
 
package org.oxycblt.auxio.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentlyAddedHelperTest {

    @Test
    fun isDownloadedPath_returnsTrue_forDownloadAndMusicDirectories() {
        assertTrue(RecentlyAddedHelper.isDownloadedPath("Music/Novaura/Artist/song.mp3"))
        assertTrue(RecentlyAddedHelper.isDownloadedPath("Download/song.mp3"))
        assertTrue(RecentlyAddedHelper.isDownloadedPath("Descargas/song.mp3"))
        assertTrue(RecentlyAddedHelper.isDownloadedPath("Telegram/Telegram Audio/song.mp3"))
        assertTrue(RecentlyAddedHelper.isDownloadedPath("snaptube/download/song.mp3"))
        assertTrue(RecentlyAddedHelper.isDownloadedPath("Music/song.mp3"))
        assertTrue(
            RecentlyAddedHelper.isDownloadedPath("/storage/emulated/0/Download/new_track.mp3")
        )
    }

    @Test
    fun isDownloadedPath_returnsFalse_forSystemAndOtherDirectories() {
        assertFalse(RecentlyAddedHelper.isDownloadedPath("System/media/audio/ringtones/ring.ogg"))
        assertFalse(RecentlyAddedHelper.isDownloadedPath("Podcasts/episode.mp3"))
        assertFalse(RecentlyAddedHelper.isDownloadedPath("Alarms/alarm.ogg"))
    }

    @Test
    fun isWithin48Hours_returnsTrue_forSongsWithin48Hours() {
        val now = 1700000000000L
        val tenHoursAgo = now - (10L * 60L * 60L * 1000L)
        val fortySevenHoursAgo = now - (47L * 60L * 60L * 1000L)

        assertTrue(RecentlyAddedHelper.isWithin48Hours(tenHoursAgo, tenHoursAgo, now))
        assertTrue(RecentlyAddedHelper.isWithin48Hours(fortySevenHoursAgo, fortySevenHoursAgo, now))
        // Even if modifiedMs is old, if addedMs is within 48 hours:
        val oldModified = now - (300L * 24L * 60L * 60L * 1000L)
        assertTrue(RecentlyAddedHelper.isWithin48Hours(tenHoursAgo, oldModified, now))
    }

    @Test
    fun isWithin48Hours_returnsFalse_forSongsOlderThan48Hours() {
        val now = 1700000000000L
        val fortyNineHoursAgo = now - (49L * 60L * 60L * 1000L)
        val sevenDaysAgo = now - (7L * 24L * 60L * 60L * 1000L)

        assertFalse(RecentlyAddedHelper.isWithin48Hours(fortyNineHoursAgo, fortyNineHoursAgo, now))
        assertFalse(RecentlyAddedHelper.isWithin48Hours(sevenDaysAgo, sevenDaysAgo, now))
    }

    @Test
    fun getEffectiveTimestampMs_handlesSecondsAndMilliseconds() {
        val seconds = 1700000000L
        val millis = 1700000000000L

        assertEquals(millis, RecentlyAddedHelper.getEffectiveTimestampMs(seconds))
        assertEquals(millis, RecentlyAddedHelper.getEffectiveTimestampMs(millis))
        assertEquals(0L, RecentlyAddedHelper.getEffectiveTimestampMs(0L))
    }

    @Test
    fun getIncorporationTimeMs_returnsMaxTimestamp() {
        val t1 = 1700000000000L
        val t2 = 1700000500000L

        assertEquals(t2, RecentlyAddedHelper.getIncorporationTimeMs(t1, t2))
        assertEquals(t2, RecentlyAddedHelper.getIncorporationTimeMs(t2, t1))
    }
}
