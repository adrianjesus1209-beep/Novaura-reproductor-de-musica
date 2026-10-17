/*
 * Copyright (c) 2026 Auxio Project
 * DownloadManager.kt is part of Auxio.
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
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber as L

@Singleton
class DownloadManager @Inject constructor(@ApplicationContext private val context: Context) {
    // List of all download items in the queue/manager
    private val _downloadItems = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloadItems: StateFlow<List<DownloadItem>> = _downloadItems.asStateFlow()

    // Map of song webUrl -> current download progress percentage (0..100)
    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress.asStateFlow()

    // Number of active downloads currently running in background
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    fun enqueueDownload(song: OnlineSong) {
        val existing = _downloadItems.value.find { it.song.webUrl == song.webUrl }
        if (existing != null && existing.status == DownloadStatus.DOWNLOADING) {
            L.d("Download already in progress for: ${song.title}")
            return
        }

        val newItem = DownloadItem(song = song, progress = 0, status = DownloadStatus.DOWNLOADING)
        _downloadItems.value =
            listOf(newItem) + _downloadItems.value.filter { it.song.webUrl != song.webUrl }

        _downloadProgress.value = _downloadProgress.value + (song.webUrl to 0)
        _activeCount.value = countActive()
        _events.tryEmit(DownloadEvent.Started(song.title))

        startServiceWithAction(DownloadService.ACTION_ENQUEUE) {
            putExtra(DownloadService.EXTRA_SONG, song)
        }
    }

    fun pauseDownload(webUrl: String) {
        val item = _downloadItems.value.find { it.song.webUrl == webUrl } ?: return
        if (item.status != DownloadStatus.DOWNLOADING) return

        _downloadItems.value =
            _downloadItems.value.map {
                if (it.song.webUrl == webUrl) it.copy(status = DownloadStatus.PAUSED) else it
            }
        _downloadProgress.value = _downloadProgress.value - webUrl
        _activeCount.value = countActive()

        startServiceWithAction(DownloadService.ACTION_PAUSE) {
            putExtra(DownloadService.EXTRA_URL, webUrl)
        }
    }

    fun resumeDownload(webUrl: String) {
        val item = _downloadItems.value.find { it.song.webUrl == webUrl } ?: return
        if (item.status != DownloadStatus.PAUSED && item.status != DownloadStatus.ERROR) return

        _downloadItems.value =
            _downloadItems.value.map {
                if (it.song.webUrl == webUrl) it.copy(status = DownloadStatus.DOWNLOADING) else it
            }
        _downloadProgress.value = _downloadProgress.value + (webUrl to item.progress)
        _activeCount.value = countActive()

        startServiceWithAction(DownloadService.ACTION_ENQUEUE) {
            putExtra(DownloadService.EXTRA_SONG, item.song)
        }
    }

    fun cancelDownload(webUrl: String) {
        val item = _downloadItems.value.find { it.song.webUrl == webUrl }
        _downloadItems.value = _downloadItems.value.filter { it.song.webUrl != webUrl }
        _downloadProgress.value = _downloadProgress.value - webUrl
        _activeCount.value = countActive()

        startServiceWithAction(DownloadService.ACTION_CANCEL) {
            putExtra(DownloadService.EXTRA_URL, webUrl)
        }
    }

    fun pauseAll() {
        val downloading = _downloadItems.value.filter { it.status == DownloadStatus.DOWNLOADING }
        for (item in downloading) {
            pauseDownload(item.song.webUrl)
        }
    }

    fun resumeAll() {
        val paused = _downloadItems.value.filter { it.status == DownloadStatus.PAUSED }
        for (item in paused) {
            resumeDownload(item.song.webUrl)
        }
    }

    fun cancelAll() {
        val items = _downloadItems.value.toList()
        for (item in items) {
            cancelDownload(item.song.webUrl)
        }
    }

    fun clearCompleted() {
        _downloadItems.value =
            _downloadItems.value.filter {
                it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PAUSED
            }
    }

    internal fun updateProgress(webUrl: String, progressPercent: Int) {
        _downloadProgress.value = _downloadProgress.value + (webUrl to progressPercent)
        _downloadItems.value =
            _downloadItems.value.map {
                if (it.song.webUrl == webUrl)
                    it.copy(progress = progressPercent, status = DownloadStatus.DOWNLOADING)
                else it
            }
    }

    internal fun onDownloadFinished(webUrl: String, success: Boolean, title: String) {
        _downloadProgress.value = _downloadProgress.value - webUrl
        _downloadItems.value =
            _downloadItems.value.map {
                if (it.song.webUrl == webUrl) {
                    it.copy(
                        progress = if (success) 100 else it.progress,
                        status = if (success) DownloadStatus.COMPLETED else DownloadStatus.ERROR,
                    )
                } else {
                    it
                }
            }
        _activeCount.value = countActive()

        if (success) {
            _events.tryEmit(DownloadEvent.Success(title))
        } else {
            _events.tryEmit(DownloadEvent.Error(title))
        }
    }

    private fun countActive(): Int =
        _downloadItems.value.count { it.status == DownloadStatus.DOWNLOADING }

    private fun startServiceWithAction(actionName: String, block: Intent.() -> Unit = {}) {
        val intent =
            Intent(context, DownloadService::class.java).apply {
                action = actionName
                block()
            }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            L.e(e, "Failed to send action $actionName to DownloadService")
        }
    }
}
