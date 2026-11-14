/*
 * Copyright (c) 2026 Auxio Project
 * SearchIndex.kt is part of Auxio.
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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.music.resolve
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.Library
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.Name
import timber.log.Timber as L

/**
 * Pre-computed in-memory search index for instant (< 3ms) searches without repeated string
 * normalization or regex matching per keystroke.
 */
@Singleton
class SearchIndex @Inject constructor(@ApplicationContext private val context: Context) {
    data class IndexedSong(
        val song: Song,
        val normalizedName: String,
        val sortName: String?,
        val normalizedArtist: String?,
        val normalizedAlbum: String?,
        val pathName: String?,
    )

    data class IndexedAlbum(
        val album: Album,
        val normalizedName: String,
        val sortName: String?,
        val normalizedArtist: String?,
    )

    data class IndexedArtist(
        val artist: Artist,
        val normalizedName: String,
        val sortName: String?,
    )

    data class IndexedGenre(
        val genre: Genre,
        val normalizedName: String,
    )

    data class IndexedPlaylist(
        val playlist: Playlist,
        val normalizedName: String,
    )

    private class IndexSnapshot(
        val songs: List<IndexedSong> = emptyList(),
        val albums: List<IndexedAlbum> = emptyList(),
        val artists: List<IndexedArtist> = emptyList(),
        val genres: List<IndexedGenre> = emptyList(),
        val playlists: List<IndexedPlaylist> = emptyList(),
    )

    @Volatile
    var isReady: Boolean = false
        private set

    private val buildMutex = Mutex()
    @Volatile private var snapshot = IndexSnapshot()

    /** Builds the pre-normalized in-memory index from the loaded [Library]. */
    suspend fun build(library: Library) =
        withContext(Dispatchers.Default) {
            buildMutex.withLock {
                L.d("Building in-memory SearchIndex for ${library.songs.size} songs")
                val startTime = System.currentTimeMillis()

                val songsList = ArrayList<IndexedSong>(library.songs.size)
                for (song in library.songs) {
                    val resolvedName = song.name.resolve(context)
                    val normalizedName = normalizeString(resolvedName)
                    val sortName = song.name.sort?.let { normalizeString(it) }
                    val artistName =
                        song.artists.firstOrNull()?.name?.resolve(context)?.let {
                            normalizeString(it)
                        }
                    val albumName = normalizeString(song.album.name.resolve(context))
                    val pathName = song.path.name?.let { normalizeString(it) }

                    songsList.add(
                        IndexedSong(
                            song = song,
                            normalizedName = normalizedName,
                            sortName = sortName,
                            normalizedArtist = artistName,
                            normalizedAlbum = albumName,
                            pathName = pathName,
                        )
                    )
                }

                val albumsList = ArrayList<IndexedAlbum>(library.albums.size)
                for (album in library.albums) {
                    val resolvedName = album.name.resolve(context)
                    val normalizedName = normalizeString(resolvedName)
                    val sortName = (album.name as? Name.Known)?.sort?.let { normalizeString(it) }
                    val artistName =
                        album.artists.firstOrNull()?.name?.resolve(context)?.let {
                            normalizeString(it)
                        }
                    albumsList.add(
                        IndexedAlbum(
                            album = album,
                            normalizedName = normalizedName,
                            sortName = sortName,
                            normalizedArtist = artistName,
                        )
                    )
                }

                val artistsList = ArrayList<IndexedArtist>(library.artists.size)
                for (artist in library.artists) {
                    val resolvedName = artist.name.resolve(context)
                    val normalizedName = normalizeString(resolvedName)
                    val sortName = (artist.name as? Name.Known)?.sort?.let { normalizeString(it) }
                    artistsList.add(
                        IndexedArtist(
                            artist = artist,
                            normalizedName = normalizedName,
                            sortName = sortName,
                        )
                    )
                }

                val genresList = ArrayList<IndexedGenre>(library.genres.size)
                for (genre in library.genres) {
                    val resolvedName = genre.name.resolve(context)
                    genresList.add(
                        IndexedGenre(
                            genre = genre,
                            normalizedName = normalizeString(resolvedName),
                        )
                    )
                }

                val playlistsList = ArrayList<IndexedPlaylist>(library.playlists.size)
                for (playlist in library.playlists) {
                    val resolvedName = playlist.name.resolve(context)
                    playlistsList.add(
                        IndexedPlaylist(
                            playlist = playlist,
                            normalizedName = normalizeString(resolvedName),
                        )
                    )
                }

                snapshot =
                    IndexSnapshot(
                        songs = songsList,
                        albums = albumsList,
                        artists = artistsList,
                        genres = genresList,
                        playlists = playlistsList,
                    )
                isReady = true

                val tookMs = System.currentTimeMillis() - startTime
                L.d(
                    "SearchIndex built in ${tookMs}ms: ${songsList.size} songs, ${albumsList.size} albums, ${artistsList.size} artists"
                )
            }
        }

    fun searchSongs(query: String, filterCollection: Collection<Song>?): List<Song> {
        val currentSnapshot = snapshot
        val cleanQuery = normalizeString(query)
        val rawQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty() && rawQuery.isEmpty()) return emptyList()

