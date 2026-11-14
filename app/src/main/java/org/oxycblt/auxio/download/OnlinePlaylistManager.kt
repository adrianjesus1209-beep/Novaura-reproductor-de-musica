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
import android.os.Parcelable
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
import kotlinx.parcelize.Parcelize
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.auxio.R
import timber.log.Timber as L

@Parcelize
data class OnlinePlaylist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val songs: List<OnlineSong> = emptyList(),
) : Parcelable

@Singleton
class OnlinePlaylistManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playlistFile by lazy { File(context.filesDir, "online_playlists.json") }
    private val historyFile by lazy { File(context.filesDir, "online_history.json") }
    private val countsFile by lazy { File(context.filesDir, "online_play_counts.json") }
    private val fileMutex = Mutex()
    private val stateLock = Any()

    private val _playlists = MutableStateFlow<List<OnlinePlaylist>>(emptyList())
    val playlists: StateFlow<List<OnlinePlaylist>> = _playlists.asStateFlow()

    private val _recentSongs = MutableStateFlow<List<OnlineSong>>(emptyList())
    val recentSongs: StateFlow<List<OnlineSong>> = _recentSongs.asStateFlow()

    private val _playCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val playCounts: StateFlow<Map<String, Int>> = _playCounts.asStateFlow()

    init {
        scope.launch {
            fileMutex.withLock {
                loadPlaylistsLocked()
                loadHistoryAndCountsLocked()
            }
        }
    }

    private suspend fun loadPlaylistsLocked() =
        withContext(Dispatchers.IO) {
            if (!playlistFile.exists()) {
                return@withContext
            }

            try {
                val content = playlistFile.readText()
                val array = JSONArray(content)
                val loaded = mutableListOf<OnlinePlaylist>()
                var migratedFavoritesId = false

                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val rawId = obj.optString("id", UUID.randomUUID().toString())
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
                                viewCount = sObj.optLong("viewCount", 0L),
                            )
                        )
                    }

                    val isLegacyFavorites =
                        rawId != ID_FAVORITES &&
                            !migratedFavoritesId &&
                            (name.equals(FAVORITES_NAME, ignoreCase = true) ||
                                name.equals(
                                    context.getString(R.string.lbl_favorites),
                                    ignoreCase = true,
                                ))
                    val normalizedId =
                        if (isLegacyFavorites) {
                            migratedFavoritesId = true
                            ID_FAVORITES
                        } else {
                            rawId
                        }

                    loaded.add(
                        OnlinePlaylist(
                            id = normalizedId,
                            name = name,
                            createdAt = createdAt,
                            songs = songs,
                        )
                    )
                }

                val mergedEarly: Boolean
                synchronized(stateLock) {
                    val early = _playlists.value
                    if (early.isEmpty()) {
                        _playlists.value = loaded
                        mergedEarly = migratedFavoritesId
                    } else {
                        val byId = loaded.associateBy { it.id }.toMutableMap()
                        for (earlyPlaylist in early) {
                            val existing = byId[earlyPlaylist.id]
                            byId[earlyPlaylist.id] =
                                if (existing == null) {
                                    earlyPlaylist
                                } else {
                                    val mergedSongs =
                                        (existing.songs + earlyPlaylist.songs).distinctBy {
                                            it.webUrl
                                        }
                                    existing.copy(songs = mergedSongs)
                                }
                        }
                        _playlists.value = byId.values.toList()
                        mergedEarly = true
                    }
                }
                if (mergedEarly) {
                    savePlaylistsLocked()
                }
            } catch (e: Exception) {
                L.e(e, "Failed to load online playlists")
                runCatching {
                    val backup =
                        File(
                            context.filesDir,
                            "online_playlists.corrupt.${System.currentTimeMillis()}.json",
                        )
                    playlistFile.copyTo(backup, overwrite = true)
                }
            }
        }

    private suspend fun savePlaylists() =
        withContext(Dispatchers.IO) { fileMutex.withLock { savePlaylistsLocked() } }

    private fun savePlaylistsLocked() {
        try {
            val snapshot = synchronized(stateLock) { _playlists.value }
            val array = JSONArray()
            for (p in snapshot) {
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
                                    put("viewCount", s.viewCount)
                                }
                            )
                        }
                        put("songs", sArr)
                    }
                array.put(obj)
            }
            writeFileAtomically(playlistFile, array.toString(2))
        } catch (e: Exception) {
            L.e(e, "Failed to save online playlists")
        }
    }

    fun createPlaylist(name: String): OnlinePlaylist {
        val newPlaylist = OnlinePlaylist(name = name)
        synchronized(stateLock) { _playlists.value = _playlists.value + newPlaylist }
        scope.launch { savePlaylists() }
        return newPlaylist
    }

    fun deletePlaylist(id: String) {
        synchronized(stateLock) { _playlists.value = _playlists.value.filter { it.id != id } }
        scope.launch { savePlaylists() }
    }

    fun renamePlaylist(id: String, newName: String) {
        synchronized(stateLock) {
            _playlists.value =
                _playlists.value.map { if (it.id == id) it.copy(name = newName) else it }
        }
        scope.launch { savePlaylists() }
    }

    fun addSongToPlaylist(playlistId: String, song: OnlineSong): Boolean {
        val added =
            synchronized(stateLock) {
                val playlist = _playlists.value.find { it.id == playlistId } ?: return false
                if (playlist.songs.any { it.webUrl == song.webUrl }) {
                    return false
                }
                _playlists.value =
                    _playlists.value.map {
                        if (it.id == playlistId) {
                            it.copy(songs = it.songs + song)
                        } else {
                            it
                        }
                    }
                true
            }
        if (added) {
            scope.launch { savePlaylists() }
        }
        return added
    }

    fun addSongsToPlaylist(playlistId: String, songsToAdd: List<OnlineSong>): Int {
        if (songsToAdd.isEmpty()) return 0
        val addedCount =
            synchronized(stateLock) {
                val playlist = _playlists.value.find { it.id == playlistId } ?: return 0
                val existingUrls = playlist.songs.map { it.webUrl }.toHashSet()
                val newSongs =
                    songsToAdd.distinctBy { it.webUrl }.filter { it.webUrl !in existingUrls }
                if (newSongs.isEmpty()) return 0

                _playlists.value =
                    _playlists.value.map {
                        if (it.id == playlistId) {
                            it.copy(songs = it.songs + newSongs)
                        } else {
                            it
                        }
                    }
                newSongs.size
            }
        if (addedCount > 0) {
            scope.launch { savePlaylists() }
        }
        return addedCount
    }

    fun removeSongFromPlaylist(playlistId: String, songWebUrl: String) {
        val removed =
            synchronized(stateLock) {
                val playlist = _playlists.value.find { it.id == playlistId } ?: return
                if (playlist.songs.none { it.webUrl == songWebUrl }) return
                _playlists.value =
                    _playlists.value.map {
                        if (it.id == playlistId) {
                            it.copy(songs = it.songs.filter { s -> s.webUrl != songWebUrl })
                        } else {
                            it
                        }
                    }
                true
            }
        if (removed) {
            scope.launch { savePlaylists() }
        }
    }

    fun getFavoritesPlaylist(): OnlinePlaylist? =
        synchronized(stateLock) { findFavoritesLocked(_playlists.value) }

    private fun findFavoritesLocked(list: List<OnlinePlaylist>): OnlinePlaylist? =
        list.firstOrNull {
            it.id == ID_FAVORITES ||
                it.name.equals(FAVORITES_NAME, ignoreCase = true) ||
                it.name.equals(context.getString(R.string.lbl_favorites), ignoreCase = true)
        }

    fun isFavorite(song: OnlineSong): Boolean =
        getFavoritesPlaylist()?.songs?.any { it.webUrl == song.webUrl } == true

    fun toggleFavorites(songs: List<OnlineSong>): Boolean {
        if (songs.isEmpty()) return false
        val addedResult: Boolean
        synchronized(stateLock) {
            val current = _playlists.value
            val existingFav = findFavoritesLocked(current)
            val favPlaylist =
                existingFav
                    ?: OnlinePlaylist(
                            id = ID_FAVORITES,
                            name = context.getString(R.string.lbl_favorites),
                        )
                        .also { created -> _playlists.value = current + created }

            val distinctSongs = songs.distinctBy { it.webUrl }
            val existingUrls = favPlaylist.songs.map { it.webUrl }.toHashSet()
            val toRemoveUrls = distinctSongs.map { it.webUrl }.toHashSet()

            val updatedFavSongs =
                if (distinctSongs.size == 1) {
                    val single = distinctSongs.first()
                    if (single.webUrl in existingUrls) {
                        addedResult = false
                        favPlaylist.songs.filterNot { it.webUrl == single.webUrl }
                    } else {
                        addedResult = true
                        favPlaylist.songs + single
                    }
                } else if (distinctSongs.all { it.webUrl in existingUrls }) {
                    addedResult = false
                    favPlaylist.songs.filterNot { it.webUrl in toRemoveUrls }
                } else {
                    addedResult = true
                    favPlaylist.songs + distinctSongs.filter { it.webUrl !in existingUrls }
                }

            _playlists.value =
                _playlists.value.map {
                    if (it.id == favPlaylist.id) it.copy(songs = updatedFavSongs) else it
                }
        }
        scope.launch { savePlaylists() }
        return addedResult
    }

    fun recordPlay(song: OnlineSong) {
        synchronized(stateLock) {
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
        }

        scope.launch { saveHistoryAndCounts() }
    }

    fun getPlayCount(song: OnlineSong): Int = _playCounts.value[song.webUrl] ?: 0

    fun getMostPlayedSongs(limit: Int = 50): List<OnlineSong> {
        val counts: Map<String, Int>
        val allKnown: List<OnlineSong>
        synchronized(stateLock) {
            counts = _playCounts.value
            allKnown =
                (_recentSongs.value + _playlists.value.flatMap { it.songs }).distinctBy {
                    it.webUrl
                }
        }
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
                ?: synchronized(stateLock) { _recentSongs.value }
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

    private suspend fun loadHistoryAndCountsLocked() =
        withContext(Dispatchers.IO) {
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
                                viewCount = sObj.optLong("viewCount", 0L),
                            )
                        )
                    }
                    synchronized(stateLock) {
                        val early = _recentSongs.value
                        if (early.isEmpty()) {
                            _recentSongs.value = recent
                        } else {
                            val merged = (recent + early).distinctBy { it.webUrl }.toMutableList()
                            while (merged.size > MAX_RECENT_SONGS) {
                                merged.removeAt(0)
                            }
                            _recentSongs.value = merged
                        }
                    }
                }
                if (countsFile.exists()) {
                    val obj = JSONObject(countsFile.readText())
                    val map = mutableMapOf<String, Int>()
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        map[key] = obj.optInt(key, 0)
                    }
                    synchronized(stateLock) {
                        val earlyCounts = _playCounts.value
                        for ((k, v) in earlyCounts) {
                            map[k] = (map[k] ?: 0) + v
                        }
                        _playCounts.value = map
                    }
                }
            } catch (e: Exception) {
                L.e(e, "Failed to load online history and play counts")
            }
        }

    private suspend fun saveHistoryAndCounts() =
        withContext(Dispatchers.IO) { fileMutex.withLock { saveHistoryAndCountsLocked() } }

    private fun saveHistoryAndCountsLocked() {
        try {
            val recentSnapshot: List<OnlineSong>
            val countsSnapshot: Map<String, Int>
            synchronized(stateLock) {
                recentSnapshot = _recentSongs.value
                countsSnapshot = _playCounts.value
            }

            val hArr = JSONArray()
            for (s in recentSnapshot) {
                hArr.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("title", s.title)
                        put("artist", s.artist)
                        put("durationText", s.durationText)
                        put("durationSeconds", s.durationSeconds)
                        put("thumbnailUrl", s.thumbnailUrl.orEmpty())
                        put("webUrl", s.webUrl)
                        put("viewCount", s.viewCount)
                    }
                )
            }
            writeFileAtomically(historyFile, hArr.toString())

            val cObj = JSONObject()
            for ((url, count) in countsSnapshot) {
                cObj.put(url, count)
            }
            writeFileAtomically(countsFile, cObj.toString())
        } catch (e: Exception) {
            L.e(e, "Failed to save online history and play counts")
        }
    }

    private fun writeFileAtomically(target: File, content: String) {
        val parent = target.parentFile ?: target.absoluteFile.parentFile
        parent?.mkdirs()
        val tmp = File(parent, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        const val ID_FAVORITES = "favorites_default"
        const val FAVORITES_NAME = "Favoritos"
        private const val MAX_RECENT_SONGS = 50
    }
}
