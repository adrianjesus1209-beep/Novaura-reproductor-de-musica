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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
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
        startForegroundCompat(buildPrimaryForegroundNotification())

        if (intent == null) {
            if (activeSongs.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
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
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_PAUSE -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                if (url.isNotEmpty()) {
                    pauseSingleDownload(url)
                } else {
                    downloadManager.pauseAll()
                    checkAllDownloadsFinished()
                }
            }
            ACTION_PAUSE_ALL -> {
                downloadManager.pauseAll()
                checkAllDownloadsFinished()
            }
            ACTION_RESUME -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                if (url.isNotEmpty()) {
                    downloadManager.resumeDownload(url)
                }
                if (activeSongs.isEmpty()) {
                    checkAllDownloadsFinished()
                }
            }
            ACTION_RESUME_ALL -> {
                downloadManager.resumeAll()
                if (activeSongs.isEmpty()) {
                    checkAllDownloadsFinished()
                }
            }
            ACTION_CANCEL -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                if (url.isNotEmpty()) {
                    cancelSingleDownload(url)
                } else {
                    downloadManager.cancelAll()
                    cancelAllDownloads()
                }
            }
            ACTION_CANCEL_ALL -> {
                downloadManager.cancelAll()
                cancelAllDownloads()
            }
            else -> {
                if (activeSongs.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun pauseSingleDownload(url: String) {
        val job = activeJobs.remove(url)
        job?.cancel()
        activeSongs.remove(url)
        activeProgress.remove(url)
        notificationManager.cancel(getSongNotificationId(url))
        downloadManager.setPausedFromService(url)
        checkAllDownloadsFinished()
    }

    private fun cancelSingleDownload(url: String) {
        val job = activeJobs.remove(url)
        job?.cancel()
        activeSongs.remove(url)
        activeProgress.remove(url)
        notificationManager.cancel(getSongNotificationId(url))
        downloadManager.setCancelledFromService(url)
        checkAllDownloadsFinished()
    }

    private fun cancelAllDownloads() {
        for ((url, job) in activeJobs) {
            job.cancel()
            notificationManager.cancel(getSongNotificationId(url))
        }
        activeJobs.clear()
        activeSongs.clear()
        activeProgress.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startDownloading(song: OnlineSong) {
        if (activeSongs.containsKey(song.webUrl)) {
            return
        }

        activeSongs[song.webUrl] = song
        activeProgress[song.webUrl] = 0

        // Start or update foreground notification
        startForegroundCompat(buildPrimaryForegroundNotification())

        postNotificationUpdate(force = true)

        val job = serviceScope.launch {
            L.d("Starting background download for: ${song.title}")
            val resultUri =
                try {
                    downloadRepository.downloadSong(song) { percent ->
                        activeProgress[song.webUrl] = percent
                        downloadManager.updateProgress(song.webUrl, percent)
                        postNotificationUpdateThrottled()
                    }
                } catch (e: CancellationException) {
                    throw e
                }

            if (!isActive || !activeJobs.containsKey(song.webUrl)) {
                return@launch
            }

            val success = resultUri != null
            activeSongs.remove(song.webUrl)
            activeProgress.remove(song.webUrl)
            activeJobs.remove(song.webUrl)

            // Cancel the active progress notification
            notificationManager.cancel(getSongNotificationId(song.webUrl))

            if (success) {
                // Show individual completed notification with checkmark
                showIndividualCompletedNotification(song)

                // Silent, incremental library index to detect new song and cover art without UI
                // flicker
                musicRepository.requestIndex(
                    withCache = true,
                    forceStorageScan = true,
                    silent = true,
                )
            }

            downloadManager.onDownloadFinished(song.webUrl, success, song.title)
            checkAllDownloadsFinished()
        }

        activeJobs[song.webUrl] = job
    }

    private fun checkAllDownloadsFinished() {
        if (activeSongs.isEmpty()) {
            val hasPaused =
                downloadManager.downloadItems.value.any { it.status == DownloadStatus.PAUSED }
            if (hasPaused) {
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildPrimaryForegroundNotification(),
                )
            } else {
                L.d("All background downloads completed")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
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
        val totalCount = activeSongs.size
        val hasPaused =
            downloadManager.downloadItems.value.any { it.status == DownloadStatus.PAUSED }

        if (totalCount == 0 && !hasPaused) return

        if (totalCount == 1) {
            // Exactly 1 song downloading: foreground notification is the song itself
            val song = activeSongs.values.first()
            val progress = activeProgress[song.webUrl] ?: 0
            val notif = buildSongNotification(song, progress, isForeground = true)
            notificationManager.notify(NOTIFICATION_ID, notif)
            notificationManager.cancel(getSongNotificationId(song.webUrl))
        } else if (totalCount > 1) {
            // Multiple songs: foreground notification is the group summary
            val summaryNotif = buildSummaryNotification()
            notificationManager.notify(NOTIFICATION_ID, summaryNotif)

            // And every active song has its own individual notification with Pause & Cancel
            for ((url, song) in activeSongs) {
                val progress = activeProgress[url] ?: 0
                val songNotif = buildSongNotification(song, progress, isForeground = false)
                notificationManager.notify(getSongNotificationId(url), songNotif)
            }
        } else if (hasPaused) {
            notificationManager.notify(
                NOTIFICATION_ID,
                buildPrimaryForegroundNotification(),
            )
        }
    }

    private fun buildPrimaryForegroundNotification(): Notification {
        return if (activeSongs.size == 1) {
            val song = activeSongs.values.first()
            val progress = activeProgress[song.webUrl] ?: 0
            buildSongNotification(song, progress, isForeground = true)
        } else {
            buildSummaryNotification()
        }
    }

    private fun buildSongNotification(
        song: OnlineSong,
        progress: Int,
        isForeground: Boolean,
    ): Notification {
        val notifId = getSongNotificationId(song.webUrl)

        val mainIntent =
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                notifId,
                mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val pauseIntent =
            Intent(this, DownloadService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_URL, song.webUrl)
            }
        val pausePendingIntent =
            PendingIntent.getService(
                this,
                notifId * 2,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val cancelIntent =
            Intent(this, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_URL, song.webUrl)
            }
        val cancelPendingIntent =
            PendingIntent.getService(
                this,
                notifId * 2 + 1,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val builder =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download_24)
                .setContentTitle(song.title)
                .setContentText("${song.artist} • $progress%")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .setProgress(100, progress, false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(
                    R.drawable.ic_pause_24,
                    getString(R.string.lbl_pause),
                    pausePendingIntent,
                )
                .addAction(
                    R.drawable.ic_close_24,
                    getString(R.string.lbl_cancel),
                    cancelPendingIntent,
                )

        if (!isForeground) {
            builder.setGroup(GROUP_KEY_DOWNLOADS)
        }

        return builder.build()
    }

    private fun buildSummaryNotification(): Notification {
        val totalCount = activeSongs.size
        val avgProgress =
            if (activeProgress.isNotEmpty()) {
                activeProgress.values.sum() / activeProgress.size
            } else {
                0
            }

        val hasPaused =
            downloadManager.downloadItems.value.any { it.status == DownloadStatus.PAUSED }
        val isPausedState = totalCount == 0 && hasPaused

        val title =
            if (isPausedState) {
                getString(R.string.notif_downloads_paused)
            } else {
                getString(R.string.notif_downloading_multiple, totalCount)
            }

        val contentText =
            if (isPausedState) {
                val pausedCount =
                    downloadManager.downloadItems.value.count { it.status == DownloadStatus.PAUSED }
                getString(R.string.lbl_status_paused) +
                    " • " +
                    getString(R.string.notif_downloading_multiple, pausedCount)
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

        val pauseOrResumeIntent =
            Intent(this, DownloadService::class.java).apply {
                action = if (isPausedState) ACTION_RESUME_ALL else ACTION_PAUSE_ALL
            }
        val pauseOrResumePendingIntent =
            PendingIntent.getService(
                this,
                1,
                pauseOrResumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val cancelIntent =
            Intent(this, DownloadService::class.java).apply {
                action = ACTION_CANCEL_ALL
            }
        val cancelPendingIntent =
            PendingIntent.getService(
                this,
                2,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val builder =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download_24)
                .setContentTitle(title)
                .setContentText(contentText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setGroup(GROUP_KEY_DOWNLOADS)
                .setGroupSummary(true)

        if (!isPausedState) {
            builder.setProgress(100, avgProgress, false)
            builder.addAction(
                R.drawable.ic_pause_24,
                getString(R.string.lbl_pause),
                pauseOrResumePendingIntent,
            )
        } else {
            builder.setProgress(0, 0, false)
            builder.addAction(
                R.drawable.ic_play_24,
                getString(R.string.lbl_resume),
                pauseOrResumePendingIntent,
            )
        }

        builder.addAction(
            R.drawable.ic_close_24,
            getString(R.string.lbl_cancel),
            cancelPendingIntent,
        )

        return builder.build()
    }

    private fun showIndividualCompletedNotification(song: OnlineSong) {
        val notifId = getCompletedNotificationId(song.webUrl)

        val mainIntent =
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                notifId,
                mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val title = getString(R.string.notif_download_single_complete_title, song.title)

        val completionNotif =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download_24)
                .setContentTitle(title)
                .setContentText(song.artist)
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        notificationManager.notify(notifId, completionNotif)
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
        const val ACTION_PAUSE_ALL = "org.oxycblt.auxio.download.action.PAUSE_ALL"
        const val ACTION_RESUME = "org.oxycblt.auxio.download.action.RESUME"
        const val ACTION_RESUME_ALL = "org.oxycblt.auxio.download.action.RESUME_ALL"
        const val ACTION_CANCEL = "org.oxycblt.auxio.download.action.CANCEL"
        const val ACTION_CANCEL_ALL = "org.oxycblt.auxio.download.action.CANCEL_ALL"
        const val EXTRA_SONG = "extra_song"
        const val EXTRA_URL = "extra_url"
        const val NOTIFICATION_ID = 2026
        const val CHANNEL_ID = "novaura_downloads_channel"
        const val GROUP_KEY_DOWNLOADS = "org.oxycblt.auxio.DOWNLOADS_GROUP"

        fun getSongNotificationId(url: String): Int {
            val hash = url.hashCode() and 0x7FFFFFFF
            return 10000 + (hash % 900000)
        }

        fun getCompletedNotificationId(url: String): Int {
            val hash = url.hashCode() and 0x7FFFFFFF
            return 1000000 + (hash % 900000)
        }
    }
}
