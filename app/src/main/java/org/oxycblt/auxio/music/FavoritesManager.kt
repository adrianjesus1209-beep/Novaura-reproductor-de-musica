/*
 * Copyright (c) 2026 Auxio Project
 * FavoritesManager.kt is part of Auxio.
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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Manages persistent storage and reactive state for favorite songs. Preserves user favorite order
 * and provides synchronization with the music library.
 */
@Singleton
class FavoritesManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _favoriteUids = MutableStateFlow<List<String>>(loadFavorites())
    val favoriteUids: StateFlow<List<String>> = _favoriteUids.asStateFlow()

    private fun loadFavorites(): List<String> {
        val raw = prefs.getString(KEY_FAVORITES_JSON, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            list
        } catch (e: Exception) {
            L.w(e, "Failed to parse favorite songs JSON")
            emptyList()
        }
    }

    private fun saveFavorites(list: List<String>) {
        val array = JSONArray()
        for (item in list) {
            array.put(item)
        }
        prefs.edit().putString(KEY_FAVORITES_JSON, array.toString()).apply()
        _favoriteUids.value = list
    }

    /** Returns whether the given song is in favorites. */
    fun isFavorite(songUid: Music.UID): Boolean {
        return _favoriteUids.value.contains(songUid.toString())
    }

    /** Returns whether the given song is in favorites. */
    fun isFavorite(song: Song): Boolean {
        return isFavorite(song.uid)
    }

    /**
     * Toggles the favorite status of a song. Newly added songs are placed at the top of the
     * favorites list.
     *
     * @return true if the song is now a favorite, false if it was removed.
     */
    @Synchronized
    fun toggleFavorite(songUid: Music.UID): Boolean {
        val uidStr = songUid.toString()
        val current = _favoriteUids.value.toMutableList()
        val nowFavorite =
            if (current.contains(uidStr)) {
                current.remove(uidStr)
                false
            } else {
                current.add(0, uidStr)
                true
            }
        saveFavorites(current)
        return nowFavorite
    }

    /** Toggles favorite status using the [Song] instance. */
    fun toggleFavorite(song: Song): Boolean = toggleFavorite(song.uid)

    /** Explicitly set or remove favorite status. */
    @Synchronized
    fun setFavorite(songUid: Music.UID, favorite: Boolean) {
        val uidStr = songUid.toString()
        val current = _favoriteUids.value.toMutableList()
        if (favorite) {
            if (!current.contains(uidStr)) {
                current.add(0, uidStr)
                saveFavorites(current)
            }
        } else {
            if (current.remove(uidStr)) {
                saveFavorites(current)
            }
        }
    }

    /**
     * Resolves the current favorite songs against the active music library. Songs are returned in
     * the order they were favorited.
     */
    fun getFavoriteSongs(): List<Song> {
        val library = musicRepository.library ?: return emptyList()
        val uids = _favoriteUids.value
        val result = ArrayList<Song>(uids.size)
        for (uidStr in uids) {
            val uid = Music.UID.fromString(uidStr) ?: continue
            val song = library.findSong(uid)
            if (song != null) {
                result.add(song)
            }
        }
        return result
    }

    companion object {
        private const val PREFS_NAME = "novaura_favorites"
        private const val KEY_FAVORITES_JSON = "favorite_song_uids"
    }
}
