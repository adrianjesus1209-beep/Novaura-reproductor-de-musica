/*
 * Copyright (c) 2026 Auxio Project
 * WallpaperHelper.kt is part of Auxio.
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
 
package org.oxycblt.auxio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.google.android.material.R as MR
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.util.getAttrColorCompat
import timber.log.Timber as L

/**
 * Shared helpers for decoding, orienting, blurring, and rendering custom wallpapers safely off the
 * main thread across both primary and secondary screens.
 */
object WallpaperHelper {
    private const val MAX_WALLPAPER_DIMENSION = 1920
    private const val LEGACY_BLUR_MAX_DIMENSION = 320

    data class PreparedWallpaper(val bitmap: Bitmap, val blurPercent: Int, val scrimAlpha: Float)

    fun calculateScrimAlpha(brightness: Int): Float {
        val factor = -brightness.toFloat() / 100f
        return (0.35f + factor * 0.45f).coerceIn(0.05f, 0.95f)
    }

    suspend fun loadAndPrepare(
        uiSettings: UISettings,
        maxDimension: Int = MAX_WALLPAPER_DIMENSION,
    ): PreparedWallpaper? {
        if (!uiSettings.customWallpaperEnabled) return null
        val file = uiSettings.getCustomWallpaperFile()
        if (!file.exists()) return null
        val blur = uiSettings.customWallpaperBlur.coerceIn(0, 100)
        val brightness = uiSettings.customWallpaperBrightness.coerceIn(-100, 100)
        val decoded =
            withContext(Dispatchers.IO) { decodeSampledFile(file, maxDimension) } ?: return null
        val prepared =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && blur > 0) {
                withContext(Dispatchers.Default) {
                    val radius = (blur * 25f / 100f).roundToInt().coerceIn(1, 25)
                    val downscaled = downscaleForBlur(decoded, LEGACY_BLUR_MAX_DIMENSION)
                    if (downscaled !== decoded) {
                        decoded.recycle()
                    }
                    stackBlur(downscaled, radius)
                }
            } else {
                decoded
            }
        return PreparedWallpaper(prepared, blur, calculateScrimAlpha(brightness))
    }

    suspend fun decodeSampledUri(
        context: Context,
        sourceUri: Uri,
        maxDimension: Int = MAX_WALLPAPER_DIMENSION,
    ): Bitmap? =
        withContext(Dispatchers.IO) { decodeSampledUriSync(context, sourceUri, maxDimension) }

    fun decodeSampledUriSync(
        context: Context,
        sourceUri: Uri,
        maxDimension: Int = MAX_WALLPAPER_DIMENSION,
    ): Bitmap? {
        return try {
            val orientation =
                context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                    ExifInterface(stream)
                        .getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL,
                        )
                } ?: ExifInterface.ORIENTATION_NORMAL

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options =
                BitmapFactory.Options().apply {
                    inSampleSize =
                        calculateSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
                }
            val raw =
                context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                } ?: return null

            applyExifOrientation(raw, orientation)
        } catch (e: Exception) {
            L.e(e, "Failed to decode sampled wallpaper URI")
            null
        }
    }

    fun decodeSampledFile(file: File, maxDimension: Int = MAX_WALLPAPER_DIMENSION): Bitmap? {
        return try {
            val orientation =
                ExifInterface(file.absolutePath)
                    .getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options =
                BitmapFactory.Options().apply {
                    inSampleSize =
                        calculateSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
                }
            val raw = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            applyExifOrientation(raw, orientation)
        } catch (e: Exception) {
            L.e(e, "Failed to decode sampled wallpaper file")
            null
        }
    }

    suspend fun applyBlurToPreviewBitmap(
        source: Bitmap,
        blurPercent: Int,
        imageView: ImageView,
    ): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyRenderEffectBlur(imageView, blurPercent)
            return null
        }
        if (blurPercent <= 0) {
            return null
        }
        return withContext(Dispatchers.Default) {
            val radius = (blurPercent * 25f / 100f).roundToInt().coerceIn(1, 25)
            val copy = downscaleForBlur(source, LEGACY_BLUR_MAX_DIMENSION)
            val mutableCopy =
                if (copy === source || !copy.isMutable) {
                    val copied = copy.copy(Bitmap.Config.ARGB_8888, true)
                    if (copy !== source && copied !== copy) {
                        copy.recycle()
                    }
                    copied
                } else {
                    copy
                }
            stackBlur(mutableCopy, radius)
        }
    }

    fun applyRenderEffectBlur(imageView: ImageView, blurPercent: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (blurPercent > 0) {
                val radius = (blurPercent / 100f * 50f).coerceAtLeast(1f)
                imageView.setRenderEffect(
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                )
            } else {
                imageView.setRenderEffect(null)
            }
        }
    }

    fun clearWallpaperViews(imageView: ImageView?, scrimView: View?) {
        if (imageView != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                imageView.setRenderEffect(null)
            }
            imageView.setImageDrawable(null)
            imageView.alpha = 0f
        }
        if (scrimView != null) {
            scrimView.alpha = 0f
        }
    }

    fun bindPreparedWallpaper(
        imageView: ImageView,
        scrimView: View,
        prepared: PreparedWallpaper,
    ) {
        imageView.setImageBitmap(prepared.bitmap)
        imageView.alpha = 1f
        applyRenderEffectBlur(imageView, prepared.blurPercent)
        scrimView.setBackgroundColor(Color.BLACK)
        scrimView.alpha = prepared.scrimAlpha
    }

    fun hasActiveWallpaper(uiSettings: UISettings): Boolean =
        uiSettings.customWallpaperEnabled && uiSettings.getCustomWallpaperFile().exists()

    fun applySecondaryWallpaperBackground(
        rootView: View?,
        uiSettings: UISettings,
        vararg surfaceViewsToClear: View?,
    ) {
        val target = rootView ?: return
        val context = target.context
        if (hasActiveWallpaper(uiSettings)) {
            target.setBackgroundColor(
                ContextCompat.getColor(context, R.color.ui_secondary_wallpaper_scrim)
            )
            surfaceViewsToClear.forEach { it?.setBackgroundColor(Color.TRANSPARENT) }
        } else {
            val surfaceColor = context.getAttrColorCompat(MR.attr.colorSurface).defaultColor
            target.setBackgroundColor(surfaceColor)
            surfaceViewsToClear.forEach { it?.setBackgroundColor(surfaceColor) }
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun downscaleForBlur(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val longest = max(w, h)
        if (longest <= maxDim) {
            return if (bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.isMutable) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, true)
            }
        }
        val scale = maxDim.toFloat() / longest.toFloat()
        val dstW = max(1, (w * scale).roundToInt())
        val dstH = max(1, (h * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(bitmap, dstW, dstH, true)
        return if (scaled.config == Bitmap.Config.ARGB_8888 && scaled.isMutable) {
            scaled
        } else {
            val mutable = scaled.copy(Bitmap.Config.ARGB_8888, true)
            if (mutable !== scaled) {
                scaled.recycle()
            }
            mutable
        }
    }

    fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val transformed =
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap) {
            bitmap.recycle()
        }
        return transformed
    }

    /** Fast integer StackBlur (O(w * h)) for Android 7–10 devices lacking RenderEffect. */
    private fun stackBlur(bitmap: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        val vmin = IntArray(max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (i in dv.indices) {
            dv[i] = i / divsum
        }

        var yi = 0
        var yw = 0
        val stack = Array(div) { IntArray(3) }
        val r1 = radius + 1

        for (y in 0 until h) {
            var rsum = 0
            var gsum = 0
            var bsum = 0
            var routsum = 0
            var goutsum = 0
            var boutsum = 0
            var rinsum = 0
            var ginsum = 0
            var binsum = 0

            for (i in -radius..radius) {
                val p = pix[yi + min(wm, max(i, 0))]
                val sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                val rbs = r1 - abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }

            var stackpointer = radius
            for (x in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                val stackstart = stackpointer - radius + div
                var sir = stack[stackstart % div]
                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (y == 0) {
                    vmin[x] = min(x + radius + 1, wm)
                }
                val p = pix[yw + vmin[x]]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]
                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]
                yi++
            }
            yw += w
        }

        for (x in 0 until w) {
            var rsum = 0
            var gsum = 0
            var bsum = 0
            var routsum = 0
            var goutsum = 0
            var boutsum = 0
            var rinsum = 0
            var ginsum = 0
            var binsum = 0
            var yp = -radius * w

            for (i in -radius..radius) {
                yi = max(0, yp) + x
                val sir = stack[i + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                val rbs = r1 - abs(i)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (i < hm) {
                    yp += w
                }
            }

            yi = x
            var stackpointer = radius
            for (y in 0 until h) {
                pix[yi] =
                    (0xff000000.toInt() and pix[yi]) or
                        (dv[rsum] shl 16) or
                        (dv[gsum] shl 8) or
                        dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                val stackstart = stackpointer - radius + div
                var sir = stack[stackstart % div]
                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (x == 0) {
                    vmin[y] = min(y + r1, hm) * w
                }
                val p = x + vmin[y]
                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]
                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]
                yi += w
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }
}
