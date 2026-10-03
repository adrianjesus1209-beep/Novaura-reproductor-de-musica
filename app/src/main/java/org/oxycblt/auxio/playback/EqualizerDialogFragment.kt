/*
 * Copyright (c) 2026 Auxio Project
 * EqualizerDialogFragment.kt is part of Auxio.
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
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import kotlin.math.roundToInt
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogEqualizerBinding
import org.oxycblt.auxio.playback.ui.VerticalEqColumnView
import timber.log.Timber as L

/**
 * A [DialogFragment] allowing the user to adjust the bands of the active audio session via the
 * device equalizer, choose presets, and dial Bass Boost and Virtualizer effects, matching the
 * modern visual style.
 */
@AndroidEntryPoint
class EqualizerDialogFragment : DialogFragment() {
    private val playbackModel: PlaybackViewModel by activityViewModels()

    private var _binding: DialogEqualizerBinding? = null
    private val binding
        get() = _binding!!

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    private val desiredGains = mutableListOf<Short>()
    private var currentPreset: Short = -1
    private var bassStrength: Short = 0
    private var virtualizerStrength: Short = 0

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
        dialog
            ?.window
            ?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        dialog?.window?.setDimAmount(0.9f)
        setupEqualizer()
    }

    private fun getColumns(): List<VerticalEqColumnView> {
        val b = _binding ?: return emptyList()
        return listOf(
            b.equalizerCol0,
            b.equalizerCol1,
            b.equalizerCol2,
            b.equalizerCol3,
            b.equalizerCol4,
        )
    }

    private fun setupEqualizer() {
        binding.equalizerBack.setOnClickListener { dismiss() }
        binding.equalizerReset.setOnClickListener { reset() }
        binding.equalizerEnable.setOnCheckedChangeListener { _, checked -> applyEnabled(checked) }

        val sessionId = playbackModel.currentAudioSessionId
        if (sessionId == null) {
            L.w("Skipping equalizer setup, no active audio session")
            setDisabledUi()
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
            setDisabledUi()
            return
        }
        equalizer = newEqualizer

        bassBoost =
            try {
                BassBoost(0, sessionId)
            } catch (e: RuntimeException) {
                L.w("Failed to attach BassBoost", e)
                null
            }

        virtualizer =
            try {
                Virtualizer(0, sessionId)
            } catch (e: RuntimeException) {
                L.w("Failed to attach Virtualizer", e)
                null
            }

        val profile = EqualizerSettings.load(requireContext())
        currentPreset = profile.preset
        bassStrength = profile.bassStrength
        virtualizerStrength = profile.virtualizerStrength

        try {
            newEqualizer.enabled = profile.enabled
            bassBoost?.enabled = profile.enabled
            virtualizer?.enabled = profile.enabled
            if (bassBoost?.strengthSupported == true) {
                bassBoost?.setStrength(bassStrength)
            }
            if (virtualizer?.strengthSupported == true) {
                virtualizer?.setStrength(virtualizerStrength)
            }
        } catch (e: RuntimeException) {
            L.w("Failed to set equalizer/effects state", e)
        }

        val bandCount = newEqualizer.numberOfBands.toInt()
        val bandRange = newEqualizer.bandLevelRange
        val minLevel = bandRange[0].toFloat()
        val maxLevel = bandRange[1].toFloat()
        desiredGains.clear()

        val columns = getColumns()
        for (bandIndex in 0 until minOf(bandCount, columns.size)) {
            val savedGain = profile.gains.getOrNull(bandIndex)
            val defaultLevel = savedGain ?: newEqualizer.getBandLevel(bandIndex.toShort())
            desiredGains.add(defaultLevel)

            val freqLabel = formatFreq(newEqualizer.getCenterFreq(bandIndex.toShort()))
            val col = columns[bandIndex]
            col.minLevel = minLevel
            col.maxLevel = maxLevel
            col.frequencyText = freqLabel
            col.level =
                if (profile.enabled) defaultLevel.toFloat().coerceIn(minLevel, maxLevel) else 0f
            col.isEnabled = profile.enabled
            col.onLevelChangeListener = { value, fromUser ->
                if (fromUser) {
                    currentPreset = -1
                    updatePresetText()
                    setBandLevel(bandIndex, value)
                }
            }
        }

        // Hide columns that the device does not have
        for (i in bandCount until columns.size) {
            columns[i].isVisible = false
        }

        updatePresetText()
        binding.equalizerPresetContainer.setOnClickListener { showPresetMenu(it) }

        binding.equalizerKnobBass.title = getString(R.string.lbl_bass)
        binding.equalizerKnobBass.value = bassStrength.toInt()
        binding.equalizerKnobBass.isEnabled = profile.enabled
        binding.equalizerKnobBass.onValueChangedListener = { value, fromUser ->
            if (fromUser) {
                bassStrength = value.toShort()
                bassBoost?.let { bb ->
                    if (bb.strengthSupported) {
                        try {
                            bb.setStrength(bassStrength)
                        } catch (e: RuntimeException) {
                            L.w("Failed to set bass boost", e)
                        }
                    }
                }
                persist()
            }
        }

        binding.equalizerKnobVirtualizer.title = getString(R.string.lbl_virtualizer)
        binding.equalizerKnobVirtualizer.value = virtualizerStrength.toInt()
        binding.equalizerKnobVirtualizer.isEnabled = profile.enabled
        binding.equalizerKnobVirtualizer.onValueChangedListener = { value, fromUser ->
            if (fromUser) {
                virtualizerStrength = value.toShort()
                virtualizer?.let { vz ->
                    if (vz.strengthSupported) {
                        try {
                            vz.setStrength(virtualizerStrength)
                        } catch (e: RuntimeException) {
                            L.w("Failed to set virtualizer", e)
                        }
                    }
                }
                persist()
            }
        }

        binding.equalizerEnable.isChecked = profile.enabled
    }

    private fun setDisabledUi() {
        binding.equalizerEnable.isEnabled = false
        binding.equalizerReset.isEnabled = false
        binding.equalizerPresetContainer.isEnabled = false
        binding.equalizerNoSession.isVisible = true
        getColumns().forEach { it.isEnabled = false }
        binding.equalizerKnobBass.isEnabled = false
        binding.equalizerKnobVirtualizer.isEnabled = false
    }

    private fun updatePresetText() {
        val b = _binding ?: return
        val eq = equalizer
        if (currentPreset < 0 || eq == null) {
            b.equalizerPresetText.text = getString(R.string.lbl_custom)
        } else {
            try {
                b.equalizerPresetText.text = eq.getPresetName(currentPreset)
            } catch (e: RuntimeException) {
                b.equalizerPresetText.text = getString(R.string.lbl_custom)
            }
        }
    }

    private fun showPresetMenu(anchor: View) {
        val eq = equalizer ?: return
        val popup = PopupMenu(requireContext(), anchor)
        val numPresets =
            try {
                eq.numberOfPresets.toInt()
            } catch (e: RuntimeException) {
                0
            }

        popup.menu.add(0, -1, 0, getString(R.string.lbl_custom))
        for (i in 0 until numPresets) {
            val name =
                try {
                    eq.getPresetName(i.toShort())
                } catch (e: RuntimeException) {
                    "Preset $i"
                }
            popup.menu.add(0, i, i + 1, name)
        }

        popup.setOnMenuItemClickListener { item ->
            val presetIndex = item.itemId.toShort()
            applyPreset(presetIndex)
            true
        }
        popup.show()
    }

    private fun applyPreset(presetIndex: Short) {
        val eq = equalizer ?: return
        currentPreset = presetIndex
        if (presetIndex >= 0) {
            try {
                eq.usePreset(presetIndex)
            } catch (e: RuntimeException) {
                L.w("Failed to use preset $presetIndex", e)
            }
            val columns = getColumns()
            columns.forEachIndexed { bandIndex, col ->
                val level =
                    try {
                        eq.getBandLevel(bandIndex.toShort())
                    } catch (e: RuntimeException) {
                        0.toShort()
                    }
                if (bandIndex < desiredGains.size) {
                    desiredGains[bandIndex] = level
                }
                col.level = level.toFloat()
            }
        }
        updatePresetText()
        persist()
    }

    private fun setBandLevel(band: Int, levelMillis: Float) {
        val eq = equalizer ?: return
        if (band < desiredGains.size) {
            desiredGains[band] = levelMillis.roundToInt().toShort()
        }
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
            bassBoost?.enabled = enabled
            virtualizer?.enabled = enabled
        } catch (e: RuntimeException) {
            L.w("Failed to set equalizer state", e)
            return
        }
        getColumns().forEachIndexed { band, col ->
            col.isEnabled = enabled
            if (enabled) {
                val gain = desiredGains.getOrNull(band) ?: 0
                col.level = gain.toFloat().coerceIn(col.minLevel, col.maxLevel)
                try {
                    eq.setBandLevel(band.toShort(), gain)
                } catch (e: RuntimeException) {
                    L.w("Failed to set equalizer band level", e)
                }
            } else {
                col.level = 0f
            }
        }
        binding.equalizerKnobBass.isEnabled = enabled
        binding.equalizerKnobVirtualizer.isEnabled = enabled
        binding.equalizerPresetContainer.isEnabled = enabled
        persist()
    }

    private fun reset() {
        val eq = equalizer ?: return
        getColumns().forEachIndexed { band, col ->
            if (band < desiredGains.size) {
                desiredGains[band] = 0
            }
            try {
                eq.setBandLevel(band.toShort(), 0)
            } catch (e: RuntimeException) {
                L.w("Failed to reset equalizer band", e)
            }
            col.level = 0f
        }
        currentPreset = -1
        bassStrength = 0
        virtualizerStrength = 0
        binding.equalizerKnobBass.value = 0
        binding.equalizerKnobVirtualizer.value = 0
        try {
            if (bassBoost?.strengthSupported == true) bassBoost?.setStrength(0)
            if (virtualizer?.strengthSupported == true) virtualizer?.setStrength(0)
        } catch (e: RuntimeException) {
            L.w("Failed to reset audiofx", e)
        }
        updatePresetText()
        persist()
    }

    private fun persist() {
        val eq = equalizer ?: return
        EqualizerSettings.save(
            requireContext(),
            eq.enabled,
            desiredGains,
            currentPreset,
            bassStrength,
            virtualizerStrength,
        )
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
    }

    override fun onDestroy() {
        super.onDestroy()
        equalizer?.release()
        equalizer = null
        bassBoost?.release()
        bassBoost = null
        virtualizer?.release()
        virtualizer = null
    }
}
