/*
 * Copyright (c) 2026 Auxio Project
 * ThemeCustomizerDialog.kt is part of Auxio.
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
 
package org.oxycblt.auxio.theme

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.BuildConfig
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogThemeCustomizerBinding
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.ui.WallpaperHelper
import timber.log.Timber as L

/**
 * A full-screen [DialogFragment] that allows users to pick a custom wallpaper image from their
 * device and adjust blur and brightness in real time with hardware acceleration.
 */
@AndroidEntryPoint
class ThemeCustomizerDialog : DialogFragment() {

    @Inject lateinit var uiSettings: UISettings

    private var _binding: DialogThemeCustomizerBinding? = null
    private val binding
        get() = _binding!!

    private var pendingImageUri: Uri? = null
    private var previewBitmap: Bitmap? = null
    private var blurredPreviewBitmap: Bitmap? = null
    private var hasPromptedPicker = false
    private var previewLoadJob: Job? = null
    private var blurPreviewJob: Job? = null
    private var saveJob: Job? = null

    private val photoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                L.d("Selected custom wallpaper URI: $uri")
                pendingImageUri = uri
                loadPreviewFromUri(uri)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogThemeCustomizerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog
            ?.window
            ?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (savedInstanceState != null) {
            pendingImageUri =
                BundleCompat.getParcelable(
                    savedInstanceState,
                    KEY_PENDING_IMAGE_URI,
                    Uri::class.java,
                )
            hasPromptedPicker = savedInstanceState.getBoolean(KEY_HAS_PROMPTED_PICKER, false)
        }

        binding.btnBack.setOnClickListener { dismiss() }
        binding.btnCancel.setOnClickListener { dismiss() }

        binding.btnReset.setOnClickListener {
            L.d("Resetting custom wallpaper")
            uiSettings.clearCustomWallpaper()
            Toast.makeText(requireContext(), R.string.lbl_reset_wallpaper, Toast.LENGTH_SHORT)
                .show()
            dismiss()
        }

        binding.cardPreview.setOnClickListener {
            photoPickerLauncher.launch("image/*")
        }

        binding.btnSave.setOnClickListener {
            saveAndApplyWallpaper()
        }

        val currentBlur = uiSettings.customWallpaperBlur.toFloat().coerceIn(0f, 100f)
        val currentBrightness = uiSettings.customWallpaperBrightness.toFloat().coerceIn(-100f, 100f)

        binding.sliderBlur.value = currentBlur
        binding.sliderBrightness.value = currentBrightness

        binding.sliderBlur.addOnChangeListener { _, value, _ ->
            updateBlur(value)
        }

        binding.sliderBrightness.addOnChangeListener { _, value, _ ->
            updateBrightness(value)
        }

        updateBlur(currentBlur)
        updateBrightness(currentBrightness)

