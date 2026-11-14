/*
 * Copyright (c) 2026 Auxio Project
 * ThumbnailLoader.kt is part of Auxio.
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
 
package org.oxycblt.auxio.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.oxycblt.auxio.R
import org.oxycblt.auxio.image.coil.BlurTransformation

object ThumbnailLoader {
    private val memoryCache =
        object : LruCache<String, Bitmap>(16 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                (value.allocationByteCount / 1024).coerceAtLeast(1)
        }

    private val blurCache =
        object : LruCache<String, Bitmap>(8 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                (value.allocationByteCount / 1024).coerceAtLeast(1)
        }

    private val client
        get() = NewPipeDownloader.getInstance().client

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun load(imageView: ImageView, url: String?) {
        load(imageView, null, url)
    }

    fun load(imageView: ImageView, blurView: ImageView?, url: String?) {
        if (url.isNullOrEmpty()) {
            imageView.tag = null
            imageView.setImageResource(R.drawable.ic_song_24)
            blurView?.tag = null
            blurView?.setImageDrawable(null)
            return
        }

        imageView.tag = url
        blurView?.tag = url

        val cached = memoryCache.get(url)
        val cachedBlur = if (blurView != null) blurCache.get(url) else null

        if (cached != null && !cached.isRecycled) {
            imageView.setImageBitmap(cached)
            if (blurView != null) {
                if (cachedBlur != null && !cachedBlur.isRecycled) {
                    blurView.setImageBitmap(cachedBlur)
                } else {
                    val blurViewRef = WeakReference(blurView)
                    scope.launch {
                        val blurred = BlurTransformation.blur(cached, radius = 22, sampling = 0.2f)
                        if (!blurred.isRecycled) {
                            blurCache.put(url, blurred)
                        }
                        withContext(Dispatchers.Main) {
                            val targetBlur = blurViewRef.get()
                            if (
                                targetBlur != null && targetBlur.tag == url && !blurred.isRecycled
                            ) {
                                targetBlur.setImageBitmap(blurred)
                            }
                        }
                    }
                }
            }
            return
        }

        imageView.setImageResource(R.drawable.ic_song_24)
        blurView?.setImageDrawable(null)

        val imageViewRef = WeakReference(imageView)
        val blurViewRef = blurView?.let { WeakReference(it) }

        scope.launch {
            val bitmap = fetchBitmapSync(url, maxSizePx = 512) ?: return@launch
            if (bitmap.isRecycled) return@launch
            val blurred =
                if (blurViewRef != null) {
                    BlurTransformation.blur(bitmap, radius = 22, sampling = 0.2f).also {
                        if (!it.isRecycled) blurCache.put(url, it)
                    }
                } else {
                    null
                }
            withContext(Dispatchers.Main) {
                val targetImage = imageViewRef.get()
                if (targetImage != null && targetImage.tag == url && !bitmap.isRecycled) {
                    targetImage.setImageBitmap(bitmap)
                }
                val targetBlur = blurViewRef?.get()
                if (
                    targetBlur != null &&
                        targetBlur.tag == url &&
                        blurred != null &&
                        !blurred.isRecycled
                ) {
                    targetBlur.setImageBitmap(blurred)
                }
            }
        }
    }

    fun fetchBitmapSync(url: String?, maxSizePx: Int = 512): Bitmap? {
        if (url.isNullOrBlank()) return null
        memoryCache
            .get(url)
            ?.takeIf { !it.isRecycled }
            ?.let {
                return it
            }
        return try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body ?: return@use null
                val contentLength = body.contentLength()
                if (contentLength > MAX_IMAGE_BYTES) return@use null
                val source = body.source()
                source.request(MAX_IMAGE_BYTES + 1L)
                if (source.buffer.size > MAX_IMAGE_BYTES) return@use null
                val bytes = body.bytes()
                val decoded = decodeSampledBitmap(bytes, maxSizePx)
                if (decoded != null && !decoded.isRecycled) {
                    memoryCache.put(url, decoded)
                }
                decoded
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSampledBitmap(bytes: ByteArray, maxSizePx: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var inSampleSize = 1
        if (bounds.outHeight > maxSizePx || bounds.outWidth > maxSizePx) {
            val halfHeight = bounds.outHeight / 2
            val halfWidth = bounds.outWidth / 2
            while (
                halfHeight / inSampleSize >= maxSizePx && halfWidth / inSampleSize >= maxSizePx
            ) {
                inSampleSize *= 2
            }
        }
        val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private const val MAX_IMAGE_BYTES = 8L * 1024L * 1024L
}
