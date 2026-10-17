/*
 * Copyright (c) 2026 Auxio Project
 * OnlinePlaylistManager.kt is part of Auxio.
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
 
package org.oxycblt.auxio.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.Serializable
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber as L

data class OnlinePlaylist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val songs: List<OnlineSong> = emptyList(),
) : Serializable

@Singleton
class OnlinePlaylistManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playlistFile by lazy { File(context.filesDir, "online_playlists.json") }

    private val _playlists = MutableStateFlow<List<OnlinePlaylist>>(emptyList())
    val playlists: StateFlow<List<OnlinePlaylist>> = _playlists.asStateFlow()

    init {
        scope.launch {
            loadPlaylists()
        }
    }

    private suspend fun loadPlaylists() =
        withContext(Dispatchers.IO) {
            if (!playlistFile.exists()) {
                _playlists.value = emptyList()
                return@withContext
            }

            try {
                val content = playlistFile.readText()
                val array = JSONArray(content)
                val list = mutableListOf<OnlinePlaylist>()

                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.optString("id", UUID.randomUUID().toString())
                    val name = obj.optString("name", "Mi Lista")
                    val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    val songsArray = obj.optJSONArray("songs") ?: JSONArray()
                    val songs = mutableListOf<OnlineSong>()

                    for (j in 0 until songsArray.length()) {
                        val sObj = songsArray.getJSONObject(j)
                        songs.add(
                            OnlineSong(
                                id = sObj.optString("id"),
                                title = sObj.optString("title"),
                                artist = sObj.optString("artist"),
                                durationText = sObj.optString("durationText"),
                                durationSeconds = sObj.optLong("durationSeconds"),
                                thumbnailUrl =
                                    sObj.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                                webUrl = sObj.optString("webUrl"),
                            )
                        )
                    }

                    list.add(
                        OnlinePlaylist(id = id, name = name, createdAt = createdAt, songs = songs)
                    )
                }
                _playlists.value = list
            } catch (e: Exception) {
                L.e(e, "Failed to load online playlists")
                _playlists.value = emptyList()
            }
        }

    private suspend fun savePlaylists() =
        withContext(Dispatchers.IO) {
            try {
                val array = JSONArray()
                for (p in _playlists.value) {
                    val obj =
                        JSONObject().apply {
                            put("id", p.id)
                            put("name", p.name)
                            put("createdAt", p.createdAt)
                            val sArr = JSONArray()
                            for (s in p.songs) {
                                sArr.put(
                                    JSONObject().apply {
                                        put("id", s.id)
                                        put("title", s.title)
                                        put("artist", s.artist)
                                        put("durationText", s.durationText)
                                        put("durationSeconds", s.durationSeconds)
                                        put("thumbnailUrl", s.thumbnailUrl.orEmpty())
                                        put("webUrl", s.webUrl)
                                    }
                                )
                            }
                            put("songs", sArr)
                        }
                    array.put(obj)
                }
                playlistFile.writeText(array.toString(2))
            } catch (e: Exception) {
                L.e(e, "Failed to save online playlists")
            }
        }

    fun createPlaylist(name: String): OnlinePlaylist {
        val newPlaylist = OnlinePlaylist(name = name)
        _playlists.value = _playlists.value + newPlaylist
        scope.launch { savePlaylists() }
        return newPlaylist
    }

    fun deletePlaylist(id: String) {
        _playlists.value = _playlists.value.filter { it.id != id }
        scope.launch { savePlaylists() }
    }

    fun renamePlaylist(id: String, newName: String) {
        _playlists.value =
            _playlists.value.map {
                if (it.id == id) it.copy(name = newName) else it
            }
        scope.launch { savePlaylists() }
    }

    fun addSongToPlaylist(playlistId: String, song: OnlineSong): Boolean {
        val playlist = _playlists.value.find { it.id == playlistId } ?: return false
        if (playlist.songs.any { it.webUrl == song.webUrl }) {
            return false // Already exists
        }

        _playlists.value =
            _playlists.value.map {
                if (it.id == playlistId) {
                    it.copy(songs = it.songs + song)
                } else {
                    it
                }
            }
        scope.launch { savePlaylists() }
        return true
    }

    fun removeSongFromPlaylist(playlistId: String, songWebUrl: String) {
        _playlists.value =
            _playlists.value.map {
                if (it.id == playlistId) {
                    it.copy(songs = it.songs.filter { s -> s.webUrl != songWebUrl })
                } else {
                    it
                }
            }
        scope.launch { savePlaylists() }
    }
}
