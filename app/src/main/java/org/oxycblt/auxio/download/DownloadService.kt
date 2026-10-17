/*
 * Copyright (c) 2026 Auxio Project
 * DownloadService.kt is part of Auxio.
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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.oxycblt.auxio.MainActivity
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicRepository
import timber.log.Timber as L

@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var downloadRepository: DownloadRepository
    @Inject lateinit var downloadManager: DownloadManager
    @Inject lateinit var musicRepository: MusicRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeSongs = ConcurrentHashMap<String, OnlineSong>()
    private val activeProgress = ConcurrentHashMap<String, Int>()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private lateinit var notificationManager: NotificationManager
    private var lastNotificationUpdateTime = 0L

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            if (activeSongs.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_ENQUEUE -> {
                @Suppress("DEPRECATION")
                val song =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getSerializableExtra(EXTRA_SONG, OnlineSong::class.java)
                    } else {
                        intent.getSerializableExtra(EXTRA_SONG) as? OnlineSong
                    }

                if (song != null) {
                    startDownloading(song)
                } else if (activeSongs.isEmpty()) {
                    stopSelf()
                }
            }
            ACTION_PAUSE -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                pauseDownload(url)
            }
            ACTION_CANCEL -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                cancelDownload(url)
            }
            ACTION_CANCEL_ALL -> {
                cancelAllDownloads()
            }
            else -> {
                if (activeSongs.isEmpty()) stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun pauseDownload(url: String) {
        val job = activeJobs.remove(url)
        job?.cancel()
        activeSongs.remove(url)
        activeProgress.remove(url)
        checkAllDownloadsFinished()
    }

    private fun cancelDownload(url: String) {
        val job = activeJobs.remove(url)
        job?.cancel()
        activeSongs.remove(url)
        activeProgress.remove(url)
        checkAllDownloadsFinished()
    }

    private fun cancelAllDownloads() {
        for ((_, job) in activeJobs) {
            job.cancel()
        }
        activeJobs.clear()
        activeSongs.clear()
        activeProgress.clear()
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun startDownloading(song: OnlineSong) {
        if (activeSongs.containsKey(song.webUrl)) {
            return
        }

        activeSongs[song.webUrl] = song
        activeProgress[song.webUrl] = 0

        // Start foreground immediately with current state
        val notification = buildOngoingNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val job = serviceScope.launch {
            L.d("Starting background download for: ${song.title}")
            val resultUri =
                downloadRepository.downloadSong(song) { percent ->
                    activeProgress[song.webUrl] = percent
                    downloadManager.updateProgress(song.webUrl, percent)
                    postNotificationUpdateThrottled()
                }

            val success = resultUri != null
            activeSongs.remove(song.webUrl)
            activeProgress.remove(song.webUrl)
            activeJobs.remove(song.webUrl)

            if (success) {
                // withCache = false forces a real storage scan so Novaura detects the new song and
                // its cover.jpg immediately
                musicRepository.requestIndex(false)
            }

            downloadManager.onDownloadFinished(song.webUrl, success, song.title)
            checkAllDownloadsFinished()
        }

        activeJobs[song.webUrl] = job
    }

    private fun checkAllDownloadsFinished() {
        if (activeSongs.isEmpty()) {
            L.d("All background downloads completed")
            showCompletedNotification()
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        } else {
            postNotificationUpdate(force = true)
        }
    }

    private fun postNotificationUpdateThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateTime > 400L) {
            lastNotificationUpdateTime = now
            postNotificationUpdate(force = false)
        }
    }

    private fun postNotificationUpdate(force: Boolean) {
        if (activeSongs.isEmpty()) return
        notificationManager.notify(NOTIFICATION_ID, buildOngoingNotification())
    }

    private fun buildOngoingNotification(): Notification {
        val totalCount = activeSongs.size
        val avgProgress =
            if (activeProgress.isNotEmpty()) {
                activeProgress.values.sum() / activeProgress.size
            } else {
                0
            }

        val title =
            if (totalCount == 1) {
                val song = activeSongs.values.firstOrNull()
                val songTitle = song?.title ?: "Música"
                getString(R.string.notif_downloading_single, songTitle)
            } else {
                getString(R.string.notif_downloading_multiple, totalCount)
            }

        val contentText =
            if (totalCount == 1) {
                "$avgProgress%"
            } else {
                val songNames = activeSongs.values.joinToString(", ") { it.title }
                "${songNames.take(40)}... • $avgProgress%"
            }

        val mainIntent =
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_24)
            .setContentTitle(title)
            .setContentText(contentText)
            .setProgress(100, avgProgress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showCompletedNotification() {
        val mainIntent =
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val completionNotif =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download_24)
                .setContentTitle(getString(R.string.notif_download_complete))
                .setContentText(getString(R.string.notif_download_all_complete))
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        notificationManager.notify(NOTIFICATION_ID, completionNotif)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notif_download_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    .apply {
                        description = getString(R.string.notif_download_channel_desc)
                        setShowBadge(false)
                    }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_ENQUEUE = "org.oxycblt.auxio.download.action.ENQUEUE"
        const val ACTION_PAUSE = "org.oxycblt.auxio.download.action.PAUSE"
        const val ACTION_CANCEL = "org.oxycblt.auxio.download.action.CANCEL"
        const val ACTION_CANCEL_ALL = "org.oxycblt.auxio.download.action.CANCEL_ALL"
        const val EXTRA_SONG = "extra_song"
        const val EXTRA_URL = "extra_url"
        const val NOTIFICATION_ID = 2026
        const val CHANNEL_ID = "novaura_downloads_channel"
    }
}
