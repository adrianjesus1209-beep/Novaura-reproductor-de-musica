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

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
        isRunning = true
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startForegroundSafely(buildPrimaryForegroundNotification())) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (intent == null) {
            if (activeSongs.isEmpty()) {
                exitForegroundAndStop()
            }
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_ENQUEUE -> {
                val song =
                    IntentCompat.getParcelableExtra(intent, EXTRA_SONG, OnlineSong::class.java)

                if (song != null) {
                    startDownloading(song)
                } else if (activeSongs.isEmpty()) {
                    exitForegroundAndStop()
                }
            }
            ACTION_PAUSE -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                if (url.isNotEmpty()) {
                    pauseSingleDownload(url)
                } else {
                    pauseAllDownloads()
                }
            }
            ACTION_PAUSE_ALL -> {
                pauseAllDownloads()
            }
            ACTION_RESUME -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                if (url.isNotEmpty()) {
                    downloadManager.resumeDownload(url)
                }
                if (activeSongs.isEmpty() && downloadManager.activeCount.value == 0) {
                    checkAllDownloadsFinished()
                }
            }
            ACTION_RESUME_ALL -> {
                downloadManager.resumeAll()
                if (activeSongs.isEmpty() && downloadManager.activeCount.value == 0) {
                    checkAllDownloadsFinished()
                }
            }
            ACTION_CANCEL -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                if (url.isNotEmpty()) {
                    cancelSingleDownload(url)
                } else {
                    cancelAllDownloads()
                }
            }
            ACTION_CANCEL_ALL -> {
                cancelAllDownloads()
            }
            else -> {
                if (activeSongs.isEmpty()) {
                    exitForegroundAndStop()
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        L.w("DownloadService foreground timeout reached (startId=$startId, fgsType=$fgsType)")
        downloadManager.pauseAllFromService()
        pauseAllDownloads()
    }

    private fun startForegroundSafely(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: SecurityException) {
            L.w(e, "Unable to promote DownloadService to foreground")
            false
        } catch (e: RuntimeException) {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
            ) {
                L.w(e, "DownloadService foreground start not allowed from background")
                false
            } else {
                throw e
            }
        }
    }

    private fun exitForegroundAndStop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notifySafely(id: Int, notification: Notification) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            notificationManager.notify(id, notification)
        } catch (e: SecurityException) {
            L.w(e, "Unable to post download notification id=$id")
        }
    }

    private fun pauseSingleDownload(url: String) {
        activeJobs.remove(url)?.cancel()
        activeSongs.remove(url)
        activeProgress.remove(url)
        notificationManager.cancel(getSongNotificationId(url))
        downloadManager.setPausedFromService(url)
        checkAllDownloadsFinished()
    }

    private fun pauseAllDownloads() {
        val urls = activeJobs.keys.toList()
        for (url in urls) {
            activeJobs.remove(url)?.cancel()
            activeSongs.remove(url)
            activeProgress.remove(url)
            notificationManager.cancel(getSongNotificationId(url))
            downloadManager.setPausedFromService(url)
        }
        checkAllDownloadsFinished()
    }

    private fun cancelSingleDownload(url: String) {
        activeJobs.remove(url)?.cancel()
        activeSongs.remove(url)
        activeProgress.remove(url)
        notificationManager.cancel(getSongNotificationId(url))
        downloadRepository.cleanupTempFile(url)
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
        notificationManager.cancel(PAUSED_SUMMARY_NOTIFICATION_ID)
        downloadRepository.cleanupAllTempFiles()
        downloadManager.clearAllFromService()
        exitForegroundAndStop()
    }

    private fun startDownloading(song: OnlineSong) {
        if (activeSongs.containsKey(song.webUrl)) {
            return
        }

        notificationManager.cancel(PAUSED_SUMMARY_NOTIFICATION_ID)
        activeSongs[song.webUrl] = song
        activeProgress[song.webUrl] = 0

        // Start or update foreground notification
        if (!startForegroundSafely(buildPrimaryForegroundNotification())) {
            activeSongs.remove(song.webUrl)
            activeProgress.remove(song.webUrl)
            downloadManager.setPausedFromService(song.webUrl)
            exitForegroundAndStop()
            return
        }

        postNotificationUpdate(force = true)

        val job =
            serviceScope.launch(start = CoroutineStart.LAZY) {
                L.d("Starting background download for: ${song.title}")
                var completedNormally = false
                try {
                    val resultUri =
                        downloadRepository.downloadSong(song) { percent ->
                            activeProgress[song.webUrl] = percent
                            downloadManager.updateProgress(song.webUrl, percent)
                            postNotificationUpdateThrottled()
                        }

                    if (!isActive || !activeJobs.containsKey(song.webUrl)) {
                        return@launch
                    }

                    val success = resultUri != null
                    activeSongs.remove(song.webUrl)
                    activeProgress.remove(song.webUrl)
                    activeJobs.remove(song.webUrl)
                    notificationManager.cancel(getSongNotificationId(song.webUrl))

                    if (success) {
                        showIndividualCompletedNotification(song)
                        musicRepository.requestIndex(
                            withCache = true,
                            forceStorageScan = true,
                            silent = true,
                        )
                    }

                    completedNormally = true
                    downloadManager.onDownloadFinished(song.webUrl, success, song.title)
                    checkAllDownloadsFinished()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.e(e, "Unexpected error while downloading ${song.title}")
                    if (!completedNormally && activeJobs.remove(song.webUrl) != null) {
                        activeSongs.remove(song.webUrl)
                        activeProgress.remove(song.webUrl)
                        notificationManager.cancel(getSongNotificationId(song.webUrl))
                        downloadManager.onDownloadFinished(song.webUrl, false, song.title)
                        checkAllDownloadsFinished()
                    }
                }
            }

        activeJobs[song.webUrl] = job
        job.start()
    }

    private fun checkAllDownloadsFinished() {
        if (activeSongs.isEmpty()) {
            val hasPaused =
                downloadManager.downloadItems.value.any { it.status == DownloadStatus.PAUSED }
            // Never keep an idle DATA_SYNC foreground service alive across pauses.
            exitForegroundAndStop()
            if (hasPaused) {
                notifySafely(PAUSED_SUMMARY_NOTIFICATION_ID, buildSummaryNotification())
            } else {
                notificationManager.cancel(PAUSED_SUMMARY_NOTIFICATION_ID)
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
        if (totalCount == 0) return

        if (totalCount == 1) {
            val song = activeSongs.values.first()
            val progress = activeProgress[song.webUrl] ?: 0
            val notif = buildSongNotification(song, progress, isForeground = true)
            notifySafely(NOTIFICATION_ID, notif)
            notificationManager.cancel(getSongNotificationId(song.webUrl))
        } else {
            val summaryNotif = buildSummaryNotification()
            notifySafely(NOTIFICATION_ID, summaryNotif)

            for ((url, song) in activeSongs) {
                val progress = activeProgress[url] ?: 0
                val songNotif = buildSongNotification(song, progress, isForeground = false)
                notifySafely(getSongNotificationId(url), songNotif)
            }
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
            Intent(this, DownloadNotificationReceiver::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_URL, song.webUrl)
            }
        val pausePendingIntent =
            PendingIntent.getBroadcast(
                this,
                notifId * 2,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val cancelIntent =
            Intent(this, DownloadNotificationReceiver::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_URL, song.webUrl)
            }
        val cancelPendingIntent =
            PendingIntent.getBroadcast(
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

        val pauseOrResumePendingIntent =
            if (isPausedState) {
                val resumeIntent =
                    Intent(this, DownloadService::class.java).apply { action = ACTION_RESUME_ALL }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PendingIntent.getForegroundService(
                        this,
                        1,
                        resumeIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                } else {
                    PendingIntent.getService(
                        this,
                        1,
                        resumeIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                }
            } else {
                val pauseIntent =
                    Intent(this, DownloadNotificationReceiver::class.java).apply {
                        action = ACTION_PAUSE_ALL
                    }
                PendingIntent.getBroadcast(
                    this,
                    1,
                    pauseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

        val cancelIntent =
            Intent(this, DownloadNotificationReceiver::class.java).apply {
                action = ACTION_CANCEL_ALL
            }
        val cancelPendingIntent =
            PendingIntent.getBroadcast(
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
                .setOngoing(!isPausedState)
                .setAutoCancel(isPausedState)
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

        notifySafely(notifId, completionNotif)
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
        isRunning = false
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile
        var isRunning = false
            private set

        const val PAUSED_SUMMARY_NOTIFICATION_ID = 2027
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
