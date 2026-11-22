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

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OnlinePlaylistManagerTest {
    private lateinit var tempDir: File
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
        tempDir = Files.createTempDirectory("online_playlist_test").toFile()
        manager = OnlinePlaylistManager(filesDir = tempDir, ioDispatcher = Dispatchers.IO)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun createPlaylistAndAddSongsToPlaylist_deduplicatesAndPersists() = runBlocking {
        manager.awaitIdle()
        manager.createPlaylist("Mi Playlist Online")
        val playlistId = manager.playlists.value.first { it.name == "Mi Playlist Online" }.id

        val s1 = song(1)
        val s2 = song(2)
        assertEquals(2, manager.addSongsToPlaylist(playlistId, listOf(s1, s1, s2)))
        assertEquals(0, manager.addSongsToPlaylist(playlistId, listOf(s1)))

        val playlist = manager.playlists.value.first { it.id == playlistId }
        assertEquals(listOf(s1, s2), playlist.songs)

        manager.awaitIdle()
        val jsonFile = File(tempDir, "online_playlists.json")
        assertTrue(jsonFile.exists())

        val reloaded = OnlinePlaylistManager(filesDir = tempDir, ioDispatcher = Dispatchers.IO)
        reloaded.awaitIdle()
        assertTrue(
            reloaded.playlists.value.any { it.name == "Mi Playlist Online" && it.songs.size == 2 }
        )
    }

    @Test
    fun toggleFavorites_usesCanonicalIdAndMigratesLocalizedName() = runBlocking {
        manager.awaitIdle()
        val localized =
            OnlinePlaylistManager(
                filesDir = tempDir,
                ioDispatcher = Dispatchers.IO,
                favoritesLabel = "Favorites",
            )
        localized.awaitIdle()
        val s1 = song(1)
        val s2 = song(2)

        assertTrue(localized.toggleFavorites(listOf(s1)))
        assertEquals(OnlinePlaylistManager.ID_FAVORITES, localized.getFavoritesPlaylist()?.id)
        assertTrue(localized.isFavorite(s1))
        assertFalse(localized.isFavorite(s2))

        // Partial batch adds missing s2 and returns true
        assertTrue(localized.toggleFavorites(listOf(s1, s2)))
        assertEquals(2, localized.getFavoritesPlaylist()?.songs?.size)

        // Full batch removes both and returns false
        assertFalse(localized.toggleFavorites(listOf(s1, s2)))
        assertFalse(localized.isFavorite(s1))
        assertFalse(localized.isFavorite(s2))

        // Legacy favorites JSON with random ID gets migrated to ID_FAVORITES on load
        val legacyDir = Files.createTempDirectory("online_playlist_legacy").toFile()
        try {
            File(legacyDir, "online_playlists.json")
                .writeText(
                    """[{"id":"random-uuid","name":"Favoritos","createdAt":1000,"songs":[]}]"""
                )
            val migrated =
                OnlinePlaylistManager(
                    filesDir = legacyDir,
                    ioDispatcher = Dispatchers.IO,
                    favoritesLabel = "Favorites",
                )
            migrated.awaitIdle()
            val fav = migrated.getFavoritesPlaylist()
            assertNotNull(fav)
            assertEquals(OnlinePlaylistManager.ID_FAVORITES, fav?.id)
        } finally {
            legacyDir.deleteRecursively()
        }
    }

    @Test
    fun recordPlay_prependsNewestFirstAndRetainsMostPlayedBeyondFiftyRecents() = runBlocking {
        manager.awaitIdle()
        val topHit = song(1)
        repeat(5) { manager.recordPlay(topHit) }

        // Push 55 distinct songs after topHit so topHit is evicted from the 50-item recent window
        for (i in 2..56) {
            manager.recordPlay(song(i))
        }

        val recents = manager.recentSongs.value
        assertEquals(50, recents.size)
        assertEquals(song(56).webUrl, recents.first().webUrl)
        assertFalse(recents.any { it.webUrl == topHit.webUrl })

        val mostPlayed = manager.getMostPlayedSongs(limit = 50)
        assertEquals(topHit.webUrl, mostPlayed.first().webUrl)
        assertEquals(5, manager.getPlayCount(topHit))

        manager.awaitIdle()
        val reloaded = OnlinePlaylistManager(filesDir = tempDir, ioDispatcher = Dispatchers.IO)
        reloaded.awaitIdle()
        assertEquals(song(56).webUrl, reloaded.recentSongs.value.first().webUrl)
        assertEquals(topHit.webUrl, reloaded.getMostPlayedSongs(limit = 50).first().webUrl)
    }

    @Test
    fun corruptPlaylistFile_createsBackupWithoutCrashing() = runBlocking {
        val corruptDir = Files.createTempDirectory("online_playlist_corrupt").toFile()
        try {
            File(corruptDir, "online_playlists.json").writeText("{not valid json")
            val m = OnlinePlaylistManager(filesDir = corruptDir, ioDispatcher = Dispatchers.IO)
            m.awaitIdle()

            assertTrue(m.playlists.value.isEmpty())
            val backups = corruptDir.listFiles { _, name ->
                name.startsWith("online_playlists.corrupt.") && name.endsWith(".json")
            }
            assertTrue((backups?.size ?: 0) == 1)
        } finally {
            corruptDir.deleteRecursively()
        }
    }
}
