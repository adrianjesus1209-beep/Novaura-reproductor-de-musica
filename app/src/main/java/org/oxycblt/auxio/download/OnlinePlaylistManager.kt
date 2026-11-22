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
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
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
class OnlinePlaylistManager
internal constructor(
    private val filesDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val favoritesLabel: String = FAVORITES_NAME,
    private val mixArtistTemplate: (String) -> String = { "Mix de $it" },
    private val allSongsLabel: String = "Todas las canciones",
) {
    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(
        filesDir = context.filesDir,
        ioDispatcher = Dispatchers.IO,
        favoritesLabel = context.getString(R.string.lbl_favorites),
        mixArtistTemplate = { artist -> context.getString(R.string.fmt_mix_artist, artist) },
        allSongsLabel = context.getString(R.string.lbl_all_songs),
    )

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + ioDispatcher)
    private val playlistFile by lazy { File(filesDir, "online_playlists.json") }
    private val historyFile by lazy { File(filesDir, "online_history.json") }
    private val countsFile by lazy { File(filesDir, "online_play_counts.json") }
    private val knownSongsFile by lazy { File(filesDir, "online_known_songs.json") }
    private val fileMutex = Mutex()
    private val stateLock = Any()
    private val knownSongs = LinkedHashMap<String, OnlineSong>(MAX_KNOWN_SONGS, 0.75f, true)

    private val _playlists = MutableStateFlow<List<OnlinePlaylist>>(emptyList())
    val playlists: StateFlow<List<OnlinePlaylist>> = _playlists.asStateFlow()

    private val _recentSongs = MutableStateFlow<List<OnlineSong>>(emptyList())
    val recentSongs: StateFlow<List<OnlineSong>> = _recentSongs.asStateFlow()

    private val _playCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val playCounts: StateFlow<Map<String, Int>> = _playCounts.asStateFlow()

    private val initJob: Job = scope.launch {
        fileMutex.withLock {
            loadPlaylistsLocked()
            loadHistoryAndCountsLocked()
        }
    }

    internal suspend fun awaitIdle() {
        initJob.join()
        scopeJob.children.toList().joinAll()
    }

    private suspend fun loadPlaylistsLocked() =
        withContext(ioDispatcher) {
            if (!playlistFile.exists()) {
                return@withContext
            }

            try {
                val content = playlistFile.readText()
                val array = JsonParser.array().from(content)
                val loaded = mutableListOf<OnlinePlaylist>()
                var migratedFavoritesId = false

                for (i in 0 until array.size) {
                    val obj = array.getObject(i) ?: continue
                    val rawId =
                        obj.getString("id")?.takeIf { it.isNotBlank() }
                            ?: UUID.randomUUID().toString()
                    val name = obj.getString("name")?.takeIf { it.isNotBlank() } ?: "Mi Lista"
                    val createdAt = obj.getLong("createdAt", System.currentTimeMillis())
                    val songsArray = obj.getArray("songs") ?: JsonArray()
                    val songs = parseJsonSongArray(songsArray)

                    val isLegacyFavorites =
                        rawId != ID_FAVORITES &&
                            !migratedFavoritesId &&
                            (name.equals(FAVORITES_NAME, ignoreCase = true) ||
                                name.equals(favoritesLabel, ignoreCase = true))
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
                            filesDir,
                            "online_playlists.corrupt.${System.currentTimeMillis()}.json",
                        )
                    playlistFile.copyTo(backup, overwrite = true)
                }
            }
        }

    private suspend fun savePlaylists() =
        withContext(ioDispatcher) { fileMutex.withLock { savePlaylistsLocked() } }

    private fun savePlaylistsLocked() {
        try {
            val snapshot = synchronized(stateLock) { _playlists.value }
            val array = JsonArray()
            for (p in snapshot) {
                val obj =
                    JsonObject().apply {
                        put("id", p.id)
                        put("name", p.name)
                        put("createdAt", p.createdAt)
                        put("songs", serializeSongArray(p.songs))
                    }
                array.add(obj)
            }
            writeFileAtomically(
                playlistFile,
                JsonWriter.indent("  ").string().value(array).done(),
            )
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
                it.name.equals(favoritesLabel, ignoreCase = true)
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
                            name = favoritesLabel,
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
            currentRecent.add(0, song)
            while (currentRecent.size > MAX_RECENT_SONGS) {
                currentRecent.removeAt(currentRecent.lastIndex)
            }
            _recentSongs.value = currentRecent

            knownSongs[song.webUrl] = song
            while (knownSongs.size > MAX_KNOWN_SONGS) {
                val eldestKey = knownSongs.keys.firstOrNull() ?: break
                knownSongs.remove(eldestKey)
            }

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
                (_recentSongs.value + knownSongs.values + _playlists.value.flatMap { it.songs })
                    .distinctBy { it.webUrl }
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
            mixArtistTemplate(dominantArtist)
        } else if (currentSong != null && currentSong.title.isNotBlank()) {
            mixArtistTemplate(currentSong.title)
        } else {
            allSongsLabel
        }
    }

    private fun parseSongArray(content: String): MutableList<OnlineSong> =
        parseJsonSongArray(JsonParser.array().from(content))

    private fun parseJsonSongArray(array: JsonArray): MutableList<OnlineSong> {
        val songs = mutableListOf<OnlineSong>()
        for (i in 0 until array.size) {
            val sObj = array.getObject(i) ?: continue
            songs.add(
                OnlineSong(
                    id = sObj.getString("id").orEmpty(),
                    title = sObj.getString("title").orEmpty(),
                    artist = sObj.getString("artist").orEmpty(),
                    durationText = sObj.getString("durationText").orEmpty(),
                    durationSeconds = sObj.getLong("durationSeconds", 0L),
                    thumbnailUrl = sObj.getString("thumbnailUrl")?.takeIf { it.isNotBlank() },
                    webUrl = sObj.getString("webUrl").orEmpty(),
                    viewCount = sObj.getLong("viewCount", 0L),
                )
            )
        }
        return songs
    }

    private fun serializeSongArray(songs: Collection<OnlineSong>): JsonArray {
        val array = JsonArray()
        for (s in songs) {
            array.add(
                JsonObject().apply {
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
        return array
    }

    private suspend fun loadHistoryAndCountsLocked() =
        withContext(ioDispatcher) {
            try {
                if (historyFile.exists()) {
                    val recent = parseSongArray(historyFile.readText())
                    synchronized(stateLock) {
                        val early = _recentSongs.value
                        _recentSongs.value =
                            if (early.isEmpty()) {
                                recent.take(MAX_RECENT_SONGS)
                            } else {
                                (early + recent).distinctBy { it.webUrl }.take(MAX_RECENT_SONGS)
                            }
                        for (s in recent) {
                            knownSongs.putIfAbsent(s.webUrl, s)
                        }
                    }
                }
                if (knownSongsFile.exists()) {
                    val loadedKnown = parseSongArray(knownSongsFile.readText())
                    synchronized(stateLock) {
                        val existingEarly = knownSongs.values.toList()
                        for (s in loadedKnown) {
                            knownSongs.putIfAbsent(s.webUrl, s)
                        }
                        for (s in existingEarly) {
                            knownSongs[s.webUrl] = s
                        }
                        while (knownSongs.size > MAX_KNOWN_SONGS) {
                            val eldestKey = knownSongs.keys.firstOrNull() ?: break
                            knownSongs.remove(eldestKey)
                        }
                    }
                }
                if (countsFile.exists()) {
                    val obj = JsonParser.`object`().from(countsFile.readText())
                    val map = mutableMapOf<String, Int>()
                    for (key in obj.keys) {
                        map[key] = obj.getInt(key, 0)
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
        withContext(ioDispatcher) { fileMutex.withLock { saveHistoryAndCountsLocked() } }

    private fun saveHistoryAndCountsLocked() {
        try {
            val recentSnapshot: List<OnlineSong>
            val knownSnapshot: List<OnlineSong>
            val countsSnapshot: Map<String, Int>
            synchronized(stateLock) {
                recentSnapshot = _recentSongs.value
                knownSnapshot = knownSongs.values.toList()
                countsSnapshot = _playCounts.value
            }

            writeFileAtomically(historyFile, JsonWriter.string(serializeSongArray(recentSnapshot)))
            writeFileAtomically(
                knownSongsFile,
                JsonWriter.string(serializeSongArray(knownSnapshot)),
            )

            val cObj = JsonObject()
            for ((url, count) in countsSnapshot) {
                cObj.put(url, count)
            }
            writeFileAtomically(countsFile, JsonWriter.string(cObj))
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
        private const val MAX_KNOWN_SONGS = 300
    }
}
