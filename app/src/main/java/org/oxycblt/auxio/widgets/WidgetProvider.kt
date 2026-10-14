/*
 * Copyright (c) 2021 Auxio Project
 * WidgetProvider.kt is part of Auxio.
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
 
package org.oxycblt.auxio.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import org.oxycblt.auxio.AuxioService
import org.oxycblt.auxio.BuildConfig
import org.oxycblt.auxio.IntegerTable
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.service.PlaybackActions
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.ui.UISettingsImpl
import org.oxycblt.auxio.util.newMainPendingIntent
import timber.log.Timber as L

/**
 * The [AppWidgetProvider] for the "Now Playing" widget. This widget shows the current playback
 * state alongside actions to control it.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class WidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_PLAY_PAUSE -> {
                L.d("Widget play/pause action received")
                if (AuxioService.isForeground) {
                    context.sendBroadcast(
                        Intent(PlaybackActions.ACTION_PLAY_PAUSE)
                            .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                    )
                } else {
                    val serviceIntent =
                        Intent(context, AuxioService::class.java).apply {
                            putExtra(
                                AuxioService.INTENT_KEY_START_ID,
                                IntegerTable.START_ID_WIDGET_PLAY,
                            )
                        }
                    ContextCompat.startForegroundService(context, serviceIntent)
                }
            }
            ACTION_WIDGET_SKIP_NEXT -> {
                L.d("Widget skip next action received")
                if (AuxioService.isForeground) {
                    context.sendBroadcast(
                        Intent(PlaybackActions.ACTION_SKIP_NEXT)
                            .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                    )
                } else {
                    val serviceIntent =
                        Intent(context, AuxioService::class.java).apply {
                            putExtra(
                                AuxioService.INTENT_KEY_START_ID,
                                IntegerTable.START_ID_WIDGET_PLAY,
                            )
                        }
                    ContextCompat.startForegroundService(context, serviceIntent)
                }
            }
            ACTION_WIDGET_SKIP_PREV -> {
                L.d("Widget skip prev action received")
                if (AuxioService.isForeground) {
                    context.sendBroadcast(
                        Intent(PlaybackActions.ACTION_SKIP_PREV)
                            .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                    )
                }
            }
            ACTION_WIDGET_SHUFFLE -> {
                L.d("Widget shuffle action received")
                if (AuxioService.isForeground) {
                    context.sendBroadcast(
                        Intent(PlaybackActions.ACTION_INVERT_SHUFFLE)
                            .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                    )
                } else {
                    val serviceIntent =
                        Intent(context, AuxioService::class.java).apply {
                            putExtra(AuxioService.INTENT_KEY_START_ID, IntegerTable.START_ID_TASKER)
                        }
                    ContextCompat.startForegroundService(context, serviceIntent)
                }
            }
            ACTION_WIDGET_REPEAT -> {
                L.d("Widget repeat action received")
                if (AuxioService.isForeground) {
                    context.sendBroadcast(
                        Intent(PlaybackActions.ACTION_INC_REPEAT_MODE)
                            .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                    )
                }
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        requestUpdate(context)
        // Revert to the default layout for now until we get a response from WidgetComponent.
        // If we don't, then we will stick with the default widget layout.
        reset(context, UISettingsImpl(context))
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // Another adaptive layout backport for API 21+: We are unable to immediately update
        // the layout ourselves when the widget dimensions change, so we need to request
        // an update from WidgetComponent first.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            requestUpdate(context)
        }
    }

    /**
     * Update the currently shown layout based on the given [WidgetComponent.PlaybackState]
     *
     * @param context [Context] required to update the widget layout.
     * @param uiSettings [UISettings] to obtain round mode configuration
     * @param state [WidgetComponent.PlaybackState] to show, or null if no playback is going on.
     */
    fun update(context: Context, uiSettings: UISettings, state: WidgetComponent.PlaybackState?) {
        if (state == null) {
            // No state, use the default widget.
            L.d("No state provided, returning to default")
            reset(context, uiSettings)
            return
        }

        val awm = AppWidgetManager.getInstance(context)

        // Create and configure each possible layout for the widget.
        val defaultLayout = newWideCardLayout(context, uiSettings, state)
        val views =
            mapOf(
                SizeF(180f, 48f) to newThinStickLayout(context, state),
                SizeF(260f, 48f) to newWideStickLayout(context, state),
                SizeF(180f, 70f) to newThinCardLayout(context, uiSettings, state),
                SizeF(260f, 70f) to newWideCardLayout(context, uiSettings, state),
                SizeF(180f, 140f) to newThinCardLayout(context, uiSettings, state),
                SizeF(260f, 140f) to newWideCardLayout(context, uiSettings, state),
                SizeF(180f, 260f) to newThinPaneLayout(context, uiSettings, state),
                SizeF(260f, 260f) to newWidePaneLayout(context, uiSettings, state),
            )

        // This is the order in which we will disable cover art layouts if they exceed the
        // maximum bitmap memory usage.
        val victims =
            mutableSetOf(
                R.layout.widget_card_thin,
                R.layout.widget_card_horizontal,
                R.layout.widget_pane_thin,
                R.layout.widget_pane_wide,
                R.layout.widget_wafer_thin,
                R.layout.widget_wafer_wide,
                R.layout.widget_docked_thin,
                R.layout.widget_docked_wide,
            )

        // Manually update AppWidgetManager with the new views.
        val component = ComponentName(context, this::class.java)
        while (victims.isNotEmpty()) {
            try {
                awm.updateAppWidgetCompat(context, component, views)
                L.d("Successfully updated RemoteViews layout")
                return
            } catch (e: IllegalArgumentException) {
                val msg = e.message ?: return
                if (
                    !msg.startsWith(
                        "RemoteViews for widget update exceeds maximum bitmap memory usage"
                    )
                ) {
                    throw e
                }
                // Some android devices on Android 12-14 suffer from a bug where the maximum bitmap
                // size calculation does not factor in bitmaps shared across multiple RemoteView
                // forms.
                // To mitigate an outright crash, progressively disable layouts that contain cover
                // art
                // in order of least to most commonly used until it actually works.
                val victim = victims.first()
                val view = views.entries.find { it.value.layoutId == victim } ?: continue
                view.value.discardCover(context)
                victims.remove(victim)
            } catch (e: Exception) {
                // Layout update failed, gracefully degrade to the default widget.
                L.w("Unable to update widget: $e")
                reset(context, uiSettings)
            }
        }
        // We flat-out cannot fit the bitmap into the widget. Weird.
        L.w("Unable to update widget: Bitmap too large")
        reset(context, uiSettings)
    }

    /**
     * Revert to the default layout that displays "No music playing".
     *
     * @param context [Context] required to update the widget layout.
     */
    fun reset(context: Context, uiSettings: UISettings) {
        L.d("Using default layout")
        val layout = newDefaultLayout(context, uiSettings)
        AppWidgetManager.getInstance(context)
            .updateAppWidget(ComponentName(context, this::class.java), layout)
    }

    // --- INTERNAL METHODS ---

    /**
     * Request an update from [WidgetComponent].
     *
     * @param context [Context] required to send update request broadcast.
     */
    private fun requestUpdate(context: Context) {
        L.d("Sending update intent to PlaybackService")
        val intent = Intent(ACTION_WIDGET_UPDATE).addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
        context.sendBroadcast(intent)
    }

    // --- LAYOUTS ---

    private fun newDefaultLayout(context: Context, uiSettings: UISettings): RemoteViews {
        val views = newRemoteViews(context, R.layout.widget_default)
        val mainIntent = context.newMainPendingIntent()
        views.setOnClickPendingIntent(android.R.id.background, mainIntent)
        views.setOnClickPendingIntent(R.id.widget_cover, mainIntent)
        views.setOnClickPendingIntent(R.id.widget_song, mainIntent)
        views.setOnClickPendingIntent(R.id.widget_artist, mainIntent)
        views.setOnClickPendingIntent(R.id.widget_info_container, mainIntent)

        views.setOnClickPendingIntent(
            R.id.widget_play_pause,
            newWidgetPendingIntent(context, ACTION_WIDGET_PLAY_PAUSE),
        )
        views.setImageViewResource(R.id.widget_play_pause, R.drawable.ic_play_24)

        views.setOnClickPendingIntent(
            R.id.widget_shuffle,
            newWidgetPendingIntent(context, ACTION_WIDGET_SHUFFLE),
        )
        views.setImageViewResource(R.id.widget_shuffle, R.drawable.ic_shuffle_off_24)

        views.setOnClickPendingIntent(
            R.id.widget_skip_prev,
            newWidgetPendingIntent(context, ACTION_WIDGET_SKIP_PREV),
        )

        views.setOnClickPendingIntent(
            R.id.widget_skip_next,
            newWidgetPendingIntent(context, ACTION_WIDGET_SKIP_NEXT),
        )

        views.setOnClickPendingIntent(
            R.id.widget_repeat,
            newWidgetPendingIntent(context, ACTION_WIDGET_REPEAT),
        )
        views.setImageViewResource(R.id.widget_repeat, R.drawable.ic_repeat_off_24)

        return views
    }

    private fun newWideCardLayout(
        context: Context,
        uiSettings: UISettings,
        state: WidgetComponent.PlaybackState,
    ) =
        newRemoteViews(context, R.layout.widget_card_horizontal)
            .setupPlaybackState(context, state)
            .setupFullControls(context, state)

    private fun newThinCardLayout(
        context: Context,
        uiSettings: UISettings,
        state: WidgetComponent.PlaybackState,
    ) =
        newRemoteViews(context, R.layout.widget_card_thin)
            .setupPlaybackState(context, state)
            .setupTimelineControls(context, state)

    private fun newThinStickLayout(context: Context, state: WidgetComponent.PlaybackState) =
        newRemoteViews(context, R.layout.widget_stick_thin).setupTimelineControls(context, state)

    private fun newWideStickLayout(context: Context, state: WidgetComponent.PlaybackState) =
        newRemoteViews(context, R.layout.widget_stick_wide).setupFullControls(context, state)

    private fun newThinWaferLayout(
        context: Context,
        uiSettings: UISettings,
        state: WidgetComponent.PlaybackState,
    ) =
        newRemoteViews(context, R.layout.widget_wafer_thin)
            .setupBackground(uiSettings)
            .setupCover(context, state.takeIf { canDisplayWaferCover(uiSettings) })
            .setupFillingCover(uiSettings)
            .setupTimelineControls(context, state)

    private fun newWideWaferLayout(
        context: Context,
        uiSettings: UISettings,
        state: WidgetComponent.PlaybackState,
    ) =
        newRemoteViews(context, R.layout.widget_wafer_wide)
            .setupBackground(uiSettings)
            .setupCover(context, state.takeIf { canDisplayWaferCover(uiSettings) })
            .setupFillingCover(uiSettings)
            .setupFullControls(context, state)

    private fun newThinDockedLayout(
        context: Context,
        uiSettings: UISettings,
        state: WidgetComponent.PlaybackState,
    ) =
        newRemoteViews(context, R.layout.widget_docked_thin)
            .setupBar(uiSettings)
            .setupCover(context, state)
            .setupTimelineControls(context, state)

    private fun newWideDockedLayout(
        context: Context,
        uiSettings: UISettings,
        state: WidgetComponent.PlaybackState,
    ) =
        newRemoteViews(context, R.layout.widget_docked_wide)
            .setupBar(uiSettings)
            .setupCover(context, state)
            .setupFullControls(context, state)

    private fun newThinPaneLayout(
        context: Context,
        uiSettings: UISettings,
        state: WidgetComponent.PlaybackState,
    ) =
        newRemoteViews(context, R.layout.widget_pane_thin)
            .setupBackground(uiSettings)
            .setupPlaybackState(context, state)
            .setupTimelineControls(context, state)

    private fun newWidePaneLayout(
        context: Context,
        uiSettings: UISettings,
        state: WidgetComponent.PlaybackState,
    ) =
        newRemoteViews(context, R.layout.widget_pane_wide)
            .setupBackground(uiSettings)
            .setupPlaybackState(context, state)
            .setupFullControls(context, state)

    /**
     * Set up the control bar in a [RemoteViews] layout that contains one. This is a kind of
     * "floating" drawable that sits in front of the cover and contains the controls.
     */
    private fun RemoteViews.setupBar(uiSettings: UISettings): RemoteViews {
        // Below API 31, enable a rounded bar only if round mode is enabled.
        // On API 31+, the bar should always be round in order to fit in with other widgets.
        val background =
            if (useRoundedRemoteViews(uiSettings)) {
                R.drawable.ui_widget_bg_round
            } else {
                R.drawable.ui_widget_bg_sharp
            }
        setBackgroundResource(R.id.widget_controls, background)
        return this
    }

    /**
     * Set up the background in a [RemoteViews] layout that contains one. This is largely
     * self-explanatory, being a solid-color background that sits behind the cover and controls.
     */
    private fun RemoteViews.setupBackground(uiSettings: UISettings): RemoteViews {
        // Below API 31, enable a rounded background only if round mode is enabled.
        // On API 31+, the background should always be round in order to fit in with other
        // widgets.
        val background =
            if (useRoundedRemoteViews(uiSettings)) {
                R.drawable.ui_widget_bg_round
            } else {
                R.drawable.ui_widget_bg_sharp
            }
        setBackgroundResource(android.R.id.background, background)
        return this
    }

    /**
     * Set up the album cover in a [RemoteViews] layout that contains one.
     *
     * @param context [Context] required to set up the view.
     * @param state Current [WidgetComponent.PlaybackState] to display.
     */
    private fun RemoteViews.setupCover(
        context: Context,
        state: WidgetComponent.PlaybackState?,
    ): RemoteViews {
        if (state == null) {
            setImageViewBitmap(R.id.widget_cover, null)
            setContentDescription(R.id.widget_cover, null)
            return this
        }

        if (state.cover != null) {
            setImageViewBitmap(R.id.widget_cover, state.cover)
            setContentDescription(
                R.id.widget_cover,
                context.getString(
                    R.string.desc_album_cover,
                    state.song.album.name.resolve(context),
                ),
            )
        } else {
            discardCover(context)
        }

        return this
    }

    private fun RemoteViews.discardCover(context: Context) {
        setImageViewResource(R.id.widget_cover, R.drawable.ic_remote_default_cover_24)
        setContentDescription(R.id.widget_cover, context.getString(R.string.desc_no_cover))
    }

    private fun RemoteViews.setupFillingCover(uiSettings: UISettings): RemoteViews {
        // Below API 31, enable a rounded background only if round mode is enabled.
        // On API 31+, the background should always be round in order to fit in with other
        // widgets.
        val background =
            if (useRoundedRemoteViews(uiSettings)) {
                R.drawable.ui_widget_bg_round
            } else {
                R.drawable.ui_widget_bg_sharp
            }
        setBackgroundResource(R.id.widget_cover, background)
        return this
    }

    /**
     * Set up the album cover, song title, and artist name in a [RemoteViews] layout that contains
     * them.
     *
     * @param context [Context] required to set up the view.
     * @param state Current [WidgetComponent.PlaybackState] to display.
     */
    private fun RemoteViews.setupPlaybackState(
        context: Context,
        state: WidgetComponent.PlaybackState,
    ): RemoteViews {
        setupCover(context, state)
        setTextViewText(R.id.widget_song, state.song.name.resolve(context))
        setTextViewText(R.id.widget_artist, state.song.artists.resolveNames(context))

        val mainIntent = context.newMainPendingIntent()
        setOnClickPendingIntent(R.id.widget_cover, mainIntent)
        setOnClickPendingIntent(R.id.widget_song, mainIntent)
        setOnClickPendingIntent(R.id.widget_artist, mainIntent)
        setOnClickPendingIntent(R.id.widget_info_container, mainIntent)
        return this
    }

    /**
     * Set up the play/pause button in a [RemoteViews] layout that contains one.
     *
     * @param context [Context] required to set up the view.
     * @param state Current [WidgetComponent.PlaybackState] to display.
     */
    private fun RemoteViews.setupBasicControls(
        context: Context,
        state: WidgetComponent.PlaybackState,
    ): RemoteViews {
        setOnClickPendingIntent(
            R.id.widget_play_pause,
            newWidgetPendingIntent(context, ACTION_WIDGET_PLAY_PAUSE),
        )

        val icon: Int
        val background: Int
        if (state.isPlaying) {
            icon = R.drawable.ic_pause_24
            background = R.drawable.ui_remote_fab_container_playing
        } else {
            icon = R.drawable.ic_play_24
            background = R.drawable.ui_remote_fab_container_paused
        }

        setImageViewResource(R.id.widget_play_pause, icon)
        // If the view doesn't have a background set or on stick layouts, background is handled
        return this
    }

    /**
     * Set up the play/pause and skip previous/next button in a [RemoteViews] layout that contains
     * them.
     *
     * @param context [Context] required to set up the view.
     * @param state Current [WidgetComponent.PlaybackState] to display.
     */
    private fun RemoteViews.setupTimelineControls(
        context: Context,
        state: WidgetComponent.PlaybackState,
    ): RemoteViews {
        setupBasicControls(context, state)
        setLayoutDirection(R.id.widget_controls, View.LAYOUT_DIRECTION_LTR)
        setOnClickPendingIntent(
            R.id.widget_skip_prev,
            newWidgetPendingIntent(context, ACTION_WIDGET_SKIP_PREV),
        )
        setOnClickPendingIntent(
            R.id.widget_skip_next,
            newWidgetPendingIntent(context, ACTION_WIDGET_SKIP_NEXT),
        )
        return this
    }

    /**
     * Set up the play/pause, skip previous/next, and repeat/shuffle buttons in a [RemoteViews] that
     * contains them.
     *
     * @param context [Context] required to set up the view.
     * @param state Current [WidgetComponent.PlaybackState] to display.
     */
    private fun RemoteViews.setupFullControls(
        context: Context,
        state: WidgetComponent.PlaybackState,
    ): RemoteViews {
        setupTimelineControls(context, state)

        setOnClickPendingIntent(
            R.id.widget_repeat,
            newWidgetPendingIntent(context, ACTION_WIDGET_REPEAT),
        )
        setOnClickPendingIntent(
            R.id.widget_shuffle,
            newWidgetPendingIntent(context, ACTION_WIDGET_SHUFFLE),
        )

        val repeatRes =
            when (state.repeatMode) {
                RepeatMode.NONE -> R.drawable.ic_repeat_off_24
                RepeatMode.ALL -> R.drawable.ic_repeat_on_24
                RepeatMode.TRACK -> R.drawable.ic_repeat_one_24
            }
        setImageViewResource(R.id.widget_repeat, repeatRes)

        val shuffleRes =
            when {
                state.isShuffled -> R.drawable.ic_shuffle_on_24
                else -> R.drawable.ic_shuffle_off_24
            }
        setImageViewResource(R.id.widget_shuffle, shuffleRes)

        return this
    }

    private fun newWidgetPendingIntent(context: Context, action: String): PendingIntent {
        val intent =
            Intent(context, WidgetProvider::class.java).apply {
                this.action = action
            }
        val requestCode = action.hashCode()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun useRoundedRemoteViews(uiSettings: UISettings) =
        uiSettings.roundMode || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private fun canDisplayWaferCover(uiSettings: UISettings) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || !uiSettings.roundMode

    companion object {
        const val ACTION_WIDGET_UPDATE = BuildConfig.APPLICATION_ID + ".action.WIDGET_UPDATE"
        const val ACTION_WIDGET_PLAY_PAUSE = BuildConfig.APPLICATION_ID + ".widget.PLAY_PAUSE"
        const val ACTION_WIDGET_SKIP_NEXT = BuildConfig.APPLICATION_ID + ".widget.SKIP_NEXT"
        const val ACTION_WIDGET_SKIP_PREV = BuildConfig.APPLICATION_ID + ".widget.SKIP_PREV"
        const val ACTION_WIDGET_SHUFFLE = BuildConfig.APPLICATION_ID + ".widget.SHUFFLE"
        const val ACTION_WIDGET_REPEAT = BuildConfig.APPLICATION_ID + ".widget.REPEAT"
    }
}
