/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackStatsManager.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.stats

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Song

/**
 * Manages listening history and playback statistics (e.g. tracks played, time listened, most played
 * tracks, top artists).
 */
@Singleton
class PlaybackStatsManager @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class PlaybackStats(val tracksPlayed: Int, val listenedDurationMs: Long)

    fun recordSongPlayed(song: Song, durationMs: Long) {
        val now = System.currentTimeMillis()
        val songKey = KEY_SONG_COUNT_PREFIX + song.uid

        val songCount = prefs.getInt(songKey, 0) + 1
        val lifetimeTracks = prefs.getInt(KEY_LIFETIME_TRACKS, 0) + 1
        val lifetimeDuration = prefs.getLong(KEY_LIFETIME_DURATION, 0L) + durationMs

        val editor =
            prefs
                .edit()
                .putInt(songKey, songCount)
                .putInt(KEY_LIFETIME_TRACKS, lifetimeTracks)
                .putLong(KEY_LIFETIME_DURATION, lifetimeDuration)

        for (artist in song.artists) {
            val artistKey = KEY_ARTIST_COUNT_PREFIX + artist.uid
            val artistCount = prefs.getInt(artistKey, 0) + 1
            editor.putInt(artistKey, artistCount)
        }

        // Append 7-day log entry: timestamp:duration:songUid
        val existingLog = prefs.getString(KEY_7D_LOG, "") ?: ""
        val cutoff = now - TimeUnit.DAYS.toMillis(7)

        val updatedEntries =
            existingLog
                .split(";")
                .filter { entry ->
                    val parts = entry.split(":")
                    if (parts.size >= 2) {
                        val time = parts[0].toLongOrNull() ?: 0L
                        time >= cutoff
                    } else false
                }
                .toMutableList()

        updatedEntries.add("$now:$durationMs:${song.uid}")

        editor.putString(KEY_7D_LOG, updatedEntries.joinToString(";")).apply()
    }

    fun getMostPlayedSongs(allSongs: List<Song>, limit: Int = 10): List<Song> {
        if (allSongs.isEmpty()) return emptyList()

        val sorted = allSongs.sortedByDescending { song ->
            prefs.getInt(KEY_SONG_COUNT_PREFIX + song.uid, 0)
        }

        // Return up to limit tracks
        return sorted.take(limit)
    }

    fun getTopArtists(allArtists: List<Artist>, limit: Int = 10): List<Artist> {
        if (allArtists.isEmpty()) return emptyList()

        val sorted = allArtists.sortedByDescending { artist ->
            prefs.getInt(KEY_ARTIST_COUNT_PREFIX + artist.uid, 0)
        }

        return sorted.take(limit)
    }

    fun getStats7Days(): PlaybackStats {
        val now = System.currentTimeMillis()
        val cutoff = now - TimeUnit.DAYS.toMillis(7)
        val log = prefs.getString(KEY_7D_LOG, "") ?: ""

        var count = 0
        var totalMs = 0L

        if (log.isNotEmpty()) {
            for (entry in log.split(";")) {
                val parts = entry.split(":")
                if (parts.size >= 2) {
                    val time = parts[0].toLongOrNull() ?: 0L
                    if (time >= cutoff) {
                        count++
                        totalMs += parts[1].toLongOrNull() ?: 0L
                    }
                }
            }
        }

        return PlaybackStats(tracksPlayed = count, listenedDurationMs = totalMs)
    }

    fun getStatsLifetime(): PlaybackStats {
        val count = prefs.getInt(KEY_LIFETIME_TRACKS, 0)
        val duration = prefs.getLong(KEY_LIFETIME_DURATION, 0L)
        return PlaybackStats(tracksPlayed = count, listenedDurationMs = duration)
    }

    fun formatDuration(ms: Long): String {
        val totalSecs = ms / 1000
        val hours = totalSecs / 3600
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60

        return when {
            hours > 0 -> "${hours}h ${mins}m"
            mins > 0 -> "${mins}m ${secs}s"
            else -> "${secs}s"
        }
    }

    companion object {
        private const val PREFS_NAME = "auxio_playback_stats"
        private const val KEY_SONG_COUNT_PREFIX = "song_plays_"
        private const val KEY_ARTIST_COUNT_PREFIX = "artist_plays_"
        private const val KEY_LIFETIME_TRACKS = "lifetime_tracks"
        private const val KEY_LIFETIME_DURATION = "lifetime_duration"
        private const val KEY_7D_LOG = "log_7d"
    }
}
