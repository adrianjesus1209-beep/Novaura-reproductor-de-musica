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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.auxio.R
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
    private val historyFile by lazy { File(context.filesDir, "online_history.json") }
    private val countsFile by lazy { File(context.filesDir, "online_play_counts.json") }
    private val fileMutex = Mutex()

    private val _playlists = MutableStateFlow<List<OnlinePlaylist>>(emptyList())
    val playlists: StateFlow<List<OnlinePlaylist>> = _playlists.asStateFlow()

    private val _recentSongs = MutableStateFlow<List<OnlineSong>>(emptyList())
    val recentSongs: StateFlow<List<OnlineSong>> = _recentSongs.asStateFlow()

    private val _playCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val playCounts: StateFlow<Map<String, Int>> = _playCounts.asStateFlow()

    init {
        scope.launch {
            loadPlaylists()
            loadHistoryAndCounts()
        }
    }

    private suspend fun loadPlaylists() =
        withContext(Dispatchers.IO) {
            fileMutex.withLock {
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
                            OnlinePlaylist(
                                id = id,
                                name = name,
                                createdAt = createdAt,
                                songs = songs,
                            )
                        )
                    }
                    _playlists.value = list
                } catch (e: Exception) {
                    L.e(e, "Failed to load online playlists")
                    _playlists.value = emptyList()
                }
            }
        }

    private suspend fun savePlaylists() =
        withContext(Dispatchers.IO) {
            fileMutex.withLock {
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
                    val tempFile = File(context.filesDir, "online_playlists.json.tmp")
                    tempFile.writeText(array.toString(2))
                    if (!tempFile.renameTo(playlistFile)) {
                        tempFile.copyTo(playlistFile, overwrite = true)
                        tempFile.delete()
                    }
                } catch (e: Exception) {
                    L.e(e, "Failed to save online playlists")
                }
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

    fun addSongsToPlaylist(playlistId: String, songsToAdd: List<OnlineSong>): Int {
        if (songsToAdd.isEmpty()) return 0
        val playlist = _playlists.value.find { it.id == playlistId } ?: return 0
        val existingUrls = playlist.songs.map { it.webUrl }.toHashSet()
        val newSongs = songsToAdd.distinctBy { it.webUrl }.filter { it.webUrl !in existingUrls }
        if (newSongs.isEmpty()) return 0

        _playlists.value =
            _playlists.value.map {
                if (it.id == playlistId) {
                    it.copy(songs = it.songs + newSongs)
                } else {
                    it
                }
            }
        scope.launch { savePlaylists() }
        return newSongs.size
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

    fun getFavoritesPlaylist(): OnlinePlaylist? =
        _playlists.value.firstOrNull { it.name.equals(FAVORITES_NAME, ignoreCase = true) }

    fun isFavorite(song: OnlineSong): Boolean =
        getFavoritesPlaylist()?.songs?.any { it.webUrl == song.webUrl } == true

    fun toggleFavorites(songs: List<OnlineSong>): Boolean {
        if (songs.isEmpty()) return false
        val favPlaylist = getFavoritesPlaylist() ?: createPlaylist(FAVORITES_NAME)
        return if (songs.size == 1) {
            val song = songs.first()
            if (favPlaylist.songs.any { it.webUrl == song.webUrl }) {
                removeSongFromPlaylist(favPlaylist.id, song.webUrl)
                false
            } else {
                addSongToPlaylist(favPlaylist.id, song)
                true
            }
        } else {
            addSongsToPlaylist(favPlaylist.id, songs)
            true
        }
    }

    fun recordPlay(song: OnlineSong) {
        val currentRecent =
            _recentSongs.value.filterNot { it.webUrl == song.webUrl }.toMutableList()
        currentRecent.add(song)
        while (currentRecent.size > MAX_RECENT_SONGS) {
            currentRecent.removeAt(0)
        }
        _recentSongs.value = currentRecent

        val updatedCounts = _playCounts.value.toMutableMap()
        updatedCounts[song.webUrl] = (updatedCounts[song.webUrl] ?: 0) + 1
        _playCounts.value = updatedCounts

        scope.launch { saveHistoryAndCounts() }
    }

    fun getPlayCount(song: OnlineSong): Int = _playCounts.value[song.webUrl] ?: 0

    fun getMostPlayedSongs(limit: Int = 50): List<OnlineSong> {
        val counts = _playCounts.value
        val allKnown =
            (_recentSongs.value + _playlists.value.flatMap { it.songs }).distinctBy { it.webUrl }
        return allKnown
            .filter { (counts[it.webUrl] ?: 0) > 0 }
            .sortedByDescending { counts[it.webUrl] ?: 0 }
            .take(limit)
    }

    fun generateMixTitle(currentSong: OnlineSong?, sourceTitle: String? = null): String {
        if (!sourceTitle.isNullOrBlank()) {
            return sourceTitle
        }
        val currentArtist =
            currentSong?.artist?.takeIf {
                it.isNotBlank() &&
                    !it.contains("unknown", ignoreCase = true) &&
                    !it.contains("desconocido", ignoreCase = true)
            }
        val dominantArtist =
            currentArtist
                ?: _recentSongs.value
                    .mapNotNull { s ->
                        s.artist.takeIf {
                            it.isNotBlank() &&
                                !it.contains("unknown", ignoreCase = true) &&
                                !it.contains("desconocido", ignoreCase = true)
                        }
                    }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key

        return if (dominantArtist != null) {
            context.getString(R.string.fmt_mix_artist, dominantArtist)
        } else if (currentSong != null && currentSong.title.isNotBlank()) {
            context.getString(R.string.fmt_mix_artist, currentSong.title)
        } else {
            context.getString(R.string.lbl_all_songs)
        }
    }

    private suspend fun loadHistoryAndCounts() =
        withContext(Dispatchers.IO) {
            fileMutex.withLock {
                try {
                    if (historyFile.exists()) {
                        val array = JSONArray(historyFile.readText())
                        val recent = mutableListOf<OnlineSong>()
                        for (i in 0 until array.length()) {
                            val sObj = array.getJSONObject(i)
                            recent.add(
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
                        _recentSongs.value = recent
                    }
                    if (countsFile.exists()) {
                        val obj = JSONObject(countsFile.readText())
                        val map = mutableMapOf<String, Int>()
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            map[key] = obj.optInt(key, 0)
                        }
                        _playCounts.value = map
                    }
                } catch (e: Exception) {
                    L.e(e, "Failed to load online history and play counts")
                }
            }
        }

    private suspend fun saveHistoryAndCounts() =
        withContext(Dispatchers.IO) {
            fileMutex.withLock {
                try {
                    val hArr = JSONArray()
                    for (s in _recentSongs.value) {
                        hArr.put(
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
                    historyFile.writeText(hArr.toString())

                    val cObj = JSONObject()
                    for ((url, count) in _playCounts.value) {
                        cObj.put(url, count)
                    }
                    countsFile.writeText(cObj.toString())
                } catch (e: Exception) {
                    L.e(e, "Failed to save online history and play counts")
                }
            }
        }

    companion object {
        const val FAVORITES_NAME = "Favoritos"
        private const val MAX_RECENT_SONGS = 50
    }
}
