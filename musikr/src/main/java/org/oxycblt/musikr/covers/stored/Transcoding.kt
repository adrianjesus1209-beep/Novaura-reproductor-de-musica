/*
 * Copyright (c) 2025 Auxio Project
 * Transcoding.kt is part of Auxio.
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
 
package org.oxycblt.musikr.covers.stored

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** An interface for transforming in-memory cover data into a different format for storage. */
interface Transcoding {
    /**
     * A tag to append to the file name to indicate the transcoding format used, such as a file
     * extension or additional qualifier.
     *
     * This should allow the cover to be uniquely identified in storage, and shouldn't collide with
     * other [Transcoding] implementations.
     */
    val tag: String

    /**
     * Transcode the given cover data into a different format and write it to the output stream.
     *
     * You can assume that all code ran here is in a critical section, and that you are the only one
     * with access to this [OutputStream] right now.
     *
     * @param data The cover data to transcode.
     * @param output The [OutputStream] to write the transcoded data to.
     */
    fun transcodeInto(data: ByteArray, output: OutputStream)
}

/**
 * A [Transcoding] implementation that does not transcode the cover data at all.
 *
 * This is useful for when the cover data is already in the desired format, or when the time/quality
 * tradeoff of transcoding is not worth it. Note that this may mean that large or malformed data may
 * be written to [CoverStorage] and yield bad results when loading the resulting covers.
 */
object NoTranscoding : Transcoding {
    override val tag = ".img"

    override fun transcodeInto(data: ByteArray, output: OutputStream) {
        output.write(data)
    }
}

/**
 * A [Transcoding] implementation that compresses the cover data into a specific format, size, and
 * quality.
 *
 * This is useful if you want to standardize the covers to a specific format and minimize the size
 * of the cover data to save space.
 *
 * @param format The [Bitmap.CompressFormat] to use to compress the cover data.
 * @param resolution The resolution to use for the cover data.
 * @param quality The quality to use for the cover data, from 0 to 100.
 */
class Compress(
    private val format: Bitmap.CompressFormat,
    private val resolution: Int,
    private val quality: Int,
) : Transcoding {
    override val tag = "_${resolution}x${quality}.${format.name.lowercase()}"

    override fun transcodeInto(data: ByteArray, output: OutputStream) {
        val targetSize = resolution.coerceAtLeast(1)
        val options =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(data, 0, data.size, this)
                inSampleSize = calculateInSampleSize(targetSize)
                inJustDecodeBounds = false
            }
        val decoded =
            requireNotNull(BitmapFactory.decodeByteArray(data, 0, data.size, options)) {
                "Failed to decode bitmap"
            }
        var toCompress = decoded
        try {
            val maxDim = max(decoded.width, decoded.height)
            if (maxDim > targetSize && decoded.width > 0 && decoded.height > 0) {
                val scale = targetSize.toFloat() / maxDim.toFloat()
                val scaledW = (decoded.width * scale).roundToInt().coerceAtLeast(1)
                val scaledH = (decoded.height * scale).roundToInt().coerceAtLeast(1)
                toCompress = Bitmap.createScaledBitmap(decoded, scaledW, scaledH, true)
            }
            toCompress.compress(format, quality, output)
        } finally {
            if (toCompress !== decoded && !toCompress.isRecycled) {
                toCompress.recycle()
            }
            if (!decoded.isRecycled) {
                decoded.recycle()
            }
        }
    }

    private fun BitmapFactory.Options.calculateInSampleSize(size: Int): Int {
        var inSampleSize = 1
        val height = outHeight
        val width = outWidth
        if (size <= 0 || height <= 0 || width <= 0) {
            return 1
        }
        while ((height / inSampleSize) > size * 2 || (width / inSampleSize) > size * 2) {
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }
}
