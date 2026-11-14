/*
 * Copyright (c) 2026 Auxio Project
 * EqualizerController.kt is part of Auxio.
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
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber as L

/**
 * Service-scoped singleton managing the single active Android [Equalizer], [BassBoost], and
 * [Virtualizer] session attached to ExoPlayer's audio session ID so effects persist continuously
 * after closing [EqualizerDialogFragment] and survive track/restart transitions.
 */
@OptIn(UnstableApi::class)
@Singleton
class EqualizerController @Inject constructor(@ApplicationContext private val context: Context) {
    data class Snapshot(
        val attached: Boolean,
        val enabled: Boolean,
        val bandCount: Int,
        val minLevel: Float,
        val maxLevel: Float,
        val centerFreqsMilliHz: List<Int>,
        val presetNames: List<String>,
        val currentPreset: Short,
        val gains: List<Short>,
        val bassSupported: Boolean,
        val bassStrength: Short,
        val virtualizerSupported: Boolean,
        val virtualizerStrength: Short,
    )

    private var currentSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    private var currentState: EqualizerSettings.State = EqualizerSettings.load(context)
    private val desiredGains = mutableListOf<Short>()

    @Synchronized
    fun attachSession(sessionId: Int) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId <= 0) {
            return
        }
        if (currentSessionId == sessionId && equalizer != null) {
            applyProfileToEffects()
            return
        }

        releaseEffectsLocked()
        currentSessionId = sessionId

        val newEqualizer =
            try {
                Equalizer(0, sessionId)
            } catch (e: RuntimeException) {
                L.w(e, "Failed to attach equalizer to audio session $sessionId")
                null
            }
        if (newEqualizer == null) {
            currentSessionId = C.AUDIO_SESSION_ID_UNSET
            return
        }

        equalizer = newEqualizer
        bassBoost =
            try {
                BassBoost(0, sessionId)
            } catch (e: RuntimeException) {
                L.w(e, "Failed to attach BassBoost to audio session $sessionId")
                null
            }
        virtualizer =
            try {
                Virtualizer(0, sessionId)
            } catch (e: RuntimeException) {
                L.w(e, "Failed to attach Virtualizer to audio session $sessionId")
                null
            }

        currentState = EqualizerSettings.load(context)
        applyProfileToEffects()
    }

    @Synchronized
    fun releaseSession() {
        releaseEffectsLocked()
        currentSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    @Synchronized
    fun snapshot(fallbackSessionId: Int?): Snapshot {
        if (equalizer == null && fallbackSessionId != null && fallbackSessionId > 0) {
            attachSession(fallbackSessionId)
        }
        val eq = equalizer
        if (eq == null) {
            return Snapshot(
                attached = false,
                enabled = false,
                bandCount = 0,
                minLevel = -1500f,
                maxLevel = 1500f,
                centerFreqsMilliHz = emptyList(),
                presetNames = emptyList(),
                currentPreset = -1,
                gains = emptyList(),
                bassSupported = false,
                bassStrength = 0,
                virtualizerSupported = false,
                virtualizerStrength = 0,
            )
        }

        val bandCount =
            try {
                eq.numberOfBands.toInt().coerceAtLeast(0)
            } catch (e: RuntimeException) {
                0
            }
        val bandRange =
            try {
                eq.bandLevelRange
            } catch (e: RuntimeException) {
                shortArrayOf(-1500, 1500)
            }
        val minLevel = bandRange.getOrElse(0) { -1500 }.toFloat()
        val maxLevel = bandRange.getOrElse(1) { 1500 }.toFloat().coerceAtLeast(minLevel + 1f)

        val centerFreqs =
            (0 until bandCount).map { band ->
                try {
                    eq.getCenterFreq(band.toShort())
                } catch (e: RuntimeException) {
                    0
                }
            }
        val numPresets =
            try {
                eq.numberOfPresets.toInt().coerceAtLeast(0)
            } catch (e: RuntimeException) {
                0
            }
        val presetNames =
            (0 until numPresets).map { idx ->
                try {
                    eq.getPresetName(idx.toShort())
                } catch (e: RuntimeException) {
                    "Preset $idx"
                }
            }

        syncDesiredGainsLocked(eq, bandCount)

        return Snapshot(
            attached = true,
            enabled = currentState.enabled,
            bandCount = bandCount,
            minLevel = minLevel,
            maxLevel = maxLevel,
            centerFreqsMilliHz = centerFreqs,
            presetNames = presetNames,
            currentPreset = currentState.preset,
            gains = desiredGains.toList(),
            bassSupported = bassBoost?.strengthSupported == true,
            bassStrength = currentState.bassStrength,
            virtualizerSupported = virtualizer?.strengthSupported == true,
            virtualizerStrength = currentState.virtualizerStrength,
        )
    }

    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean {
        val eq = equalizer ?: return false
        try {
            eq.enabled = enabled
            bassBoost?.enabled = enabled
            virtualizer?.enabled = enabled
        } catch (e: RuntimeException) {
            L.w(e, "Failed to update equalizer enabled state")
            return false
        }

        currentState = currentState.copy(enabled = enabled)
        if (enabled) {
            val bandCount = safeBandCount(eq)
            syncDesiredGainsLocked(eq, bandCount)
            for (band in 0 until bandCount) {
                val gain = desiredGains.getOrElse(band) { 0 }
                try {
                    eq.setBandLevel(band.toShort(), gain)
                } catch (e: RuntimeException) {
                    L.w(e, "Failed to restore band $band level")
                }
            }
        }
        persistLocked()
        return true
    }

    @Synchronized
    fun setBandLevel(band: Int, levelMillis: Short): Boolean {
        val eq = equalizer ?: return false
        val bandCount = safeBandCount(eq)
        if (band !in 0 until bandCount) {
            return false
        }
        syncDesiredGainsLocked(eq, bandCount)
        desiredGains[band] = levelMillis
        currentState = currentState.copy(preset = -1, gains = desiredGains.toList())
        try {
            eq.setBandLevel(band.toShort(), levelMillis)
        } catch (e: RuntimeException) {
            L.w(e, "Failed to set band $band level")
            return false
        }
        persistLocked()
        return true
    }

    @Synchronized
    fun applyPreset(presetIndex: Short): List<Short>? {
        val eq = equalizer ?: return null
        val bandCount = safeBandCount(eq)
        syncDesiredGainsLocked(eq, bandCount)
        if (presetIndex >= 0) {
            try {
                eq.usePreset(presetIndex)
            } catch (e: RuntimeException) {
                L.w(e, "Failed to apply preset $presetIndex")
            }
            for (band in 0 until bandCount) {
                desiredGains[band] =
                    try {
                        eq.getBandLevel(band.toShort())
                    } catch (e: RuntimeException) {
                        0
                    }
            }
        }
        currentState = currentState.copy(preset = presetIndex, gains = desiredGains.toList())
        persistLocked()
        return desiredGains.toList()
    }

    @Synchronized
    fun setBassStrength(strength: Short) {
        val clamped = strength.coerceIn(0, 1000)
        currentState = currentState.copy(bassStrength = clamped)
        bassBoost?.let { bb ->
            if (bb.strengthSupported) {
                try {
                    bb.setStrength(clamped)
                } catch (e: RuntimeException) {
                    L.w(e, "Failed to set bass strength")
                }
            }
        }
        persistLocked()
    }

    @Synchronized
    fun setVirtualizerStrength(strength: Short) {
        val clamped = strength.coerceIn(0, 1000)
        currentState = currentState.copy(virtualizerStrength = clamped)
        virtualizer?.let { vz ->
            if (vz.strengthSupported) {
                try {
                    vz.setStrength(clamped)
                } catch (e: RuntimeException) {
                    L.w(e, "Failed to set virtualizer strength")
                }
            }
        }
        persistLocked()
    }

    @Synchronized
    fun reset(): Snapshot? {
        val eq = equalizer ?: return null
        val bandCount = safeBandCount(eq)
        desiredGains.clear()
        for (band in 0 until bandCount) {
            desiredGains.add(0)
            try {
                eq.setBandLevel(band.toShort(), 0)
            } catch (e: RuntimeException) {
                L.w(e, "Failed to reset band $band")
            }
        }
        currentState =
            currentState.copy(
                gains = desiredGains.toList(),
                preset = -1,
                bassStrength = 0,
                virtualizerStrength = 0,
            )
        try {
            if (bassBoost?.strengthSupported == true) bassBoost?.setStrength(0)
            if (virtualizer?.strengthSupported == true) virtualizer?.setStrength(0)
        } catch (e: RuntimeException) {
            L.w(e, "Failed to reset audio effects")
        }
        persistLocked()
        return snapshot(currentSessionId)
    }

    private fun applyProfileToEffects() {
        val eq = equalizer ?: return
        val bandCount = safeBandCount(eq)
        syncDesiredGainsLocked(eq, bandCount)

        try {
            eq.enabled = currentState.enabled
            bassBoost?.enabled = currentState.enabled
            virtualizer?.enabled = currentState.enabled
            if (bassBoost?.strengthSupported == true) {
                bassBoost?.setStrength(currentState.bassStrength.coerceIn(0, 1000))
            }
            if (virtualizer?.strengthSupported == true) {
                virtualizer?.setStrength(currentState.virtualizerStrength.coerceIn(0, 1000))
            }
            if (currentState.enabled) {
                for (band in 0 until bandCount) {
                    eq.setBandLevel(band.toShort(), desiredGains.getOrElse(band) { 0 })
                }
            }
        } catch (e: RuntimeException) {
            L.w(e, "Failed to apply persisted equalizer profile")
        }
    }

    private fun syncDesiredGainsLocked(eq: Equalizer, bandCount: Int) {
        if (desiredGains.size == bandCount) {
            return
        }
        desiredGains.clear()
        for (band in 0 until bandCount) {
            val fallback =
                try {
                    eq.getBandLevel(band.toShort())
                } catch (e: RuntimeException) {
                    0
                }
            desiredGains.add(currentState.gains.getOrElse(band) { fallback })
        }
    }

    private fun safeBandCount(eq: Equalizer): Int =
        try {
            eq.numberOfBands.toInt().coerceAtLeast(0)
        } catch (e: RuntimeException) {
            0
        }

    private fun persistLocked() {
        EqualizerSettings.save(
            context,
            currentState.enabled,
            desiredGains,
            currentState.preset,
            currentState.bassStrength,
            currentState.virtualizerStrength,
        )
    }

    private fun releaseEffectsLocked() {
        equalizer?.release()
        equalizer = null
        bassBoost?.release()
        bassBoost = null
        virtualizer?.release()
        virtualizer = null
    }
}
