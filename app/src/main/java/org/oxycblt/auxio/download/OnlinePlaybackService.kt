/*
 * Copyright (c) 2026 Auxio Project
 * OnlinePlaybackService.kt is part of Auxio.
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
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.oxycblt.auxio.BuildConfig
import org.oxycblt.auxio.MainActivity
import org.oxycblt.auxio.R
import timber.log.Timber as L

/**
 * Foreground mediaPlayback service that hosts [MediaSessionCompat] and playback notifications for
 * [OnlinePlayerManager], ensuring reliable background streaming across Android 7-17.
 */
@AndroidEntryPoint
class OnlinePlaybackService : Service() {

    @Inject lateinit var onlinePlayerManager: OnlinePlayerManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var artworkJob: Job? = null

    private lateinit var notificationManager: NotificationManager
    private lateinit var mediaSession: MediaSessionCompat

    private var isForeground = false
    private var lastMetadataUrl: String? = null
    private var lastMetadataDurationMs: Long = -1L
    private var lastArtworkUrl: String? = null
    private var currentArtworkBitmap: Bitmap? = null

    private var lastNotifSongUrl: String? = null
    private var lastNotifPlaying: Boolean? = null
    private var lastNotifLoading: Boolean? = null
    private var lastNotifHasPrev: Boolean? = null
    private var lastNotifHasNext: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()

        mediaSession =
            MediaSessionCompat(this, "NovauraOnlineMediaSession").apply {
                setCallback(
                    object : MediaSessionCompat.Callback() {
                        override fun onPlay() {
                            onlinePlayerManager.resume()
                        }

                        override fun onPause() {
                            onlinePlayerManager.pause()
                        }

                        override fun onSkipToNext() {
                            onlinePlayerManager.playNext()
                        }

                        override fun onSkipToPrevious() {
                            onlinePlayerManager.playPrevious()
                        }

                        override fun onSeekTo(pos: Long) {
                            onlinePlayerManager.seekTo(pos)
                        }

                        override fun onStop() {
                            onlinePlayerManager.stop()
                            stopForegroundAndSelf()
                        }
                    }
                )
            }

        stateJob = serviceScope.launch {
            onlinePlayerManager.playerState.collect { state -> handleStateChanged(state) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = onlinePlayerManager.playerState.value
        val action = intent?.action

        // Always fulfill startForegroundService contract immediately before branching.
        if (action == ACTION_STOP) {
            onlinePlayerManager.stop()
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        if (!startForegroundSafely(buildNotification(state))) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_PLAY -> onlinePlayerManager.resume()
            ACTION_PAUSE -> onlinePlayerManager.pause()
            ACTION_PLAY_PAUSE -> {
                if (state.isPlaying) {
                    onlinePlayerManager.pause()
                } else {
                    onlinePlayerManager.resume()
                }
            }
            ACTION_NEXT -> onlinePlayerManager.playNext()
            ACTION_PREV -> onlinePlayerManager.playPrevious()
        }

        val updatedState = onlinePlayerManager.playerState.value
        if (updatedState.song == null && !updatedState.isLoading) {
            stopForegroundAndSelf()
        } else {
            handleStateChanged(updatedState)
        }

        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        L.w("OnlinePlaybackService timeout (startId=$startId, fgsType=$fgsType)")
        onlinePlayerManager.pause()
        stopForegroundAndSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val state = onlinePlayerManager.playerState.value
        if (!state.isPlaying && !state.isLoading) {
            onlinePlayerManager.stop()
            stopForegroundAndSelf()
        }
    }

    private fun handleStateChanged(state: OnlinePlayerState) {
        val song = state.song
        if (song == null) {
            mediaSession.isActive = false
            updateMediaSessionPlaybackState(state)
            stopForegroundAndSelf()
            return
        }

        mediaSession.isActive = true
        updateMediaSessionMetadata(song, state.durationMs)
        updateMediaSessionPlaybackState(state)

        val shouldPostNotif =
            lastNotifSongUrl != song.webUrl ||
                lastNotifPlaying != state.isPlaying ||
                lastNotifLoading != state.isLoading ||
                lastNotifHasPrev != state.hasPrevious ||
                lastNotifHasNext != state.hasNext

        val activePlayback = state.isPlaying || state.isLoading
        if (activePlayback) {
            if (!isForeground || shouldPostNotif) {
                startForegroundSafely(buildNotification(state))
            }
        } else {
            if (isForeground) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                isForeground = false
            }
            if (shouldPostNotif) {
                notifySafely(buildNotification(state))
            }
        }

        if (shouldPostNotif) {
            lastNotifSongUrl = song.webUrl
            lastNotifPlaying = state.isPlaying
            lastNotifLoading = state.isLoading
            lastNotifHasPrev = state.hasPrevious
            lastNotifHasNext = state.hasNext
        }
    }

