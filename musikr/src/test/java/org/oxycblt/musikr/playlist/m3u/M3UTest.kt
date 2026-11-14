/*
 * Copyright (c) 2026 Auxio Project
 * M3UTest.kt is part of Auxio.
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
 
package org.oxycblt.musikr.playlist.m3u

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.fs.Components
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.fs.Volume
import org.oxycblt.musikr.fs.path.VolumeManager
import org.oxycblt.musikr.playlist.ExportConfig
import org.oxycblt.musikr.tag.Name

class M3UTest {
    private val internalVolume =
        mockk<Volume.Internal>(relaxed = true) {
            every { components } returns Components.parseUnix("/storage/emulated/0")
        }

    private val externalVolume =
        mockk<Volume.External>(relaxed = true) {
            every { components } returns Components.parseUnix("/storage/1234-5678")
            every { id } returns "1234-5678"
        }

    private val volumeManager =
        mockk<VolumeManager> {
            every { getInternalVolume() } returns internalVolume
            every { getVolumes() } returns listOf(internalVolume, externalVolume)
        }

    private val m3u = M3U.from(volumeManager)

    @Test
    fun read_stripsUtf8BomFromHeaderAndPlaylistName() {
        val content = "\uFEFF#EXTM3U\n#PLAYLIST:Mix Bombeado\nSub/song1.mp3\n"
        val workingDir = Path(internalVolume, Components.parseUnix("Music/Lists"))

        val imported =
            m3u.read(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)), workingDir)

        assertNotNull(imported)
        assertEquals("Mix Bombeado", imported!!.name)
        assertEquals(1, imported.paths.size)
        assertTrue(
            Path(internalVolume, Components.parseUnix("Music/Lists/Sub/song1.mp3")) in
                imported.paths[0]
        )

        val bomOnPlaylistLine = "\uFEFF#PLAYLIST:Primera Linea\ntrack.flac\n"
        val imported2 =
            m3u.read(
                ByteArrayInputStream(bomOnPlaylistLine.toByteArray(Charsets.UTF_8)),
                workingDir,
            )
        assertEquals("Primera Linea", imported2!!.name)
    }

    @Test
    fun read_resolvesUnixWindowsAndRelativePathsAcrossVolumes() {
        val m3uText =
            """
            #EXTM3U
            #PLAYLIST:Mixed Paths
            ../Album/rel.mp3
            /storage/emulated/0/Music/abs_unix.flac
            C:\Music\win_abs.ogg
            .\win_rel.m4a
            /storage/1234-5678/Audio/sd.mp3
            """
                .trimIndent()
        val workingDir = Path(internalVolume, Components.parseUnix("Music/Playlists"))

        val imported =
            m3u.read(ByteArrayInputStream(m3uText.toByteArray(Charsets.UTF_8)), workingDir)

        assertNotNull(imported)
        assertEquals("Mixed Paths", imported!!.name)
        assertEquals(5, imported.paths.size)

        assertTrue(
            Path(internalVolume, Components.parseUnix("Music/Album/rel.mp3")) in imported.paths[0]
        )
        assertTrue(
            Path(internalVolume, Components.parseUnix("Music/abs_unix.flac")) in imported.paths[1]
        )
        assertTrue(
            Path(internalVolume, Components.parseUnix("Music/win_abs.ogg")) in imported.paths[2]
        )
        assertTrue(
            Path(internalVolume, Components.parseUnix("Music/Playlists/win_rel.m4a")) in
                imported.paths[3]
        )
        assertTrue(Path(externalVolume, Components.parseUnix("Audio/sd.mp3")) in imported.paths[4])
    }

    @Test
    fun read_thirdPartyVolumeProducesVolumeFallbacksAndEmptyFileReturnsNull() {
        val thirdPartyDir = Path(Volume.ThirdParty(mockk<Uri>(relaxed = true)), Components.root())
        val content = "#EXTM3U\nRock/uno.mp3\n"
        val imported =
            m3u.read(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)), thirdPartyDir)

        assertNotNull(imported)
        assertTrue(
            Path(internalVolume, Components.parseUnix("Rock/uno.mp3")) in imported!!.paths[0]
        )
        assertTrue(Path(externalVolume, Components.parseUnix("Rock/uno.mp3")) in imported.paths[0])

        val empty =
            m3u.read(
                ByteArrayInputStream("#EXTM3U\n#EXTINF:10,None\n".toByteArray()),
                thirdPartyDir,
            )
        assertNull(empty)
    }

    @Test
    fun write_formatsRelativeAndAbsoluteUnixOrWindowsPaths() {
        val songName = mockk<Name.Known>(relaxed = true) { every { raw } returns "Track One" }
        val albumName = mockk<Name.Known>(relaxed = true) { every { raw } returns "Album One" }
        val artistName = mockk<Name.Known>(relaxed = true) { every { raw } returns "Artist One" }
        val genreName = mockk<Name.Known>(relaxed = true) { every { raw } returns "Rock" }
        val playlistName = mockk<Name.Known>(relaxed = true) { every { raw } returns "Mi Lista" }

        val song =
            mockk<Song>(relaxed = true) {
                every { name } returns songName
                every { durationMs } returns 125_000L
                every { album } returns
                    mockk<Album>(relaxed = true) { every { name } returns albumName }
                every { artists } returns
                    listOf(mockk<Artist>(relaxed = true) { every { name } returns artistName })
                every { genres } returns
                    listOf(mockk<Genre>(relaxed = true) { every { name } returns genreName })
                every { path } returns
                    Path(internalVolume, Components.parseUnix("Music/Rock/Track One.mp3"))
            }
        val crossVolSong =
            mockk<Song>(relaxed = true) {
                every { name } returns songName
                every { durationMs } returns 60_000L
                every { album } returns
                    mockk<Album>(relaxed = true) { every { name } returns albumName }
                every { artists } returns emptyList()
                every { genres } returns emptyList()
                every { path } returns Path(externalVolume, Components.parseUnix("Ext/Two.flac"))
            }
        val playlist =
            mockk<Playlist>(relaxed = true) {
                every { name } returns playlistName
                every { songs } returns listOf(song, crossVolSong)
            }
        val workingDir = Path(internalVolume, Components.parseUnix("Music/Playlists"))

        val unixRelOut = ByteArrayOutputStream()
        m3u.write(
            playlist,
            unixRelOut,
            workingDir,
            ExportConfig(absolute = false, windowsPaths = false),
        )
        val unixRelLines = unixRelOut.toString(Charsets.UTF_8.name()).lines()
        assertEquals("#EXTM3U", unixRelLines[0])
        assertEquals("#EXTENC:UTF-8", unixRelLines[1])
        assertEquals("#PLAYLIST:Mi Lista", unixRelLines[2])
        assertEquals("#EXTINF:125,Track One", unixRelLines[3])
        assertTrue("./../Rock/Track One.mp3" in unixRelLines)
        assertTrue("/storage/1234-5678/Ext/Two.flac" in unixRelLines)

        val winAbsOut = ByteArrayOutputStream()
        m3u.write(
            playlist,
            winAbsOut,
            workingDir,
            ExportConfig(absolute = true, windowsPaths = true),
        )
        val winAbsText = winAbsOut.toString(Charsets.UTF_8.name())
        assertTrue("C:\\storage\\emulated\\0\\Music\\Rock\\Track One.mp3" in winAbsText)
        assertTrue("C:\\storage\\1234-5678\\Ext\\Two.flac" in winAbsText)
    }
}
