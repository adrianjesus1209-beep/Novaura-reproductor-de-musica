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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.oxycblt.auxio.music.MusicRepository
import timber.log.Timber as L

sealed interface DownloadEvent {
    data class Success(val title: String) : DownloadEvent

    data class Error(val title: String) : DownloadEvent
}

@HiltViewModel
class DownloadViewModel
@Inject
constructor(
    private val downloadRepository: DownloadRepository,
    private val musicRepository: MusicRepository,
) : ViewModel() {

    private val _searchResults = MutableStateFlow<List<OnlineSong>>(emptyList())
    val searchResults: StateFlow<List<OnlineSong>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // Map of online song webUrl -> download progress percentage (0..100)
    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress.asStateFlow()

    private val _events = MutableSharedFlow<DownloadEvent>()
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    fun search(query: String) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
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
        if (_downloadProgress.value.containsKey(song.webUrl)) {
            // Already downloading
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _downloadProgress.value = _downloadProgress.value + (song.webUrl to 0)
            try {
                val resultUri =
                    downloadRepository.downloadSong(song) { percent ->
                        _downloadProgress.value = _downloadProgress.value + (song.webUrl to percent)
                    }

                if (resultUri != null) {
                    _events.emit(DownloadEvent.Success(song.title))
                    // Request library reindex so the new song shows up immediately in the player
                    musicRepository.requestIndex(true)
                } else {
                    _events.emit(DownloadEvent.Error(song.title))
                }
            } catch (e: Exception) {
                L.e(e, "Error downloading ${song.title}")
                _events.emit(DownloadEvent.Error(song.title))
            } finally {
                _downloadProgress.value = _downloadProgress.value - song.webUrl
            }
        }
    }
}