    private fun updateMediaSessionMetadata(song: OnlineSong, durationMs: Long) {
        if (lastArtworkUrl != song.thumbnailUrl) {
            lastArtworkUrl = song.thumbnailUrl
            currentArtworkBitmap = null
            artworkJob?.cancel()
            val url = song.thumbnailUrl
            if (!url.isNullOrBlank()) {
                artworkJob =
                    serviceScope.launch(Dispatchers.IO) {
                        val bmp = ThumbnailLoader.fetchBitmapSync(url, maxSizePx = 512)
                        serviceScope.launch(Dispatchers.Main.immediate) {
                            if (lastArtworkUrl == url && bmp != null && !bmp.isRecycled) {
                                currentArtworkBitmap = bmp
                                pushMetadataToSession(song, durationMs, bmp)
                                onlinePlayerManager.playerState.value
                                    .takeIf { it.song?.webUrl == song.webUrl }
                                    ?.let { st ->
                                        if (st.isPlaying || st.isLoading) {
                                            startForegroundSafely(buildNotification(st))
                                        } else {
                                            notifySafely(buildNotification(st))
                                        }
                                    }
                            }
                        }
                    }
            }
        }

        if (lastMetadataUrl != song.webUrl || lastMetadataDurationMs != durationMs) {
            lastMetadataUrl = song.webUrl
            lastMetadataDurationMs = durationMs
            pushMetadataToSession(song, durationMs, currentArtworkBitmap)
        }
    }

