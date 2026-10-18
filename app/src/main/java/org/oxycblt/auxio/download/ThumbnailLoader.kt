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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.oxycblt.auxio.R
import org.oxycblt.auxio.image.coil.BlurTransformation

object ThumbnailLoader {
    private val cache = LruCache<String, Bitmap>(60)
    private val blurCache = LruCache<String, Bitmap>(30)
    private val client = OkHttpClient()

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

        val cached = cache.get(url)
        val cachedBlur = blurCache.get(url)

        if (cached != null) {
            imageView.setImageBitmap(cached)
            if (blurView != null) {
                if (cachedBlur != null) {
                    blurView.setImageBitmap(cachedBlur)
                } else {
                    CoroutineScope(Dispatchers.IO).launch {
                        val blurred = BlurTransformation.blur(cached, radius = 22, sampling = 0.2f)
                        blurCache.put(url, blurred)
                        withContext(Dispatchers.Main) {
                            if (blurView.tag == url) {
                                blurView.setImageBitmap(blurred)
                            }
                        }
                    }
                }
            }
            return
        }

        imageView.setImageResource(R.drawable.ic_song_24)
        blurView?.setImageDrawable(null)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    val bytes = resp.body?.bytes()
                    if (bytes != null) {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            cache.put(url, bitmap)
                            val blurred =
                                BlurTransformation.blur(bitmap, radius = 22, sampling = 0.2f)
                            blurCache.put(url, blurred)
                            withContext(Dispatchers.Main) {
                                if (imageView.tag == url) {
                                    imageView.setImageBitmap(bitmap)
                                }
                                if (blurView?.tag == url) {
                                    blurView.setImageBitmap(blurred)
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
