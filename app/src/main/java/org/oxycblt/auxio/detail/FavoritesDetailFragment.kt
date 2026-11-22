/*
 * Copyright (c) 2026 Auxio Project
 * FavoritesDetailFragment.kt is part of Auxio.
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
import org.oxycblt.auxio.music.FavoritesManager
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song

/** Detail view presenting favorite songs as a smart playlist. */
@AndroidEntryPoint
class FavoritesDetailFragment : SmartPlaylistDetailFragment() {
    @Inject lateinit var favoritesManager: FavoritesManager

    override val titleRes: Int = R.string.lbl_favorites
    override val subheadRes: Int = R.string.lbl_favorites_album_subhead
    override val emptyDescRes: Int = R.string.lbl_favorites_empty_desc
    override val coverIconRes: Int = R.drawable.ic_favorite_24

    override fun loadSongs(): List<Song> = favoritesManager.getFavoriteSongs()

    override fun observeSongUpdates() {
        collectImmediately(favoritesManager.favoriteSongs) { refreshSmartSongs() }
        collectImmediately(favoritesManager.favoriteUids) { refreshSmartSongs() }
    }

    override fun openSongMenuDirections(parcel: Menu.ForSong.Parcel) =
        FavoritesDetailFragmentDirections.openSongMenu(parcel)

    override fun openSelectionMenuDirections(parcel: Menu.ForSelection.Parcel) =
        FavoritesDetailFragmentDirections.openSelectionMenu(parcel)

    override fun deleteSongDirections(uids: Array<Music.UID>) =
        FavoritesDetailFragmentDirections.deleteSong(uids)

    override fun showSongDirections(uid: Music.UID) =
        FavoritesDetailFragmentDirections.showSong(uid)

    override fun showAlbumDirections(uid: Music.UID) =
        FavoritesDetailFragmentDirections.showAlbum(uid)

    override fun showArtistDirections(uid: Music.UID) =
        FavoritesDetailFragmentDirections.showArtist(uid)

    override fun showArtistChoicesDirections(uid: Music.UID) =
        FavoritesDetailFragmentDirections.showArtistChoices(uid)

    override fun addToPlaylistDirections(uids: Array<Music.UID>) =
        FavoritesDetailFragmentDirections.addToPlaylist(uids)

    override fun playFromArtistDirections(uid: Music.UID) =
        FavoritesDetailFragmentDirections.playFromArtist(uid)

    override fun playFromGenreDirections(uid: Music.UID) =
        FavoritesDetailFragmentDirections.playFromGenre(uid)
}