    private fun pushMetadataToSession(song: OnlineSong, durationMs: Long, artwork: Bitmap?) {
        val builder =
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putLong(
                    MediaMetadataCompat.METADATA_KEY_DURATION,
                    durationMs.coerceAtLeast(song.durationSeconds * 1000L),
                )
        if (artwork != null && !artwork.isRecycled) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
        }
        mediaSession.setMetadata(builder.build())
    }

    private fun updateMediaSessionPlaybackState(state: OnlinePlayerState) {
        val pbState =
            when {
                state.song == null -> PlaybackStateCompat.STATE_STOPPED
                state.isLoading -> PlaybackStateCompat.STATE_BUFFERING
                state.isPlaying -> PlaybackStateCompat.STATE_PLAYING
                else -> PlaybackStateCompat.STATE_PAUSED
            }
        val speed = if (state.isPlaying && !state.isLoading) 1.0f else 0.0f
        val actions =
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO or
                (if (state.hasNext || state.isRepeat) PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                else 0L) or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(pbState, state.positionMs, speed)
                .build()
        )
    }

    private fun buildNotification(state: OnlinePlayerState): Notification {
        val song = state.song
        val openAppIntent =
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val playPauseAction =
            if (state.isPlaying) {
                NotificationCompat.Action.Builder(
                        R.drawable.ic_pause_24,
                        getString(R.string.lbl_pause),
                        serviceActionPendingIntent(ACTION_PAUSE, 1, foreground = false),
                    )
                    .build()
            } else {
                NotificationCompat.Action.Builder(
                        R.drawable.ic_play_24,
                        getString(R.string.lbl_resume),
                        serviceActionPendingIntent(ACTION_PLAY, 1, foreground = true),
                    )
                    .build()
            }

        val prevAction =
            NotificationCompat.Action.Builder(
                    R.drawable.ic_skip_prev_24,
                    getString(R.string.desc_skip_prev),
                    serviceActionPendingIntent(ACTION_PREV, 2, foreground = false),
                )
                .build()

        val nextAction =
            NotificationCompat.Action.Builder(
                    R.drawable.ic_skip_next_24,
                    getString(R.string.desc_skip_next),
                    serviceActionPendingIntent(ACTION_NEXT, 3, foreground = false),
                )
                .build()

        val stopAction =
            NotificationCompat.Action.Builder(
                    R.drawable.ic_close_24,
                    getString(R.string.lbl_close),
                    serviceActionPendingIntent(ACTION_STOP, 4, foreground = false),
                )
                .build()

        val builder =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_novaura_stat_24)
                .setContentTitle(song?.title ?: getString(R.string.lbl_playing_online))
                .setContentText(
                    if (state.isLoading) getString(R.string.lbl_streaming)
                    else song?.artist ?: getString(R.string.lbl_playing_online)
                )
                .setContentIntent(contentIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setOngoing(state.isPlaying || state.isLoading)
                .setDeleteIntent(serviceActionPendingIntent(ACTION_STOP, 5, foreground = false))
                .addAction(prevAction)
                .addAction(playPauseAction)
                .addAction(nextAction)
                .addAction(stopAction)
                .setStyle(
                    MediaStyle()
                        .setMediaSession(mediaSession.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )

        currentArtworkBitmap?.takeIf { !it.isRecycled }?.let { bmp -> builder.setLargeIcon(bmp) }

        return builder.build()
    }

    private fun serviceActionPendingIntent(
        actionName: String,
        requestCode: Int,
        foreground: Boolean,
    ): PendingIntent {
        val intent = Intent(this, OnlinePlaybackService::class.java).apply { action = actionName }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, requestCode, intent, flags)
        } else {
            PendingIntent.getService(this, requestCode, intent, flags)
        }
    }

    private fun startForegroundSafely(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
            true
        } catch (e: SecurityException) {
            L.w(e, "Unable to promote OnlinePlaybackService to foreground")
            false
        } catch (e: RuntimeException) {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
            ) {
                L.w(e, "OnlinePlaybackService foreground start not allowed")
                false
            } else {
                throw e
            }
        }
    }

    private fun notifySafely(notification: Notification) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            L.w(e, "Unable to post online playback notification")
        }
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
        notificationManager.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notif_online_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    .apply {
                        description = getString(R.string.notif_online_channel_desc)
                        setShowBadge(false)
                    }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isRunning = false
        isForeground = false
        stateJob?.cancel()
        artworkJob?.cancel()
        mediaSession.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile
        var isRunning = false
            private set

        const val NOTIFICATION_ID = 2028
        const val CHANNEL_ID = "novaura_online_playback_channel"

        const val ACTION_START = BuildConfig.APPLICATION_ID + ".online.START"
        const val ACTION_PLAY = BuildConfig.APPLICATION_ID + ".online.PLAY"
        const val ACTION_PAUSE = BuildConfig.APPLICATION_ID + ".online.PAUSE"
        const val ACTION_PLAY_PAUSE = BuildConfig.APPLICATION_ID + ".online.PLAY_PAUSE"
        const val ACTION_NEXT = BuildConfig.APPLICATION_ID + ".online.NEXT"
        const val ACTION_PREV = BuildConfig.APPLICATION_ID + ".online.PREV"
        const val ACTION_STOP = BuildConfig.APPLICATION_ID + ".online.STOP"
    }
}
