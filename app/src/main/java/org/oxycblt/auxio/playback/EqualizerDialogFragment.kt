/*
 * Copyright (c) 2026 Novaura Project
 * EqualizerDialogFragment.kt is part of Novaura.
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

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.audiofx.Equalizer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import kotlin.math.roundToInt
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogEqualizerBinding
import org.oxycblt.auxio.databinding.ItemEqualizerBandBinding
import timber.log.Timber as L

/**
 * A [DialogFragment] allowing the user to adjust the bands of the active audio session via the
 * device equalizer, themed to match the current app theme.
 */
@AndroidEntryPoint
class EqualizerDialogFragment : DialogFragment() {
    private val playbackModel: PlaybackViewModel by activityViewModels()

    private var _binding: DialogEqualizerBinding? = null
    private val binding get() = _binding!!
    private val bandBindings = mutableListOf<ItemEqualizerBandBinding>()
    private val desiredGains = mutableListOf<Short>()
    private var equalizer: Equalizer? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogEqualizerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog?.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setupEqualizer()
    }

    private fun setupEqualizer() {
        binding.equalizerReset.setOnClickListener { reset() }
        binding.equalizerEnable.setOnCheckedChangeListener { _, checked -> applyEnabled(checked) }

        val sessionId = playbackModel.currentAudioSessionId
        if (sessionId == null) {
            L.w("Skipping equalizer setup, no active audio session")
            binding.equalizerEnable.isEnabled = false
            binding.equalizerReset.isEnabled = false
            binding.equalizerNoSession.isVisible = true
            return
        }

        val newEqualizer =
            try {
                Equalizer(0, sessionId)
            } catch (e: RuntimeException) {
                L.w("Failed to attach equalizer to audio session", e)
                null
            }
        if (newEqualizer == null) {
            binding.equalizerEnable.isEnabled = false
            binding.equalizerReset.isEnabled = false
            binding.equalizerNoSession.isVisible = true
            return
        }
        equalizer = newEqualizer

        val profile = EqualizerSettings.load(requireContext())
        // Keep the device equalizer in sync with the persisted switch state.
        try {
            newEqualizer.enabled = profile.enabled
        } catch (e: RuntimeException) {
            L.w("Failed to set equalizer state", e)
        }
        val bandCount = newEqualizer.numberOfBands
        val bandRange = newEqualizer.bandLevelRange
        val minLevel = bandRange[0].toFloat()
        val maxLevel = bandRange[1].toFloat()
        desiredGains.clear()

        for (bandIndex in 0 until bandCount.toInt()) {
            val savedGain = profile.gains.getOrNull(bandIndex)
            val defaultLevel =
                if (savedGain != null) {
                    savedGain
                } else {
                    newEqualizer.getBandLevel(bandIndex.toShort())
                }
            desiredGains.add(defaultLevel)

            val freqLabel = formatFreq(newEqualizer.getCenterFreq(bandIndex.toShort()))

            val row = ItemEqualizerBandBinding.inflate(layoutInflater, binding.equalizerBands, true)
            row.bandFreq.text = freqLabel
            row.bandSlider.contentDescription =
                getString(R.string.desc_equalizer_band, bandIndex + 1, freqLabel)
            row.bandSlider.valueFrom = minLevel
            row.bandSlider.valueTo = maxLevel
            row.bandSlider.stepSize = 50f
            // Only surface the saved gains while the equalizer is enabled; otherwise show a flat
            // response so the dialog does not look modified while disabled.
            row.bandSlider.value =
                if (profile.enabled) defaultLevel.toFloat().coerceIn(minLevel, maxLevel) else 0f
            row.bandSlider.isEnabled = profile.enabled
            row.bandSlider.addOnChangeListener { _, value, fromUser ->
                if (fromUser) setBandLevel(bandIndex, value)
            }
            bandBindings.add(row)
        }

        binding.equalizerEnable.isChecked = profile.enabled
    }

    private fun setBandLevel(band: Int, levelMillis: Float) {
        val eq = equalizer ?: return
        desiredGains[band] = levelMillis.roundToInt().toShort()
        try {
            eq.setBandLevel(band.toShort(), desiredGains[band])
        } catch (e: RuntimeException) {
            L.w("Failed to set equalizer band level", e)
            return
        }
        persist()
    }

    private fun applyEnabled(enabled: Boolean) {
        val eq = equalizer ?: return
        try {
            eq.enabled = enabled
        } catch (e: RuntimeException) {
            L.w("Failed to set equalizer state", e)
            return
        }
        bandBindings.forEachIndexed { band, row ->
            if (enabled) {
                row.bandSlider.value =
                    desiredGains[band].toFloat().coerceIn(row.bandSlider.valueFrom, row.bandSlider.valueTo)
                try {
                    eq.setBandLevel(band.toShort(), desiredGains[band])
                } catch (e: RuntimeException) {
                    L.w("Failed to set equalizer band level", e)
                }
            } else {
                row.bandSlider.value = 0f
            }
            row.bandSlider.isEnabled = enabled
        }
        persist()
    }

    private fun reset() {
        val eq = equalizer ?: return
        bandBindings.forEachIndexed { band, row ->
            desiredGains[band] = 0
            try {
                eq.setBandLevel(band.toShort(), 0)
            } catch (e: RuntimeException) {
                L.w("Failed to reset equalizer band", e)
            }
            row.bandSlider.value = 0f
        }
        persist()
    }

    private fun persist() {
        val eq = equalizer ?: return
        EqualizerSettings.save(requireContext(), eq.enabled, desiredGains)
    }

    private fun formatFreq(milliHz: Int): String {
        val hz = milliHz / 1000
        return if (hz >= 1000) {
            val khz = hz / 1000f
            if (khz == khz.roundToInt().toFloat()) {
                "${hz / 1000} kHz"
            } else {
                String.format(Locale.US, "%.1f kHz", khz)
            }
        } else {
            "$hz Hz"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        bandBindings.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        equalizer?.release()
        equalizer = null
    }
}