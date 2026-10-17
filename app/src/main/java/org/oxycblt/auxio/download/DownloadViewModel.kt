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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber as L

sealed interface DownloadEvent {
    data class Started(val title: String) : DownloadEvent

    data class Success(val title: String) : DownloadEvent

    data class Error(val title: String) : DownloadEvent
}

@HiltViewModel
class DownloadViewModel
@Inject
constructor(
    private val downloadRepository: DownloadRepository,
    private val downloadManager: DownloadManager,
    private val onlinePlayerManager: OnlinePlayerManager,
) : ViewModel() {

    private val _searchResults = MutableStateFlow<List<OnlineSong>>(emptyList())
    val searchResults: StateFlow<List<OnlineSong>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // Download state delegated to background DownloadManager
    val downloadProgress: StateFlow<Map<String, Int>> = downloadManager.downloadProgress
    val activeCount: StateFlow<Int> = downloadManager.activeCount
    val events: SharedFlow<DownloadEvent> = downloadManager.events

    // Online audio streaming player state
    val playerState: StateFlow<OnlinePlayerState> = onlinePlayerManager.playerState
    val playerError: SharedFlow<String> = onlinePlayerManager.playerError

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

    fun playOnline(song: OnlineSong) {
        onlinePlayerManager.play(song)
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