        val wallpaperFile = uiSettings.getCustomWallpaperFile()
        val chosenUri = pendingImageUri
        if (chosenUri != null) {
            loadPreviewFromUri(chosenUri)
        } else if (uiSettings.customWallpaperEnabled && wallpaperFile.exists()) {
            loadPreviewFromFile(wallpaperFile)
        } else if (savedInstanceState == null && !hasPromptedPicker) {
            // Automatically launch photo picker once to prompt user on first open
            hasPromptedPicker = true
            photoPickerLauncher.launch("image/*")
        }
    }

    private fun updateBlur(value: Float) {
        val currentBinding = _binding ?: return
        val source = previewBitmap ?: return
        val blurPercent = value.toInt().coerceIn(0, 100)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            WallpaperHelper.applyRenderEffectBlur(currentBinding.ivPreview, blurPercent)
            return
        }
        if (blurPercent <= 0) {
            blurredPreviewBitmap?.let { old ->
                currentBinding.ivPreview.setImageBitmap(source)
                old.recycle()
                blurredPreviewBitmap = null
            }
                ?: run {
                    currentBinding.ivPreview.setImageBitmap(source)
                }
            return
        }
        blurPreviewJob?.cancel()
        blurPreviewJob =
            viewLifecycleOwner.lifecycleScope.launch {
                val blurred =
                    WallpaperHelper.applyBlurToPreviewBitmap(
                        source,
                        blurPercent,
                        currentBinding.ivPreview,
                    )
                val activeBinding =
                    _binding
                        ?: run {
                            blurred?.recycle()
                            return@launch
                        }
                if (blurred != null) {
                    activeBinding.ivPreview.setImageBitmap(blurred)
                    val previous = blurredPreviewBitmap
                    blurredPreviewBitmap = blurred
                    if (previous != null && previous !== blurred && previous !== source) {
                        previous.recycle()
                    }
                }
            }
    }

    private fun updateBrightness(value: Float) {
        val scrimAlpha = WallpaperHelper.calculateScrimAlpha(value.toInt())
        binding.viewPreviewScrim.setBackgroundColor(Color.BLACK)
        binding.viewPreviewScrim.alpha = scrimAlpha
    }

    private fun loadPreviewFromUri(uri: Uri) {
        previewLoadJob?.cancel()
        previewLoadJob =
            viewLifecycleOwner.lifecycleScope.launch {
                val ctx = context ?: return@launch
                val bitmap = WallpaperHelper.decodeSampledUri(ctx, uri, 1080)
                val activeBinding =
                    _binding
                        ?: run {
                            bitmap?.recycle()
                            return@launch
                        }
                if (bitmap != null) {
                    setPreviewBitmap(activeBinding, bitmap)
                }
            }
    }

    private fun loadPreviewFromFile(file: File) {
        previewLoadJob?.cancel()
        previewLoadJob =
            viewLifecycleOwner.lifecycleScope.launch {
                val bitmap =
                    withContext(Dispatchers.IO) { WallpaperHelper.decodeSampledFile(file, 1080) }
                val activeBinding =
                    _binding
                        ?: run {
                            bitmap?.recycle()
                            return@launch
                        }
                if (bitmap != null) {
                    setPreviewBitmap(activeBinding, bitmap)
                }
            }
    }

    private fun setPreviewBitmap(activeBinding: DialogThemeCustomizerBinding, bitmap: Bitmap) {
        activeBinding.ivPreview.setImageDrawable(null)
        blurredPreviewBitmap?.let { oldBlur ->
            if (oldBlur !== previewBitmap) {
                oldBlur.recycle()
            }
            blurredPreviewBitmap = null
        }
        previewBitmap?.recycle()
        previewBitmap = bitmap
        activeBinding.ivPreview.setImageBitmap(bitmap)
        activeBinding.layoutEmptyPlaceholder.visibility = View.GONE
        updateBlur(activeBinding.sliderBlur.value)
        updateBrightness(activeBinding.sliderBrightness.value)
    }

    private fun saveAndApplyWallpaper() {
        val currentBinding = _binding ?: return
        currentBinding.btnSave.isEnabled = false
        val uri = pendingImageUri
        val blur = currentBinding.sliderBlur.value.toInt()
        val brightness = currentBinding.sliderBrightness.value.toInt()

        saveJob?.cancel()
        saveJob =
            viewLifecycleOwner.lifecycleScope.launch {
                if (uri != null) {
                    val saved =
                        withContext(Dispatchers.IO) { uiSettings.saveCustomWallpaperFromUri(uri) }
                    if (!saved) {
                        L.e("Failed to save wallpaper from URI")
                    }
                }

                val wallpaperFile = uiSettings.getCustomWallpaperFile()
                if (wallpaperFile.exists()) {
                    uiSettings.setCustomWallpaper(
                        enabled = true,
                        blur = blur,
                        brightness = brightness,
                    )
                    context?.let { ctx ->
                        Toast.makeText(ctx, R.string.lbl_wallpaper_saved, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    _binding?.btnSave?.isEnabled = true
                }
                if (isAdded) {
                    dismiss()
                }
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingImageUri?.let { outState.putParcelable(KEY_PENDING_IMAGE_URI, it) }
        outState.putBoolean(KEY_HAS_PROMPTED_PICKER, hasPromptedPicker)
    }

    override fun onDestroyView() {
        previewLoadJob?.cancel()
        previewLoadJob = null
        blurPreviewJob?.cancel()
        blurPreviewJob = null
        saveJob?.cancel()
        saveJob = null
        _binding?.ivPreview?.setImageDrawable(null)
        blurredPreviewBitmap?.let { oldBlur ->
            if (oldBlur !== previewBitmap) {
                oldBlur.recycle()
            }
        }
        blurredPreviewBitmap = null
        previewBitmap?.recycle()
        previewBitmap = null
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val KEY_PENDING_IMAGE_URI = BuildConfig.APPLICATION_ID + ".theme.PENDING_IMAGE_URI"
        const val KEY_HAS_PROMPTED_PICKER =
            BuildConfig.APPLICATION_ID + ".theme.HAS_PROMPTED_PICKER"
    }
}
