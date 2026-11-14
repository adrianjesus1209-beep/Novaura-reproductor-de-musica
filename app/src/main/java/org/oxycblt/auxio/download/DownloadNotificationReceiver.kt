/*
 * Copyright (c) 2026 Auxio Project
 * DownloadNotificationReceiver.kt is part of Auxio.
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

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Handles notification button broadcasts safely without forcing a background service start. */
@AndroidEntryPoint
class DownloadNotificationReceiver : BroadcastReceiver() {
    @Inject lateinit var downloadManager: DownloadManager

    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra(DownloadService.EXTRA_URL).orEmpty()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        when (intent.action) {
            DownloadService.ACTION_PAUSE -> {
                if (url.isNotEmpty()) downloadManager.pauseDownload(url)
            }
            DownloadService.ACTION_PAUSE_ALL -> downloadManager.pauseAll()
            DownloadService.ACTION_CANCEL -> {
                if (url.isNotEmpty()) {
                    downloadManager.cancelDownload(url)
                    notificationManager?.cancel(DownloadService.getSongNotificationId(url))
                }
            }
            DownloadService.ACTION_CANCEL_ALL -> {
                downloadManager.cancelAll()
                notificationManager?.cancel(DownloadService.NOTIFICATION_ID)
            }
        }
    }
}
