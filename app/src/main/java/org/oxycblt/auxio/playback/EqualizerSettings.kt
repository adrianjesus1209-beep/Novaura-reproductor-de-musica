/*
 * Copyright (c) 2026 Auxio Project
 * EqualizerSettings.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback

import android.content.Context
import androidx.annotation.StringRes
import org.oxycblt.auxio.R

/**
 * Persistence for the in-app equalizer. Band levels are stored as a flat CSV of millibels so that
 * they survive app restarts while adapting to devices with differing band counts. Also stores
 * presets and audio effect strengths (BassBoost and Virtualizer).
 */
object EqualizerSettings {
    private const val PREF_NAME = "equalizer"
    private const val GAIN_SEPARATOR = ','
    private const val KEY_PRESET = "equalizer_preset"
    private const val KEY_BASS = "equalizer_bass_strength"
    private const val KEY_VIRTUALIZER = "equalizer_virtualizer_strength"

    /** [State] describing the currently persisted equalizer configuration. */
    data class State(
        val enabled: Boolean,
        val gains: List<Short>,
        val preset: Short = -1,
        val bassStrength: Short = 0,
        val virtualizerStrength: Short = 0,
    )

    /**
     * Persist the given equalizer state.
     *
     * @param context A context to load resources against.
     * @param enabled Whether the equalizer was active.
     * @param gains The band level of every band, in millibels.
     * @param preset The active preset index, or -1 for custom.
     * @param bassStrength The bass boost strength from 0 to 1000.
     * @param virtualizerStrength The virtualizer strength from 0 to 1000.
     */
    fun save(
        context: Context,
        enabled: Boolean,
        gains: List<Short>,
        preset: Short = -1,
        bassStrength: Short = 0,
        virtualizerStrength: Short = 0,
    ) {
        prefs(context)
            .edit()
            .putBoolean(key(context, R.string.set_key_equalizer_enabled), enabled)
            .putString(
                key(context, R.string.set_key_equalizer_gains),
                gains.joinToString(GAIN_SEPARATOR.toString()),
            )
            .putInt(KEY_PRESET, preset.toInt())
            .putInt(KEY_BASS, bassStrength.toInt())
            .putInt(KEY_VIRTUALIZER, virtualizerStrength.toInt())
            .apply()
    }

    /**
     * Load the previously persisted equalizer state.
     *
     * @param context A context to load resources against.
     */
    fun load(context: Context): State {
        val prefs = prefs(context)
        val enabled = prefs.getBoolean(key(context, R.string.set_key_equalizer_enabled), false)
        val gains = mutableListOf<Short>()
        prefs.getString(key(context, R.string.set_key_equalizer_gains), null)?.let { raw ->
            raw.split(GAIN_SEPARATOR).forEach { part -> part.toShortOrNull()?.let(gains::add) }
        }
        val preset = prefs.getInt(KEY_PRESET, -1).toShort()
        val bassStrength = prefs.getInt(KEY_BASS, 0).toShort()
        val virtualizerStrength = prefs.getInt(KEY_VIRTUALIZER, 0).toShort()
        return State(enabled, gains, preset, bassStrength, virtualizerStrength)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun key(context: Context, @StringRes stringRes: Int) = context.getString(stringRes)
}
