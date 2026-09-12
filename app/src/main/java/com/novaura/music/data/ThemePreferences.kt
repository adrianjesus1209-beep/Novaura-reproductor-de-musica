package com.novaura.music.data

import android.content.Context
import android.content.SharedPreferences

class ThemePreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("novaura_theme_prefs", Context.MODE_PRIVATE)

    var bgImageUri: String?
        get() = prefs.getString(KEY_BG_IMAGE_URI, null)
        set(value) = prefs.edit().putString(KEY_BG_IMAGE_URI, value).apply()

    var bgDarkness: Float
        get() = prefs.getFloat(KEY_BG_DARKNESS, 0.5f)
        set(value) = prefs.edit().putFloat(KEY_BG_DARKNESS, value).apply()

    var bgBlur: Float
        get() = prefs.getFloat(KEY_BG_BLUR, 10.0f)
        set(value) = prefs.edit().putFloat(KEY_BG_BLUR, value).apply()

    var isDarkMode: Boolean
        get() = prefs.getBoolean(KEY_IS_DARK_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_IS_DARK_MODE, value).apply()

    fun resetTheme() {
        prefs.edit()
            .remove(KEY_BG_IMAGE_URI)
            .putFloat(KEY_BG_DARKNESS, 0.5f)
            .putFloat(KEY_BG_BLUR, 10.0f)
            .apply()
    }

    companion object {
        private const val KEY_BG_IMAGE_URI = "bg_image_uri"
        private const val KEY_BG_DARKNESS = "bg_darkness"
        private const val KEY_BG_BLUR = "bg_blur"
        private const val KEY_IS_DARK_MODE = "is_dark_mode"
    }
}
