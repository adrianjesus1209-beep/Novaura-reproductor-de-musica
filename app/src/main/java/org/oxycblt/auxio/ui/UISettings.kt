/*
 * Copyright (c) 2023 Auxio Project
 * UISettings.kt is part of Auxio.
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
 
package org.oxycblt.auxio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.Settings
import org.oxycblt.auxio.ui.accent.Accent
import timber.log.Timber as L

/**
 * User configuration for the general app UI.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
interface UISettings : Settings<UISettings.Listener> {
    /** The current theme. Represented by the AppCompatDelegate constants. */
    val theme: Int
    /** Whether to use a black background when a dark theme is currently used. */
    val useBlackTheme: Boolean
    /** The current [Accent] (Color Scheme). */
    var accent: Accent
    /** Whether to round additional UI elements that require album covers to be rounded. */
    val roundMode: Boolean
    /** The current language setting (0 = System, 1 = Spanish, 2 = English). */
    val language: Int

    /** Persist and apply a new language. */
    fun setLanguage(language: Int)

    /** Ensure the configured language is applied. */
    fun applyCurrentLanguage()

    /** Whether custom wallpaper is enabled. */
    val customWallpaperEnabled: Boolean
    /** Blur radius percentage for custom wallpaper (0 to 100). */
    val customWallpaperBlur: Int
    /** Brightness adjustment percentage for custom wallpaper (-100 to 100). */
    val customWallpaperBrightness: Int

    /** Save wallpaper preferences. */
    fun setCustomWallpaper(enabled: Boolean, blur: Int, brightness: Int)

    /** Copy and downsample an image from URI into internal storage. */
    fun saveCustomWallpaperFromUri(sourceUri: Uri): Boolean

    /** Get file pointing to the persisted custom wallpaper. */
    fun getCustomWallpaperFile(): File

    /** Clear custom wallpaper and disable it. */
    fun clearCustomWallpaper()

    interface Listener {
        /** Called when [roundMode] changes. */
        fun onRoundModeChanged() {}

        /** Called when custom wallpaper settings change. */
        fun onCustomWallpaperChanged() {}
    }

    companion object {
        const val LANGUAGE_SYSTEM = 0
        const val LANGUAGE_SPANISH = 1
        const val LANGUAGE_ENGLISH = 2

        const val DEFAULT_WALLPAPER_BLUR = 20
        const val DEFAULT_WALLPAPER_BRIGHTNESS = -30
        const val KEY_WALLPAPER_ENABLED = "auxio_custom_wallpaper_enabled"
        const val KEY_WALLPAPER_BLUR = "auxio_custom_wallpaper_blur"
        const val KEY_WALLPAPER_BRIGHTNESS = "auxio_custom_wallpaper_brightness"
    }
}

