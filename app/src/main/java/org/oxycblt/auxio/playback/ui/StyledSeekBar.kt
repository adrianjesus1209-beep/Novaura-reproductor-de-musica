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
import com.google.android.material.slider.Slider
import kotlin.math.max
import org.oxycblt.auxio.databinding.ViewSeekBarBinding
import org.oxycblt.auxio.playback.formatDurationDs
import org.oxycblt.auxio.util.inflater

/**
 * A wrapper around [Slider] that shows position and duration values and sanitizes input to reduce
 * crashes from invalid values.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class StyledSeekBar
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    ForcedLTRFrameLayout(context, attrs, defStyleAttr),
    Slider.OnSliderTouchListener,
    Slider.OnChangeListener {
    private val binding = ViewSeekBarBinding.inflate(context.inflater, this, true)

    init {
        binding.seekBarSlider.addOnSliderTouchListener(this)
        binding.seekBarSlider.addOnChangeListener(this)
    }

    /** Enables/disables wavy active-track rendering to match playback state. */
    fun setWaveEnabled(enabled: Boolean) {
        binding.seekBarSlider.setWaveEnabled(enabled)
    }

    /**
     * Sets the source of the current audio level, in the range 0..1. The slider samples this on
     * each drawn frame rather than being pushed to.
     */
    fun setAudioLevelProvider(provider: (() -> Float)?) {
        binding.seekBarSlider.audioLevelProvider = provider
    }

    /** Sets the source of the current audio reactivity strength, from 0 to 1. */
    fun setAudioReactivityProvider(provider: (() -> Float)?) {
        binding.seekBarSlider.audioReactivityProvider = provider
    }

    /** Maintained for compatibility with online player calls. */
    fun setSongSeed(seed: Long) {
        // No-op for WavySlider
    }

    /** The current [Listener] attached to this instance. */
    var listener: Listener? = null

    /**
     * The current position, in deci-seconds (1/10th of a second). This is the current value of the
     * SeekBar and is indicated by the start TextView in the layout.
     */
    var positionDs: Long
        get() = binding.seekBarSlider.value.toLong()
        set(value) {
            val from = max(value, 0)
            if (!isActivated) {
                val clamped =
                    from
                        .toFloat()
                        .coerceIn(binding.seekBarSlider.valueFrom, binding.seekBarSlider.valueTo)
                binding.seekBarSlider.value = clamped
                binding.seekBarPosition.text = clamped.toLong().formatDurationDs(true)
            }
        }

    /**
     * The current duration, in deci-seconds (1/10th of a second). This is the end value of the
     * SeekBar and is indicated by the end TextView in the layout.
     */
    var durationDs: Long
        get() = binding.seekBarSlider.valueTo.toLong()
        set(value) {
            val to = max(value, 1)
            isEnabled = value > 0
            if (binding.seekBarSlider.value > to) {
                binding.seekBarSlider.value = to.toFloat()
            }
            binding.seekBarSlider.valueTo = to.toFloat()
            binding.seekBarDuration.text = value.formatDurationDs(false)
        }

    override fun onStartTrackingTouch(slider: Slider) {
        isActivated = true
    }

    override fun onStopTrackingTouch(slider: Slider) {
        isActivated = false
        listener?.onSeekConfirmed(slider.value.toLong())
    }

    override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
        binding.seekBarPosition.text = value.toLong().formatDurationDs(true)
    }

    /** A listener for SeekBar interactions. */
    interface Listener {
        fun onSeekConfirmed(positionDs: Long)
    }
}
