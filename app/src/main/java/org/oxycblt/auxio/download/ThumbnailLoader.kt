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
        val cachedBlur = blurCache.get(url)

        if (cached != null) {
            imageView.setImageBitmap(cached)
            if (blurView != null) {
                if (cachedBlur != null) {
                    blurView.setImageBitmap(cachedBlur)
                } else {
                    val blurViewRef = WeakReference(blurView)
                    scope.launch {
                        val blurred = BlurTransformation.blur(cached, radius = 22, sampling = 0.2f)
                        blurCache.put(url, blurred)
                        withContext(Dispatchers.Main) {
                            val targetBlur = blurViewRef.get()
                            if (targetBlur != null && targetBlur.tag == url) {
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
            try {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val bytes = resp.body?.bytes()
                    if (bytes != null) {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            memoryCache.put(url, bitmap)
                            val blurred =
                                BlurTransformation.blur(bitmap, radius = 22, sampling = 0.2f)
                            blurCache.put(url, blurred)
                            withContext(Dispatchers.Main) {
                                val targetImage = imageViewRef.get()
                                if (targetImage != null && targetImage.tag == url) {
                                    targetImage.setImageBitmap(bitmap)
                                }
                                val targetBlur = blurViewRef?.get()
                                if (targetBlur != null && targetBlur.tag == url) {
                                    targetBlur.setImageBitmap(blurred)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore loading failure, placeholder remains
            }
        }
    }
}
