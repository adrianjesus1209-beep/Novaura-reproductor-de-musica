/*
 * Copyright (c) 2026 Auxio Project
 * MostPlayedDetailFragment.kt is part of Auxio.
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

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.list.menu.Menu
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.playback.stats.PlaybackStatsManager
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song

/** Detail view presenting the most played songs according to playback statistics. */
@AndroidEntryPoint
class MostPlayedDetailFragment : SmartPlaylistDetailFragment() {
    @Inject lateinit var musicRepository: MusicRepository
    @Inject lateinit var playbackStatsManager: PlaybackStatsManager

    override val titleRes: Int = R.string.lbl_most_played
    override val subheadRes: Int = R.string.lbl_most_played_subhead
    override val emptyDescRes: Int = R.string.lbl_most_played_empty_desc
    override val coverIconRes: Int = R.drawable.ic_headphones_24

    override fun loadSongs(): List<Song> {
        val library = musicRepository.library ?: return emptyList()
        return playbackStatsManager.getMostPlayedSongs(
            library.songs.toList(),
            limit = 100,
            minPlays = 1,
        )
    }

    override fun observeSongUpdates() {
        collectImmediately(musicModel.indexingState) { refreshSmartSongs() }
    }

    override fun openSongMenuDirections(parcel: Menu.ForSong.Parcel) =
        MostPlayedDetailFragmentDirections.openSongMenu(parcel)

    override fun openSelectionMenuDirections(parcel: Menu.ForSelection.Parcel) =
        MostPlayedDetailFragmentDirections.openSelectionMenu(parcel)

    override fun deleteSongDirections(uids: Array<Music.UID>) =
        MostPlayedDetailFragmentDirections.deleteSong(uids)

    override fun showSongDirections(uid: Music.UID) =
        MostPlayedDetailFragmentDirections.showSong(uid)

    override fun showAlbumDirections(uid: Music.UID) =
        MostPlayedDetailFragmentDirections.showAlbum(uid)

    override fun showArtistDirections(uid: Music.UID) =
        MostPlayedDetailFragmentDirections.showArtist(uid)

    override fun showArtistChoicesDirections(uid: Music.UID) =
        MostPlayedDetailFragmentDirections.showArtistChoices(uid)

    override fun addToPlaylistDirections(uids: Array<Music.UID>) =
        MostPlayedDetailFragmentDirections.addToPlaylist(uids)

    override fun playFromArtistDirections(uid: Music.UID) =
        MostPlayedDetailFragmentDirections.playFromArtist(uid)

    override fun playFromGenreDirections(uid: Music.UID) =
        MostPlayedDetailFragmentDirections.playFromGenre(uid)
}
