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
 * A seekbar that wraps [WaveformSeekBarView] and displays audio waveform progress with position and
 * duration timestamps underneath, matching the waveform design from Image 3.
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

    /** Enables/disables waveform active-track rendering and animation to match playback state. */
    fun setWaveEnabled(enabled: Boolean) {
        binding.seekBarWaveform.isPlaying = enabled
    }

    /**
     * Sets the source of the current audio level, in the range 0..1. The view samples this on each
     * drawn frame.
     */
    fun setAudioLevelProvider(provider: (() -> Float)?) {
        binding.seekBarWaveform.audioLevelProvider = provider
    }

    /** Sets the source of the current audio reactivity strength, from 0 to 1. */
    fun setAudioReactivityProvider(provider: (() -> Float)?) {
        // Maintained for compatibility
    }

    /** Updates the waveform profile deterministically for the current song. */
    fun setSongSeed(seed: Long) {
        binding.seekBarWaveform.setSongSeed(seed)
    }

    /** The current [Listener] attached to this instance. */
    var listener: Listener? = null

    /**
     * The current position, in deci-seconds (1/10th of a second). This is the current value of the
     * SeekBar and is indicated by the start TextView in the layout.
     */
    var positionDs: Long
        get() = internalPositionDs
        set(value) {
            val from = max(value, 0)
            if (!isSeeking) {
                internalPositionDs = from
                val progress =
                    if (internalDurationDs > 0) from.toFloat() / internalDurationDs.toFloat()
                    else 0f
                binding.seekBarWaveform.setProgress(
                    progress,
                    from * 100L,
                    internalDurationDs * 100L,
                )
                binding.seekBarPosition.text = from.formatDurationDs(true)
            }
        }

    /**
     * The current duration, in deci-seconds (1/10th of a second). This is the end value of the
     * SeekBar and is indicated by the end TextView in the layout.
     */
    var durationDs: Long
        get() = internalDurationDs
        set(value) {
            val to = max(value, 1)
            internalDurationDs = to
            isEnabled = value > 0
            binding.seekBarWaveform.isEnabled = isEnabled
            val progress = if (to > 0) internalPositionDs.toFloat() / to.toFloat() else 0f
            binding.seekBarWaveform.setProgress(
                progress,
                internalPositionDs * 100L,
                to * 100L,
            )
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
