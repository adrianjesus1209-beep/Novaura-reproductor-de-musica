/*
 * Copyright (c) 2026 Auxio Project
 * DownloadViewModel.kt is part of Auxio.
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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber as L

sealed interface DownloadEvent {
    data class Started(val title: String) : DownloadEvent

    data class Success(val title: String) : DownloadEvent

    data class Error(val title: String) : DownloadEvent
}

enum class SearchTypeFilter {
    ALL,
    SONGS,
    PLAYLISTS,
}

enum class SearchDurationFilter {
    ANY,
    SHORT,
    MEDIUM,
    LONG,
}

enum class SearchSortFilter {
    RELEVANCE,
    VIEWS,
}

@HiltViewModel
class DownloadViewModel
@Inject
constructor(
    private val downloadRepository: DownloadRepository,
    private val downloadManager: DownloadManager,
    private val onlinePlayerManager: OnlinePlayerManager,
    private val onlinePlaylistManager: OnlinePlaylistManager,
) : ViewModel() {

    private val _searchResults = MutableStateFlow<List<OnlineSong>>(emptyList())
    val searchResults: StateFlow<List<OnlineSong>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // Filters
    val typeFilter = MutableStateFlow(SearchTypeFilter.ALL)
    val durationFilter = MutableStateFlow(SearchDurationFilter.ANY)
    val sortFilter = MutableStateFlow(SearchSortFilter.RELEVANCE)

    val displayedResults: StateFlow<List<OnlineSong>> =
        combine(_searchResults, typeFilter, durationFilter, sortFilter) { results, type, dur, sort
                ->
                var list = results

                // Filter type
                when (type) {
                    SearchTypeFilter.SONGS -> list = list.filter { it.durationSeconds in 1..900 }
                    SearchTypeFilter.PLAYLISTS -> list = list.filter { it.durationSeconds > 900 }
                    SearchTypeFilter.ALL -> {}
                }

                // Filter duration
                when (dur) {
                    SearchDurationFilter.SHORT -> list = list.filter {
                            it.durationSeconds in 1..240
                        }
                    SearchDurationFilter.MEDIUM -> list = list.filter {
                            it.durationSeconds in 241..600
                        }
                    SearchDurationFilter.LONG -> list = list.filter { it.durationSeconds > 600 }
                    SearchDurationFilter.ANY -> {}
                }

                // Sort
                when (sort) {
                    SearchSortFilter.RELEVANCE -> list
                    SearchSortFilter.VIEWS -> list.sortedByDescending { it.durationSeconds }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Download state delegated to background DownloadManager
    val downloadItems: StateFlow<List<DownloadItem>> = downloadManager.downloadItems
    val downloadProgress: StateFlow<Map<String, Int>> = downloadManager.downloadProgress
    val activeCount: StateFlow<Int> = downloadManager.activeCount
    val events: SharedFlow<DownloadEvent> = downloadManager.events

    // Online audio streaming player state
    val playerState: StateFlow<OnlinePlayerState> = onlinePlayerManager.playerState
    val playerError: SharedFlow<String> = onlinePlayerManager.playerError

    // Online Playlists
    val onlinePlaylists: StateFlow<List<OnlinePlaylist>> = onlinePlaylistManager.playlists

    private var searchJob: Job? = null

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }
        searchJob =
            viewModelScope.launch(Dispatchers.IO) {
                _isSearching.value = true
                try {
                    val results = downloadRepository.search(query)
                    _searchResults.value = results
                } catch (e: Exception) {
                    L.e(e, "Error searching: $query")
                    _searchResults.value = emptyList()
                } finally {
                    _isSearching.value = false
                }
            }
    }

    fun download(song: OnlineSong) {
        downloadManager.enqueueDownload(song)
    }

    fun pauseDownload(webUrl: String) {
        downloadManager.pauseDownload(webUrl)
    }

    fun resumeDownload(webUrl: String) {
        downloadManager.resumeDownload(webUrl)
    }

    fun cancelDownload(webUrl: String) {
        downloadManager.cancelDownload(webUrl)
    }

    fun pauseAllDownloads() {
        downloadManager.pauseAll()
    }

    fun resumeAllDownloads() {
        downloadManager.resumeAll()
    }

    fun cancelAllDownloads() {
        downloadManager.cancelAll()
    }

    fun clearCompletedDownloads() {
        downloadManager.clearCompleted()
    }

    // --- Online Playlists Operations ---
    fun createOnlinePlaylist(name: String): OnlinePlaylist =
        onlinePlaylistManager.createPlaylist(name)

    fun deleteOnlinePlaylist(id: String) {
        onlinePlaylistManager.deletePlaylist(id)
    }

    fun renameOnlinePlaylist(id: String, newName: String) {
        onlinePlaylistManager.renamePlaylist(id, newName)
    }

    fun addSongToPlaylist(playlistId: String, song: OnlineSong): Boolean =
        onlinePlaylistManager.addSongToPlaylist(playlistId, song)

    fun removeSongFromPlaylist(playlistId: String, songWebUrl: String) {
        onlinePlaylistManager.removeSongFromPlaylist(playlistId, songWebUrl)
    }

    // --- Online Streaming Player Operations ---
    fun playOnline(song: OnlineSong) {
        onlinePlayerManager.play(song)
    }

    fun playQueue(songs: List<OnlineSong>, startIndex: Int = 0) {
        onlinePlayerManager.playQueue(songs, startIndex)
    }

    fun playNextOnline() {
        onlinePlayerManager.playNext()
    }

    fun playPrevOnline() {
        onlinePlayerManager.playPrevious()
    }

    fun togglePlayPauseOnline() {
        if (onlinePlayerManager.playerState.value.isPlaying) {
            onlinePlayerManager.pause()
        } else {
            onlinePlayerManager.resume()
        }
    }

    fun stopOnlinePlayback() {
        onlinePlayerManager.stop()
    }

    fun seekOnline(positionMs: Long) {
        onlinePlayerManager.seekTo(positionMs)
    }

    override fun onCleared() {
        super.onCleared()
        onlinePlayerManager.stop()
    }
}
