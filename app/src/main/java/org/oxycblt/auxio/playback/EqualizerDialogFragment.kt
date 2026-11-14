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
import javax.inject.Inject
import kotlin.math.roundToInt
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogEqualizerBinding
import org.oxycblt.auxio.playback.ui.VerticalEqColumnView

/**
 * A [DialogFragment] allowing the user to adjust the bands of the active audio session via
 * [EqualizerController], choose presets, and dial Bass Boost and Virtualizer effects.
 */
@AndroidEntryPoint
class EqualizerDialogFragment : DialogFragment() {
    @Inject lateinit var equalizerController: EqualizerController
    private val playbackModel: PlaybackViewModel by activityViewModels()

    private var _binding: DialogEqualizerBinding? = null
    private val binding
        get() = _binding!!

    private var currentPreset: Short = -1
    private var presetNames: List<String> = emptyList()
    private val desiredGains = mutableListOf<Short>()

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
        binding.equalizerEnable.setOnCheckedChangeListener(null)

        val snapshot = equalizerController.snapshot(playbackModel.currentAudioSessionId)
        if (!snapshot.attached || snapshot.bandCount <= 0) {
            setDisabledUi()
            return
        }

        presetNames = snapshot.presetNames
        currentPreset = snapshot.currentPreset
        desiredGains.clear()
        desiredGains.addAll(snapshot.gains)

        val columns = getColumns()
        val usableBands = minOf(snapshot.bandCount, columns.size)
        for (bandIndex in 0 until usableBands) {
            val defaultLevel = snapshot.gains.getOrElse(bandIndex) { 0 }
            val freqLabel = formatFreq(snapshot.centerFreqsMilliHz.getOrElse(bandIndex) { 0 })
            val col = columns[bandIndex]
            col.isVisible = true
            col.minLevel = snapshot.minLevel
            col.maxLevel = snapshot.maxLevel
            col.frequencyText = freqLabel
            col.level =
                if (snapshot.enabled) {
                    defaultLevel.toFloat().coerceIn(snapshot.minLevel, snapshot.maxLevel)
                } else {
                    0f
                }
            col.isEnabled = snapshot.enabled
            col.onLevelChangeListener = { value, fromUser ->
                if (fromUser) {
                    currentPreset = -1
                    updatePresetText()
                    setBandLevel(bandIndex, value)
                }
            }
        }

        // Hide columns that the device does not have
        for (i in usableBands until columns.size) {
            columns[i].isVisible = false
        }

        binding.equalizerNoSession.isVisible = false
        binding.equalizerEnable.isEnabled = true
        binding.equalizerReset.isEnabled = snapshot.enabled
        binding.equalizerPresetContainer.isEnabled = snapshot.enabled
        updatePresetText()
        binding.equalizerPresetContainer.setOnClickListener { showPresetMenu(it) }

        binding.equalizerKnobBass.title = getString(R.string.lbl_bass)
        binding.equalizerKnobBass.value = snapshot.bassStrength.toInt()
        binding.equalizerKnobBass.isEnabled = snapshot.enabled && snapshot.bassSupported
        binding.equalizerKnobBass.onValueChangedListener = { value, fromUser ->
            if (fromUser) {
                equalizerController.setBassStrength(value.toShort())
            }
        }

        binding.equalizerKnobVirtualizer.title = getString(R.string.lbl_virtualizer)
        binding.equalizerKnobVirtualizer.value = snapshot.virtualizerStrength.toInt()
        binding.equalizerKnobVirtualizer.isEnabled =
            snapshot.enabled && snapshot.virtualizerSupported
        binding.equalizerKnobVirtualizer.onValueChangedListener = { value, fromUser ->
            if (fromUser) {
                equalizerController.setVirtualizerStrength(value.toShort())
            }
        }

        // Set initial switch state before registering the change listener so initial binding never
        // re-triggers persistence/effect mutations.
        binding.equalizerEnable.isChecked = snapshot.enabled
        binding.equalizerEnable.setOnCheckedChangeListener { _, checked -> applyEnabled(checked) }
    }

    private fun setDisabledUi() {
        binding.equalizerEnable.setOnCheckedChangeListener(null)
        binding.equalizerEnable.isChecked = false
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
        val name =
            if (currentPreset >= 0) {
                presetNames.getOrNull(currentPreset.toInt())
            } else {
                null
            }
        b.equalizerPresetText.text = name ?: getString(R.string.lbl_custom)
    }

    private fun showPresetMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, -1, 0, getString(R.string.lbl_custom))
        presetNames.forEachIndexed { i, name -> popup.menu.add(0, i, i + 1, name) }

        popup.setOnMenuItemClickListener { item ->
            applyPreset(item.itemId.toShort())
            true
        }
        popup.show()
    }

    private fun applyPreset(presetIndex: Short) {
        currentPreset = presetIndex
        val updatedGains = equalizerController.applyPreset(presetIndex) ?: return
        desiredGains.clear()
        desiredGains.addAll(updatedGains)
        if (presetIndex >= 0) {
            getColumns().forEachIndexed { bandIndex, col ->
                if (bandIndex < updatedGains.size) {
                    col.level =
                        updatedGains[bandIndex].toFloat().coerceIn(col.minLevel, col.maxLevel)
                }
            }
        }
        updatePresetText()
    }

    private fun setBandLevel(band: Int, levelMillis: Float) {
        val clampedLevel = levelMillis.roundToInt().toShort()
        if (!equalizerController.setBandLevel(band, clampedLevel)) {
            return
        }
        if (band in desiredGains.indices) {
            desiredGains[band] = clampedLevel
        }
    }

    private fun applyEnabled(enabled: Boolean) {
        if (!equalizerController.setEnabled(enabled)) {
            return
        }
        val snapshot = equalizerController.snapshot(playbackModel.currentAudioSessionId)
        desiredGains.clear()
        desiredGains.addAll(snapshot.gains)
        getColumns().forEachIndexed { band, col ->
            col.isEnabled = enabled
            col.level =
                if (enabled) {
                    desiredGains
                        .getOrElse(band) { 0 }
                        .toFloat()
                        .coerceIn(col.minLevel, col.maxLevel)
                } else {
                    0f
                }
        }
        binding.equalizerReset.isEnabled = enabled
        binding.equalizerKnobBass.isEnabled = enabled && snapshot.bassSupported
        binding.equalizerKnobVirtualizer.isEnabled = enabled && snapshot.virtualizerSupported
        binding.equalizerPresetContainer.isEnabled = enabled
    }

    private fun reset() {
        val snapshot = equalizerController.reset() ?: return
        currentPreset = snapshot.currentPreset
        desiredGains.clear()
        desiredGains.addAll(snapshot.gains)
        getColumns().forEach { col -> col.level = 0f }
        binding.equalizerKnobBass.value = 0
        binding.equalizerKnobVirtualizer.value = 0
        updatePresetText()
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
}
