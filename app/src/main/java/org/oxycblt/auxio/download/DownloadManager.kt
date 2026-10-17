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
    // Map of song webUrl -> current download progress percentage (0..100)
    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress.asStateFlow()

    // Number of active downloads currently running in background
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    fun enqueueDownload(song: OnlineSong) {
        if (_downloadProgress.value.containsKey(song.webUrl)) {
            L.d("Download already in progress for: ${song.title}")
            return
        }

        // Set initial 0% progress immediately
        _downloadProgress.value = _downloadProgress.value + (song.webUrl to 0)
        _activeCount.value = _downloadProgress.value.size
        _events.tryEmit(DownloadEvent.Started(song.title))

        // Start background foreground service so it survives app minimization
        val intent =
            Intent(context, DownloadService::class.java).apply {
                action = DownloadService.ACTION_ENQUEUE
                putExtra(DownloadService.EXTRA_SONG, song)
            }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            L.e(e, "Failed to start DownloadService for ${song.title}")
            _downloadProgress.value = _downloadProgress.value - song.webUrl
            _activeCount.value = _downloadProgress.value.size
            _events.tryEmit(DownloadEvent.Error(song.title))
        }
    }

    internal fun updateProgress(webUrl: String, progressPercent: Int) {
        _downloadProgress.value = _downloadProgress.value + (webUrl to progressPercent)
    }

    internal fun onDownloadFinished(webUrl: String, success: Boolean, title: String) {
        _downloadProgress.value = _downloadProgress.value - webUrl
        _activeCount.value = _downloadProgress.value.size
        if (success) {
            _events.tryEmit(DownloadEvent.Success(title))
        } else {
            _events.tryEmit(DownloadEvent.Error(title))
        }
    }
}