class UISettingsImpl @Inject constructor(@ApplicationContext private val context: Context) :
    Settings.Impl<UISettings.Listener>(context), UISettings {
    override val theme: Int
        get() =
            sharedPreferences.getInt(
                getString(R.string.set_key_theme),
                AppCompatDelegate.MODE_NIGHT_YES,
            )

    override val useBlackTheme: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_black_theme), false)

    override var accent: Accent
        get() =
            Accent.from(
                sharedPreferences.getInt(getString(R.string.set_key_accent), Accent.DEFAULT)
            )
        set(value) {
            sharedPreferences.edit {
                putInt(getString(R.string.set_key_accent), value.index)
                apply()
            }
        }

    override val roundMode: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_round_mode), true)

    override val language: Int
        get() =
            sharedPreferences.getInt(
                getString(R.string.set_key_language),
                UISettings.LANGUAGE_SYSTEM,
            )

    override fun setLanguage(language: Int) {
        sharedPreferences.edit {
            putInt(getString(R.string.set_key_language), language)
            apply()
        }
        applyLanguage(language)
    }

    override fun applyCurrentLanguage() {
        applyLanguage(language)
    }

    override val customWallpaperEnabled: Boolean
        get() = sharedPreferences.getBoolean(UISettings.KEY_WALLPAPER_ENABLED, false)

    override val customWallpaperBlur: Int
        get() =
            sharedPreferences.getInt(
                UISettings.KEY_WALLPAPER_BLUR,
                UISettings.DEFAULT_WALLPAPER_BLUR,
            )

    override val customWallpaperBrightness: Int
        get() =
            sharedPreferences.getInt(
                UISettings.KEY_WALLPAPER_BRIGHTNESS,
                UISettings.DEFAULT_WALLPAPER_BRIGHTNESS,
            )

    override fun setCustomWallpaper(enabled: Boolean, blur: Int, brightness: Int) {
        sharedPreferences.edit {
            putBoolean(UISettings.KEY_WALLPAPER_ENABLED, enabled)
            putInt(UISettings.KEY_WALLPAPER_BLUR, blur)
            putInt(UISettings.KEY_WALLPAPER_BRIGHTNESS, brightness)
            apply()
        }
    }

    override fun getCustomWallpaperFile(): File {
        val dir = File(context.filesDir, "theme")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "custom_wallpaper.jpg")
    }

    override fun clearCustomWallpaper() {
        val file = getCustomWallpaperFile()
        if (file.exists()) file.delete()
        sharedPreferences.edit {
            putBoolean(UISettings.KEY_WALLPAPER_ENABLED, false)
            apply()
        }
    }

    override fun saveCustomWallpaperFromUri(sourceUri: Uri): Boolean {
        return try {
            val destination = getCustomWallpaperFile()
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
            val maxDim = 1920
            var sampleSize = 1
            while (
                options.outWidth / sampleSize > maxDim || options.outHeight / sampleSize > maxDim
            ) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap =
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, decodeOptions)
                } ?: return false

            FileOutputStream(destination).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()
            true
        } catch (e: Exception) {
            L.e("Failed to save custom wallpaper: $e")
            false
        }
    }

    private fun applyLanguage(language: Int) {
        val targetLocales =
            when (language) {
                UISettings.LANGUAGE_SPANISH -> LocaleListCompat.forLanguageTags("es")
                UISettings.LANGUAGE_ENGLISH -> LocaleListCompat.forLanguageTags("en")
                else -> LocaleListCompat.getEmptyLocaleList()
            }
        L.d("Applying language: $language (tags=${targetLocales.toLanguageTags()})")
        if (AppCompatDelegate.getApplicationLocales() != targetLocales) {
            AppCompatDelegate.setApplicationLocales(targetLocales)
        }
    }

    override fun migrate() {
        if (sharedPreferences.contains(OLD_KEY_ACCENT3)) {
            L.d("Migrating $OLD_KEY_ACCENT3")

            sharedPreferences.edit {
                putInt(getString(R.string.set_key_accent), Accent.DEFAULT)
                remove(OLD_KEY_ACCENT3)
                apply()
            }
        }
        val currentAccent =
            sharedPreferences.getInt(getString(R.string.set_key_accent), Accent.DEFAULT)
        if (currentAccent !in 0 until Accent.MAX) {
            sharedPreferences.edit {
                putInt(getString(R.string.set_key_accent), Accent.DEFAULT)
                apply()
            }
        }
    }

    override fun onSettingChanged(key: String, listener: UISettings.Listener) {
        when (key) {
            getString(R.string.set_key_round_mode) -> {
                L.d("Dispatching round mode setting change")
                listener.onRoundModeChanged()
            }
            UISettings.KEY_WALLPAPER_ENABLED,
            UISettings.KEY_WALLPAPER_BLUR,
            UISettings.KEY_WALLPAPER_BRIGHTNESS -> {
                L.d("Dispatching custom wallpaper setting change")
                listener.onCustomWallpaperChanged()
            }
        }
    }

    private companion object {
        const val OLD_KEY_ACCENT3 = "auxio_accent"
    }
}
