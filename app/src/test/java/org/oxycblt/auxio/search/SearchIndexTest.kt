/*
 * Copyright (c) 2026 Auxio Project
 * SearchIndexTest.kt is part of Auxio.
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
 
package org.oxycblt.auxio.search

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.Library
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.tag.Name

class SearchIndexTest {
    private val context = mockk<Context>(relaxed = true)

    private fun knownName(rawValue: String, sortValue: String? = null) =
        mockk<Name.Known>(relaxed = true) {
            every { raw } returns rawValue
            every { sort } returns sortValue
        }

    private fun fakeSong(
        title: String,
        artistName: String,
        albumName: String,
        fileName: String,
    ): Song {
        val songArtist =
            mockk<Artist>(relaxed = true) { every { name } returns knownName(artistName) }
        val songAlbum =
            mockk<Album>(relaxed = true) {
                every { name } returns knownName(albumName)
                every { artists } returns listOf(songArtist)
            }
        val songPath = mockk<Path>(relaxed = true) { every { name } returns fileName }
        return mockk<Song>(relaxed = true) {
            every { name } returns knownName(title)
            every { artists } returns listOf(songArtist)
            every { album } returns songAlbum
            every { path } returns songPath
        }
    }

    @Test
    fun normalizeString_foldsDiacriticsAndNonAsciiDelimiters() {
        assertEquals("cancion aguila", SearchIndex.normalizeString("Canción Águila"))
        assertEquals("cafe deja vu", SearchIndex.normalizeString("Café Déjà Vu!"))
        assertEquals("nino", SearchIndex.normalizeString("Niño"))
    }

    @Test
    fun buildAndSearch_matchesAccentsCjkAndSymbolRawFallback(): Unit = runBlocking {
        val latinSong = fakeSong("Canción Nocturna", "Óscar", "Álbum Único", "nocturna.mp3")
        val cjkSong = fakeSong("東京夜景", "宇多田", "First Love", "tokyo.flac")
        val symbolSong = fakeSong("!!! ???", "Band+", "EP+", "track01.ogg")

        val album =
            mockk<Album>(relaxed = true) {
                every { name } returns knownName("Álbum Único")
                every { artists } returns listOf(latinSong.artists.first())
            }
        val artist = latinSong.artists.first()
        val genre = mockk<Genre>(relaxed = true) { every { name } returns knownName("Electrónica") }
        val playlist =
            mockk<Playlist>(relaxed = true) { every { name } returns knownName("Favoritas Ñ") }

        val songs = setOf(latinSong, cjkSong, symbolSong)
        val library =
            mockk<Library>(relaxed = true) {
                every { this@mockk.songs } returns songs
                every { this@mockk.albums } returns setOf(album)
                every { this@mockk.artists } returns setOf(artist)
                every { this@mockk.genres } returns setOf(genre)
                every { this@mockk.playlists } returns setOf(playlist)
            }

        val index = SearchIndex(context)
        index.build(library)

        assertTrue(index.isReady)
        assertEquals(listOf(latinSong), index.searchSongs("cancion", null))
        assertEquals(listOf(latinSong), index.searchSongs("oscar", songs))
        assertEquals(listOf(cjkSong), index.searchSongs("東京", null))
        assertEquals(listOf(symbolSong), index.searchSongs("!!!", null))
        assertEquals(listOf(album), index.searchAlbums("unico", null))
        assertEquals(listOf(artist), index.searchArtists("oscar", null))
        assertEquals(listOf(genre), index.searchGenres("electronica", null))
        assertEquals(listOf(playlist), index.searchPlaylists("favoritas n", null))
    }

    @Test
    fun buildAndSearch_concurrentCallsRemainConsistent(): Unit = runBlocking {
        val songA = fakeSong("Alpha", "One", "A", "a.mp3")
        val songB = fakeSong("Beta", "Two", "B", "b.mp3")
        val libA =
            mockk<Library>(relaxed = true) {
                every { songs } returns setOf(songA)
                every { albums } returns emptySet()
                every { artists } returns emptySet()
                every { genres } returns emptySet()
                every { playlists } returns emptySet()
            }
        val libB =
            mockk<Library>(relaxed = true) {
                every { songs } returns setOf(songA, songB)
                every { albums } returns emptySet()
                every { artists } returns emptySet()
                every { genres } returns emptySet()
                every { playlists } returns emptySet()
            }

        val index = SearchIndex(context)
        index.build(libA)

        val jobs =
            (0 until 12).map { i ->
                async(Dispatchers.Default) {
                    if (i % 3 == 0) {
                        index.build(if (i % 2 == 0) libA else libB)
                    } else {
                        val hits = index.searchSongs("alpha", null)
                        assertEquals(listOf(songA), hits)
                    }
                }
            }
        jobs.awaitAll()
    }
}
