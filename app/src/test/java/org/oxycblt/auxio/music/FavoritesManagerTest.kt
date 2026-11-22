/*
 * Copyright (c) 2026 Auxio Project
 * FavoritesManagerTest.kt is part of Auxio.
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

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.musikr.Library
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song

class FavoritesManagerTest {
    private fun fakeSong(seed: String): Song {
        val uidValue =
            requireNotNull(Music.UID.fromString("uas${UUID.nameUUIDFromBytes(seed.toByteArray())}"))
        return mockk<Song>(relaxed = true) { every { uid } returns uidValue }
    }

    @Test
    fun toggleAndLibraryChanges_preserveWhenNullAndPruneOrphansWhenLoaded() {
        val songA = fakeSong("favA")
        val songB = fakeSong("favB")
        val uidA = songA.uid
        val uidB = songB.uid
        val prefsMap = mutableMapOf<String, String>()
        val editor =
            mockk<SharedPreferences.Editor>(relaxed = true) {
                every { putString(any(), any()) } answers
                    {
                        val k = firstArg<String>()
                        val v = secondArg<String?>()
                        if (v == null) prefsMap.remove(k) else prefsMap[k] = v
                        this@mockk
                    }
            }
        val prefs =
            mockk<SharedPreferences>(relaxed = true) {
                every { edit() } returns editor
                every { getString(any(), any()) } answers { prefsMap[firstArg()] ?: secondArg() }
            }
        val context =
            mockk<Context>(relaxed = true) {
                every { getSharedPreferences(any(), any()) } returns prefs
            }

        var currentLibrary: Library? =
            mockk<Library>(relaxed = true) {
                every { songs } returns setOf(songA, songB)
                every { findSong(uidA) } returns songA
                every { findSong(uidB) } returns songB
            }
        val repo =
            mockk<MusicRepository>(relaxed = true) {
                every { library } answers { currentLibrary }
            }

        val manager = FavoritesManager(context, repo)
        assertTrue(manager.toggleFavorite(songA))
        assertTrue(manager.toggleFavorite(songB))
        assertEquals(listOf(songB, songA), manager.favoriteSongs.value)

        // Null library during temporary rescan does not wipe saved favorite UIDs
        currentLibrary = null
        manager.onMusicChanges(MusicRepository.Changes(deviceLibrary = true, userLibrary = false))
        assertTrue(manager.isFavorite(songA))
        assertTrue(manager.isFavorite(songB))

        // Once non-empty library loads without songA, orphan songA is pruned from prefs
        currentLibrary =
            mockk<Library>(relaxed = true) {
                every { songs } returns setOf(songB)
                every { findSong(uidA) } returns null
                every { findSong(uidB) } returns songB
            }
        manager.onMusicChanges(MusicRepository.Changes(deviceLibrary = true, userLibrary = false))
        assertFalse(manager.isFavorite(songA))
        assertTrue(manager.isFavorite(songB))
        assertEquals(listOf(songB), manager.favoriteSongs.value)
    }
}
