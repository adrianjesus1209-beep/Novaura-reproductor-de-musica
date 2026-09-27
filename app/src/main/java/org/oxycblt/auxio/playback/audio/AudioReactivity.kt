/*
 * Copyright (c) 2026 Auxio Project
 * AudioReactivity.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.audio

import org.oxycblt.auxio.IntegerTable

/**
 * How strongly the playback UI should react to the audio it is playing.
 *
 * Also doubles as the master switch: [OFF] stops the audio processor entirely rather than merely
 * zeroing the effect, so users on low-end devices can opt out of the work as well as the visuals.
 *
 * @author Novaura
 */
enum class AudioReactivity {
    /** No audio analysis at all. The progress wave keeps its original fixed-amplitude look. */
    OFF,
    /** A restrained amount of movement. */
    SUBTLE,
    /** The default balance between noticeable and unobtrusive. */
    NORMAL,
    /** Full amplitude range. */
    INTENSE;

    /** The strength of the effect, from 0 (none) to 1 (full range). */
    val strength: Float
        get() =
            when (this) {
                OFF -> 0f
                SUBTLE -> 0.45f
                NORMAL -> 0.75f
                INTENSE -> 1f
            }

    /** Whether audio analysis should run at all. */
    val isEnabled: Boolean
        get() = this != OFF

    /** The integer representation of this instance. */
    val intCode: Int
        get() =
            when (this) {
                OFF -> IntegerTable.AUDIO_REACTIVITY_OFF
                SUBTLE -> IntegerTable.AUDIO_REACTIVITY_SUBTLE
                NORMAL -> IntegerTable.AUDIO_REACTIVITY_NORMAL
                INTENSE -> IntegerTable.AUDIO_REACTIVITY_INTENSE
            }

    companion object {
        /** Convert an [AudioReactivity] integer representation into an instance. */
        fun fromIntCode(intCode: Int) =
            when (intCode) {
                IntegerTable.AUDIO_REACTIVITY_OFF -> OFF
                IntegerTable.AUDIO_REACTIVITY_SUBTLE -> SUBTLE
                IntegerTable.AUDIO_REACTIVITY_NORMAL -> NORMAL
                IntegerTable.AUDIO_REACTIVITY_INTENSE -> INTENSE
                else -> null
            }
    }
}
