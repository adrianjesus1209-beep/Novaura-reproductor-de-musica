/*
 * Copyright (c) 2024 Auxio Project
 * WidgetCache.kt is part of Auxio.
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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import org.oxycblt.auxio.playback.state.RepeatMode
import timber.log.Timber as L

/**
 * Persistent cache for widget state so that the last played song (title, artist, album cover, and
 * playback controls) remains visible and playable even when the playback service or app is closed.
 */
object WidgetCache {
    private const val PREFS_NAME = "novaura_widget_cache"
    private const val KEY_HAS_DATA = "has_data"
    private const val KEY_SONG_NAME = "song_name"
    private const val KEY_ARTIST_NAME = "artist_name"
    private const val KEY_ALBUM_NAME = "album_name"
    private const val KEY_REPEAT_MODE = "repeat_mode"
    private const val KEY_IS_SHUFFLED = "is_shuffled"
    private const val COVER_FILE_NAME = "widget_cached_cover.png"
    private const val MAX_COVER_DIMEN = 512

    data class CachedState(
        val songName: String,
        val artistName: String,
        val albumName: String,
        val cover: Bitmap?,
        val repeatMode: RepeatMode,
        val isShuffled: Boolean,
    )

    @Volatile private var inMemoryCache: CachedState? = null

    /** Persist the latest playback info and cover art to disk for the widget. */
    fun save(
        context: Context,
        songName: String,
        artistName: String,
        albumName: String,
        cover: Bitmap?,
        repeatMode: RepeatMode,
        isShuffled: Boolean,
    ) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs
                .edit()
                .putBoolean(KEY_HAS_DATA, true)
                .putString(KEY_SONG_NAME, songName)
                .putString(KEY_ARTIST_NAME, artistName)
                .putString(KEY_ALBUM_NAME, albumName)
                .putInt(KEY_REPEAT_MODE, repeatMode.ordinal)
                .putBoolean(KEY_IS_SHUFFLED, isShuffled)
                .apply()

            val coverFile = File(context.filesDir, COVER_FILE_NAME)
            if (cover != null && !cover.isRecycled) {
                val bitmapToSave =
                    if (cover.width > MAX_COVER_DIMEN || cover.height > MAX_COVER_DIMEN) {
                        Bitmap.createScaledBitmap(cover, MAX_COVER_DIMEN, MAX_COVER_DIMEN, true)
                    } else {
                        cover
                    }
                FileOutputStream(coverFile).use { out ->
                    bitmapToSave.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
            } else if (coverFile.exists()) {
                coverFile.delete()
            }

            inMemoryCache =
                CachedState(
                    songName = songName,
                    artistName = artistName,
                    albumName = albumName,
                    cover = cover,
                    repeatMode = repeatMode,
                    isShuffled = isShuffled,
                )

            L.d("WidgetCache: Successfully saved widget state for $songName - $artistName")
        } catch (e: Exception) {
            L.w("WidgetCache: Failed to save widget state: $e")
        }
    }

    /** Load the previously cached widget state if available. */
    fun load(context: Context): CachedState? {
        val mem = inMemoryCache
        if (mem != null && (mem.cover == null || !mem.cover.isRecycled)) {
            return mem
        }

        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_HAS_DATA, false)) {
                return null
            }

            val songName = prefs.getString(KEY_SONG_NAME, null) ?: return null
            val artistName = prefs.getString(KEY_ARTIST_NAME, "") ?: ""
            val albumName = prefs.getString(KEY_ALBUM_NAME, "") ?: ""
            val repeatOrdinal = prefs.getInt(KEY_REPEAT_MODE, RepeatMode.NONE.ordinal)
            val repeatMode = RepeatMode.entries.getOrElse(repeatOrdinal) { RepeatMode.NONE }
            val isShuffled = prefs.getBoolean(KEY_IS_SHUFFLED, false)

            val coverFile = File(context.filesDir, COVER_FILE_NAME)
            val cover =
                if (coverFile.exists() && coverFile.length() > 0) {
                    BitmapFactory.decodeFile(coverFile.absolutePath)
                } else {
                    null
                }

            val loaded =
                CachedState(
                    songName = songName,
                    artistName = artistName,
                    albumName = albumName,
                    cover = cover,
                    repeatMode = repeatMode,
                    isShuffled = isShuffled,
                )
            inMemoryCache = loaded
            loaded
        } catch (e: Exception) {
            L.w("WidgetCache: Failed to load widget state: $e")
            null
        }
    }
}
