/*
 * Copyright (c) 2022 Auxio Project
 * StyledSeekBar.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.ui

import android.content.Context
import android.util.AttributeSet
import kotlin.math.max
import org.oxycblt.auxio.databinding.ViewSeekBarBinding
import org.oxycblt.auxio.playback.formatDurationDs
import org.oxycblt.auxio.playback.ui.waveform.WaveformSeekBarView
import org.oxycblt.auxio.util.inflater

/**
 * A seekbar that displays audio waveform progress and formatted timestamps on the sides, replacing
 * the flat linear slider.
 */
class StyledSeekBar
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    ForcedLTRFrameLayout(context, attrs, defStyleAttr), WaveformSeekBarView.OnSeekListener {
    private val binding = ViewSeekBarBinding.inflate(context.inflater, this, true)
    private var internalPositionDs: Long = 0L
    private var internalDurationDs: Long = 1L
    private var isSeeking = false

    init {
        binding.seekBarWaveform.listener = this
    }

    /** Enables/disables wavy active-track rendering to match playback state. */
    fun setWaveEnabled(enabled: Boolean) {
        // Maintained for compatibility with playback state callers
    }

    /**
     * Sets a deterministic seed for the waveform profile based on the current song. Call this
     * whenever the song changes so the waveform pattern reflects the song identity.
     */
    fun setSongSeed(seed: Long) {
        binding.seekBarWaveform.setSongSeed(seed)
    }

    /** Sets the source of the current audio level, in the range 0..1. */
    fun setAudioLevelProvider(provider: (() -> Float)?) {
        binding.seekBarWaveform.audioLevelProvider = provider
    }

    /** Sets the source of the current audio reactivity strength, from 0 to 1. */
    fun setAudioReactivityProvider(provider: (() -> Float)?) {
        // Maintained for compatibility
    }

    /** The current [Listener] attached to this instance. */
    var listener: Listener? = null

    /** The current position, in deci-seconds (1/10th of a second). */
    var positionDs: Long
        get() = internalPositionDs
        set(value) {
            val from = max(value, 0)
            if (from <= internalDurationDs && !isSeeking) {
                internalPositionDs = from
                val progress =
                    if (internalDurationDs > 0) from.toFloat() / internalDurationDs else 0f
                binding.seekBarWaveform.progress = progress
                binding.seekBarPosition.text = from.formatDurationDs(true)
            }
        }

    /** The current duration, in deci-seconds (1/10th of a second). */
    var durationDs: Long
        get() = internalDurationDs
        set(value) {
            val to = max(value, 1)
            internalDurationDs = to
            isEnabled = value > 0
            binding.seekBarWaveform.isEnabled = isEnabled
            if (internalPositionDs > to) {
                internalPositionDs = to
            }
            val progress = if (to > 0) internalPositionDs.toFloat() / to else 0f
            binding.seekBarWaveform.progress = progress
            binding.seekBarDuration.text = value.formatDurationDs(false)
        }

    override fun onStartTrackingTouch() {
        isSeeking = true
        isActivated = true
    }

    override fun onProgressChanged(progress: Float, fromUser: Boolean) {
        if (fromUser) {
            val currentPos = (progress * internalDurationDs).toLong()
            binding.seekBarPosition.text = currentPos.formatDurationDs(true)
        }
    }

    override fun onStopTrackingTouch(progress: Float) {
        isSeeking = false
        isActivated = false
        val finalPos = (progress * internalDurationDs).toLong()
        internalPositionDs = finalPos
        listener?.onSeekConfirmed(finalPos)
    }

    /** A listener for SeekBar interactions. */
    interface Listener {
        fun onSeekConfirmed(positionDs: Long)
    }
}
