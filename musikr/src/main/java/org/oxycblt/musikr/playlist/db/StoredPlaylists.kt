/*
 * Copyright (c) 2024 Auxio Project
 * StoredPlaylists.kt is part of Auxio.
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
 
package org.oxycblt.musikr.playlist.db

import android.content.Context
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.playlist.PlaylistFile
import org.oxycblt.musikr.playlist.PlaylistHandle
import org.oxycblt.musikr.playlist.SongPointer

abstract class StoredPlaylists {
    internal abstract suspend fun new(name: String, songs: List<Song>): PlaylistHandle

    internal abstract suspend fun read(): List<PlaylistFile>

    companion object {
        fun from(context: Context): StoredPlaylists =
            StoredPlaylistsImpl(PlaylistDatabase.from(context).playlistDao())
    }
}

private class StoredPlaylistsImpl(private val playlistDao: PlaylistDao) : StoredPlaylists() {
    override suspend fun new(name: String, songs: List<Song>): PlaylistHandle {
        val info = PlaylistInfo(Music.UID.auxio(Music.UID.Item.PLAYLIST), name)
        playlistDao.insertPlaylist(RawPlaylist(info, songs.map { PlaylistSong(it.uid) }))
        return StoredPlaylistHandle(info, playlistDao)
    }

    override suspend fun read() =
        playlistDao.readRawPlaylists().map { raw ->
            val orderedSongUids = playlistDao.getSongUidsForPlaylist(raw.playlistInfo.playlistUid)
            val songPointers =
                if (orderedSongUids.isNotEmpty()) {
                    orderedSongUids.map { SongPointer.UID(it) }
                } else {
                    raw.songs.map { SongPointer.UID(it.songUid) }
                }
            PlaylistFile(
                raw.playlistInfo.name,
                songPointers,
                StoredPlaylistHandle(raw.playlistInfo, playlistDao),
            )
        }
}
