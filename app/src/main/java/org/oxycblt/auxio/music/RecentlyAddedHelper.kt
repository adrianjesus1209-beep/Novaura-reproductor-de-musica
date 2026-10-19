/*
 * Copyright (c) 2026 Auxio Project
 * RecentlyAddedHelper.kt is part of Auxio.
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

import org.oxycblt.musikr.Song

/**
 * Helper object providing filtering logic for recently downloaded and added songs.
 *
 * Rules:
 * 1. Only downloaded songs (internal Novaura downloads or external downloads like browsers,
 *    Telegram, WhatsApp, music download apps, or standard music directories) are eligible.
 * 2. Songs automatically expire and leave "Recently Added" after 48 hours have elapsed since their
 *    incorporation on the device.
 */
object RecentlyAddedHelper {
    /** 48 hours in milliseconds */
    const val EXPIRATION_WINDOW_MS = 48L * 60L * 60L * 1000L

    /**
     * Checks whether a file path string is located in a downloaded directory (internal or
     * external).
     */
    fun isDownloadedPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains("novaura") ||
            lower.contains("download") ||
            lower.contains("descarga") ||
            lower.contains("telegram") ||
            lower.contains("whatsapp") ||
            lower.contains("snaptube") ||
            lower.contains("ymusic") ||
            lower.contains("newpipe") ||
            lower.contains("seal") ||
            lower.contains("ytdlnis") ||
            lower.contains("spowlo") ||
            lower.contains("fildo") ||
            lower.contains("music") ||
            lower.contains("música")
    }

    /** Checks whether a song is located in a downloaded directory (internal or external). */
    fun isDownloadedSong(song: Song): Boolean = isDownloadedPath(song.path.components.toString())

    /** Normalizes timestamps to milliseconds if they were stored in seconds. */
    fun getEffectiveTimestampMs(timestamp: Long): Long {
        if (timestamp <= 0L) return 0L
        return if (timestamp < 100_000_000_000L) timestamp * 1000L else timestamp
    }

    /** Returns the incorporation timestamp of the song on the device in milliseconds. */
    fun getIncorporationTimeMs(addedMs: Long, modifiedMs: Long): Long {
        val a = getEffectiveTimestampMs(addedMs)
        val m = getEffectiveTimestampMs(modifiedMs)
        return maxOf(a, m)
    }

    fun getIncorporationTimeMs(song: Song): Long =
        getIncorporationTimeMs(song.addedMs, song.modifiedMs)

    /** Checks whether the added or modified timestamp falls within the 48-hour window. */
    fun isWithin48Hours(
        addedMs: Long,
        modifiedMs: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val a = getEffectiveTimestampMs(addedMs)
        val m = getEffectiveTimestampMs(modifiedMs)
        val candidateTimes = listOf(a, m).filter { it > 0L }
        if (candidateTimes.isEmpty()) {
            return false
        }
        return candidateTimes.any { time ->
            val age = now - time
            age in -60_000L..EXPIRATION_WINDOW_MS
        }
    }

    /**
     * Checks if a song was downloaded (internally or externally) and incorporated within the last
     * 48 hours.
     */
    fun isRecentlyAdded(song: Song, now: Long = System.currentTimeMillis()): Boolean {
        if (!isDownloadedSong(song)) {
            return false
        }
        return isWithin48Hours(song.addedMs, song.modifiedMs, now)
    }

    /**
     * Filters and sorts the list of songs to return only those downloaded and added within 48
     * hours, ordered from most recent to oldest.
     */
    fun filterRecentlyAdded(
        songs: Collection<Song>,
        now: Long = System.currentTimeMillis(),
    ): List<Song> {
        return songs
            .filter { isRecentlyAdded(it, now) }
            .sortedByDescending { getIncorporationTimeMs(it) }
    }
}
