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
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
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
import androidx.fragment.app.DialogFragment
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogThemeCustomizerBinding
import org.oxycblt.auxio.ui.UISettings
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
        if (uiSettings.customWallpaperEnabled && wallpaperFile.exists()) {
            loadPreviewFromFile(wallpaperFile)
        } else {
            // Automatically launch photo picker to prompt user on first open
            photoPickerLauncher.launch("image/*")
        }
    }

    private fun updateBlur(value: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (value > 0f) {
                val radius = (value / 100f * 50f).coerceAtLeast(1f)
                binding.ivPreview.setRenderEffect(
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                )
            } else {
                binding.ivPreview.setRenderEffect(null)
            }
        }
    }

    private fun updateBrightness(value: Float) {
        // Brightness from -100 to +100
        // -100: dark scrim alpha ~0.90
        // 0: base dark scrim alpha ~0.35 (optimal contrast for white text/icons)
        // +100: scrim alpha ~0.05
        val factor = -value / 100f
        val scrimAlpha = (0.35f + factor * 0.45f).coerceIn(0.05f, 0.95f)
        binding.viewPreviewScrim.setBackgroundColor(Color.BLACK)
        binding.viewPreviewScrim.alpha = scrimAlpha
    }

    private fun loadPreviewFromUri(uri: Uri) {
        try {
            val context = requireContext()
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }

            var sampleSize = 1
            val maxDimension = 1080
            while (
                options.outWidth / sampleSize > maxDimension ||
                    options.outHeight / sampleSize > maxDimension
            ) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap =
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, decodeOptions)
                }

            if (bitmap != null) {
                previewBitmap?.recycle()
                previewBitmap = bitmap
                binding.ivPreview.setImageBitmap(bitmap)
                binding.layoutEmptyPlaceholder.visibility = View.GONE
                updateBlur(binding.sliderBlur.value)
                updateBrightness(binding.sliderBrightness.value)
            }
        } catch (e: Exception) {
            L.e("Failed to decode preview image from URI: $e")
        }
    }

    private fun loadPreviewFromFile(file: File) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)

            var sampleSize = 1
            val maxDimension = 1080
            while (
                options.outWidth / sampleSize > maxDimension ||
                    options.outHeight / sampleSize > maxDimension
            ) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)

            if (bitmap != null) {
                previewBitmap?.recycle()
                previewBitmap = bitmap
                binding.ivPreview.setImageBitmap(bitmap)
                binding.layoutEmptyPlaceholder.visibility = View.GONE
                updateBlur(binding.sliderBlur.value)
                updateBrightness(binding.sliderBrightness.value)
            }
        } catch (e: Exception) {
            L.e("Failed to decode preview image from file: $e")
        }
    }

    private fun saveAndApplyWallpaper() {
        val uri = pendingImageUri
        if (uri != null) {
            val saved = uiSettings.saveCustomWallpaperFromUri(uri)
            if (!saved) {
                L.e("Failed to save wallpaper from URI")
            }
        }

        val wallpaperFile = uiSettings.getCustomWallpaperFile()
        if (wallpaperFile.exists()) {
            val blur = binding.sliderBlur.value.toInt()
            val brightness = binding.sliderBrightness.value.toInt()
            uiSettings.setCustomWallpaper(enabled = true, blur = blur, brightness = brightness)
            Toast.makeText(requireContext(), R.string.lbl_wallpaper_saved, Toast.LENGTH_SHORT)
                .show()
        }
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        previewBitmap?.recycle()
        previewBitmap = null
        _binding = null
    }
}
