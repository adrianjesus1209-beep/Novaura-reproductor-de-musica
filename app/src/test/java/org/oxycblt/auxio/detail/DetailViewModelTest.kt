/*
 * Copyright (c) 2026 Auxio Project
 * DetailViewModelTest.kt is part of Auxio.
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
 
package org.oxycblt.auxio.detail

import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.list.ListSettings
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song

class DetailViewModelTest {
    private fun fakeSong(tag: String): Song {
        val uidValue =
            requireNotNull(Music.UID.fromString("uas${UUID.nameUUIDFromBytes(tag.toByteArray())}"))
        return mockk<Song>(relaxed = true) { every { uid } returns uidValue }
    }

    @Test
    fun movePlaylistSongs_reordersItemsAndValidatesAdapterBounds() {
        val s0 = fakeSong("p0")
        val s1 = fakeSong("p1")
        val s2 = fakeSong("p2")
        val playlistUid = requireNotNull(Music.UID.fromString("uap${UUID.randomUUID()}"))
        val playlist =
            mockk<Playlist>(relaxed = true) {
                every { uid } returns playlistUid
                every { songs } returns listOf(s0, s1, s2)
            }

        val generator =
            mockk<DetailGenerator>(relaxed = true) {
                every { playlist(playlistUid) } returns
                    Detail(playlist, listOf(DetailSection.Songs(listOf(s0, s1, s2))))
            }
        val factory =
            object : DetailGenerator.Factory {
                override fun create(invalidator: DetailGenerator.Invalidator) = generator
            }

        val viewModel =
            DetailViewModel(
                listSettings = mockk<ListSettings>(relaxed = true),
                musicRepository = mockk<MusicRepository>(relaxed = true),
                playbackSettings = mockk<PlaybackSettings>(relaxed = true),
                detailGeneratorFactory = factory,
            )

        // Not editing yet -> move must fail
        viewModel.setPlaylist(playlistUid)
        assertFalse(viewModel.movePlaylistSongs(1, 3))

        viewModel.startPlaylistEdit()
        assertEquals(listOf(s0, s1, s2), viewModel.editedPlaylist.value)

        // Header is index 0, songs sit at adapter indices 1..3
        assertFalse(viewModel.movePlaylistSongs(0, 1))
        assertFalse(viewModel.movePlaylistSongs(1, 4))
        assertFalse(viewModel.movePlaylistSongs(2, 2))
        assertFalse(viewModel.movePlaylistSongs(-1, 2))

        // Move adapter index 1 (song 0) to adapter index 3 (song 2's slot) -> [s1, s2, s0]
        assertTrue(viewModel.movePlaylistSongs(1, 3))
        assertEquals(listOf(s1, s2, s0), viewModel.editedPlaylist.value)

        // Move back from 3 to 2 -> [s1, s0, s2]
        assertTrue(viewModel.movePlaylistSongs(3, 2))
        assertEquals(listOf(s1, s0, s2), viewModel.editedPlaylist.value)
    }
}
