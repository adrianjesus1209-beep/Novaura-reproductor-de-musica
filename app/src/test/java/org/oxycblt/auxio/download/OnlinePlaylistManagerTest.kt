/*
 * Copyright (c) 2026 Auxio Project
 * OnlinePlaylistManagerTest.kt is part of Auxio.
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
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class OnlinePlaylistManagerTest {
    private lateinit var context: Context
    private lateinit var manager: OnlinePlaylistManager

    private fun song(index: Int) =
        OnlineSong(
            id = "id-$index",
            title = "Song $index",
            artist = "Artist $index",
            durationText = "3:00",
            durationSeconds = 180L,
            thumbnailUrl = "https://example.com/$index.jpg",
            webUrl = "https://youtube.com/watch?v=test$index",
            viewCount = index * 100L,
        )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "online_playlists.json").delete()
        File(context.filesDir, "online_history.json").delete()
        File(context.filesDir, "online_play_counts.json").delete()
        manager = OnlinePlaylistManager(context)
    }

    @Test
    fun createPlaylistAndAddSongsToPlaylist_deduplicatesAndPersists() = runBlocking {
        manager.createPlaylist("Mi Playlist Online")
        val playlistId = manager.playlists.value.first { it.name == "Mi Playlist Online" }.id

        val s1 = song(1)
        val s2 = song(2)
        val insertedFirst = manager.addSongsToPlaylist(playlistId, listOf(s1, s1, s2))
        assertEquals(2, insertedFirst)

        val insertedDuplicate = manager.addSongsToPlaylist(playlistId, listOf(s1))
        assertEquals(0, insertedDuplicate)

        val playlist = manager.playlists.value.first { it.id == playlistId }
        assertEquals(listOf(s1, s2), playlist.songs)

        delay(150)
        val jsonFile = File(context.filesDir, "online_playlists.json")
        assertTrue(jsonFile.exists())
        val reloaded = OnlinePlaylistManager(context)
        delay(150)
        assertTrue(
            reloaded.playlists.value.any { it.name == "Mi Playlist Online" && it.songs.size == 2 }
        )
    }

    @Test
    fun toggleFavorites_handlesSingleAndBatchTransitions() {
        val s1 = song(1)
        val s2 = song(2)

        assertTrue(manager.toggleFavorites(listOf(s1)))
        assertTrue(manager.isFavorite(s1))
        assertFalse(manager.isFavorite(s2))

        // Partial favorite batch adds only missing song s2 -> true
        assertTrue(manager.toggleFavorites(listOf(s1, s2)))
        assertTrue(manager.isFavorite(s1))
        assertTrue(manager.isFavorite(s2))
        assertEquals(2, manager.getFavoritesPlaylist()?.songs?.size)

        // All already favorites -> removes batch -> false
        assertFalse(manager.toggleFavorites(listOf(s1, s2)))
        assertFalse(manager.isFavorite(s1))
        assertFalse(manager.isFavorite(s2))
    }
}
