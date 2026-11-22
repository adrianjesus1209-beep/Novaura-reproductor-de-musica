/*
 * Copyright (c) 2026 Auxio Project
 * DBCacheReadTest.kt is part of Auxio.
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
 
package org.oxycblt.musikr.cache.db

import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.musikr.cache.CacheResult
import org.oxycblt.musikr.fs.AddedMs
import org.oxycblt.musikr.fs.Components
import org.oxycblt.musikr.fs.File
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.fs.Volume
import org.oxycblt.musikr.fs.path.VolumeManager

class DBCacheReadTest {
    private val uri = mockk<Uri>(relaxed = true)
    private val volume = mockk<Volume.Internal>(relaxed = true)
    private val path = Path(volume, Components.parseUnix("Music/Track.mp3"))

    private fun cachedRow(
        modifiedMs: Long = 1000L,
        size: Long = 2048L,
        unixPath: String? = "Music/Track.mp3",
        volumeName: String? = DBCache.INTERNAL_VOLUME_MARKER,
    ) =
        CachedFileData(
            uri = uri,
            modifiedMs = modifiedMs,
            addedMs = 500L,
            mimeType = "audio/mpeg",
            durationMs = 120_000L,
            bitrateKbps = 320,
            sampleRateHz = 44100,
            musicBrainzId = null,
            name = "Track",
            sortName = null,
            track = 1,
            disc = null,
            subtitle = null,
            date = null,
            albumMusicBrainzId = null,
            albumName = "Album",
            albumSortName = null,
            releaseTypes = null,
            artistMusicBrainzIds = null,
            artistNames = listOf("Artist"),
            artistSortNames = null,
            albumArtistMusicBrainzIds = null,
            albumArtistNames = null,
            albumArtistSortNames = null,
            genreNames = null,
            replayGainTrackAdjustment = null,
            replayGainAlbumAdjustment = null,
            coverId = null,
            volumeName = volumeName,
            unixPath = unixPath,
            size = size,
        )

    private fun deviceFile(
        modifiedMs: Long = 1000L,
        size: Long = 2048L,
        unixPath: String = "Music/Track.mp3",
    ) =
        File(
            uri = uri,
            path = Path(volume, Components.parseUnix(unixPath)),
            addedMs =
                object : AddedMs {
                    override suspend fun resolve() = 500L
                },
            modifiedMs = modifiedMs,
            mimeType = "audio/mpeg",
            size = size,
            parent = null,
        )

    @Test
    fun read_returnsHitOnlyWhenModifiedSizeAndUnixPathMatch() = runTest {
        val readDao = mockk<CacheReadDao>()
        coEvery { readDao.selectAllSongs() } returns listOf(cachedRow())
        val cache = DBCache.from(readDao)

        val hit = cache.read(deviceFile())
        assertTrue(hit is CacheResult.Hit)

        val sizeMismatch = cache.read(deviceFile(size = 4096L))
        assertTrue(sizeMismatch is CacheResult.Stale)

        val pathMismatch = cache.read(deviceFile(unixPath = "Music/Renamed.mp3"))
        assertTrue(pathMismatch is CacheResult.Stale)
    }

    @Test
    fun read_treatsMigratedRowWithoutUnixPathAsStale() = runTest {
        val readDao = mockk<CacheReadDao>()
        coEvery { readDao.selectAllSongs() } returns listOf(cachedRow(unixPath = null))
        val cache = DBCache.from(readDao)

        val result = cache.read(deviceFile())
        assertTrue(result is CacheResult.Stale)
    }

    @Test
    fun readAll_matchesExternalVolumeIdCaseInsensitively() = runTest {
        val sdVolume =
            mockk<Volume.External>(relaxed = true) {
                every { mediaStoreName } returns "12ab-34cd"
                every { id } returns "12ab-34cd"
                every { components } returns Components.parseUnix("/storage/12AB-34CD")
            }
        val volumeManager =
            mockk<VolumeManager> {
                every { getVolumes() } returns listOf(volume, sdVolume)
                every { getInternalVolume() } returns volume
            }
        val readDao = mockk<CacheReadDao>()
        coEvery { readDao.selectAllSongs() } returns
            listOf(cachedRow(volumeName = "12AB-34CD", unixPath = "Audio/Song.flac"))
        val db =
            mockk<CacheDatabase> {
                every { readDao() } returns readDao
            }

        val cache = DBCache.from(db, volumeManager)
        val entries = cache.readAll()

        assertEquals(1, entries.size)
        assertEquals(sdVolume, entries[0].file.path.volume)
        assertEquals("Audio/Song.flac", entries[0].file.path.components.unixString)
    }

    @Test
    fun writeAll_invalidatesMappingEvenWhenDaoThrows() = runTest {
        val readDao = mockk<CacheReadDao>()
        val writeDao = mockk<CacheWriteDao>(relaxed = true)
        coEvery { readDao.selectAllSongs() } returns emptyList()
        coEvery { writeDao.updateSongs(any()) } throws IllegalStateException("disk full")

        val dbCache = DBCache.from(readDao)
        val mutableCache = MutableDBCache.from(dbCache, writeDao)

        assertTrue(mutableCache.read(deviceFile()) is CacheResult.Miss)

        val thrown =
            try {
                mutableCache.writeAll(
                    listOf(
                        org.oxycblt.musikr.cache.CachedFile(
                            file = deviceFile(),
                            audio = null,
                            addedMs = 500L,
                        )
                    )
                )
                false
            } catch (_: IllegalStateException) {
                true
            }
        assertTrue(thrown)

        mutableCache.read(deviceFile())
        coVerify(exactly = 2) { readDao.selectAllSongs() }
    }
}