        val allowedSet = filterCollection?.toSet()
        val results = ArrayList<Song>()

        for (item in currentSnapshot.songs) {
            if (allowedSet != null && !allowedSet.contains(item.song)) continue

            val matches =
                if (cleanQuery.isNotEmpty()) {
                    item.normalizedName.contains(cleanQuery) ||
                        (item.sortName != null && item.sortName.contains(cleanQuery)) ||
                        (item.normalizedArtist != null &&
                            item.normalizedArtist.contains(cleanQuery)) ||
                        (item.normalizedAlbum != null &&
                            item.normalizedAlbum.contains(cleanQuery)) ||
                        (item.pathName != null && item.pathName.contains(cleanQuery))
                } else {
                    item.song.name.resolve(context).lowercase().contains(rawQuery) ||
                        (item.song.artists
                            .firstOrNull()
                            ?.name
                            ?.resolve(context)
                            ?.lowercase()
                            ?.contains(rawQuery) == true) ||
                        item.song.album.name.resolve(context).lowercase().contains(rawQuery) ||
                        (item.song.path.name?.lowercase()?.contains(rawQuery) == true)
                }
            if (matches) {
                results.add(item.song)
            }
        }
        return results
    }

    fun searchAlbums(query: String, filterCollection: Collection<Album>?): List<Album> {
        val currentSnapshot = snapshot
        val cleanQuery = normalizeString(query)
        val rawQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty() && rawQuery.isEmpty()) return emptyList()

        val allowedSet = filterCollection?.toSet()
        val results = ArrayList<Album>()

        for (item in currentSnapshot.albums) {
            if (allowedSet != null && !allowedSet.contains(item.album)) continue

            val matches =
                if (cleanQuery.isNotEmpty()) {
                    item.normalizedName.contains(cleanQuery) ||
                        (item.sortName != null && item.sortName.contains(cleanQuery)) ||
                        (item.normalizedArtist != null &&
                            item.normalizedArtist.contains(cleanQuery))
                } else {
                    item.album.name.resolve(context).lowercase().contains(rawQuery) ||
                        (item.album.artists
                            .firstOrNull()
                            ?.name
                            ?.resolve(context)
                            ?.lowercase()
                            ?.contains(rawQuery) == true)
                }
            if (matches) {
                results.add(item.album)
            }
        }
        return results
    }

    fun searchArtists(query: String, filterCollection: Collection<Artist>?): List<Artist> {
        val currentSnapshot = snapshot
        val cleanQuery = normalizeString(query)
        val rawQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty() && rawQuery.isEmpty()) return emptyList()

        val allowedSet = filterCollection?.toSet()
        val results = ArrayList<Artist>()

        for (item in currentSnapshot.artists) {
            if (allowedSet != null && !allowedSet.contains(item.artist)) continue

            val matches =
                if (cleanQuery.isNotEmpty()) {
                    item.normalizedName.contains(cleanQuery) ||
                        (item.sortName != null && item.sortName.contains(cleanQuery))
                } else {
                    item.artist.name.resolve(context).lowercase().contains(rawQuery)
                }
            if (matches) {
                results.add(item.artist)
            }
        }
        return results
    }

    fun searchGenres(query: String, filterCollection: Collection<Genre>?): List<Genre> {
        val currentSnapshot = snapshot
        val cleanQuery = normalizeString(query)
        val rawQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty() && rawQuery.isEmpty()) return emptyList()

        val allowedSet = filterCollection?.toSet()
        val results = ArrayList<Genre>()

        for (item in currentSnapshot.genres) {
            if (allowedSet != null && !allowedSet.contains(item.genre)) continue

            val matches =
                if (cleanQuery.isNotEmpty()) {
                    item.normalizedName.contains(cleanQuery)
                } else {
                    item.genre.name.resolve(context).lowercase().contains(rawQuery)
                }
            if (matches) {
                results.add(item.genre)
            }
        }
        return results
    }

    fun searchPlaylists(query: String, filterCollection: Collection<Playlist>?): List<Playlist> {
        val currentSnapshot = snapshot
        val cleanQuery = normalizeString(query)
        val rawQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty() && rawQuery.isEmpty()) return emptyList()

        val allowedSet = filterCollection?.toSet()
        val results = ArrayList<Playlist>()

        for (item in currentSnapshot.playlists) {
            if (allowedSet != null && !allowedSet.contains(item.playlist)) continue

            val matches =
                if (cleanQuery.isNotEmpty()) {
                    item.normalizedName.contains(cleanQuery)
                } else {
                    item.playlist.name.resolve(context).lowercase().contains(rawQuery)
                }
            if (matches) {
                results.add(item.playlist)
            }
        }
        return results
    }

    companion object {
        private val NORMALIZE_POST_PROCESSING_REGEX by lazy {
            Regex("(\\p{InCombiningDiacriticalMarks}+)|(\\p{Punct})")
        }

        fun normalizeString(input: String): String {
            val normalized = Normalizer.normalize(input, Normalizer.Form.NFKD)
            return NORMALIZE_POST_PROCESSING_REGEX.replace(normalized, "").lowercase()
        }
    }
}
