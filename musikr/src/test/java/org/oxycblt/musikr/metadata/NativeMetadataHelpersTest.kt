/*
 * Copyright (c) 2026 Auxio Project
 * NativeMetadataHelpersTest.kt is part of Auxio.
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
 
package org.oxycblt.musikr.metadata

import io.mockk.mockk
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.oxycblt.musikr.fs.File

class NativeMetadataHelpersTest {
    @Test
    fun nativeTagMap_accumulatesDuplicateTagValuesInsteadOfOverwriting() {
        val tagMap = NativeTagMap()
        tagMap.addID("TPE1", "Artist A")
        tagMap.addID("TPE1", listOf("Artist B", "Artist C"))
        tagMap.addCustom("Genre", "Rock")
        tagMap.addCustom("GENRE", listOf("Electronic"))
        tagMap.addCombined("TXXX", "Mood", listOf("Energetic"))
        tagMap.addCombined("TXXX", "mood", "Upbeat")

        val result = tagMap.getObject()
        assertEquals(listOf("Artist A", "Artist B", "Artist C"), result["TPE1"])
        assertEquals(listOf("Rock", "Electronic"), result["GENRE"])
        assertEquals(listOf("Energetic", "Upbeat"), result["TXXX:MOOD"])
    }

    @Test
    fun nativeInputStream_readBlockLoopsAcrossPartialChannelReads() {
        val payload = byteArrayOf(10, 20, 30, 40, 50, 60, 70)
        val channel = PartialReadFileChannel(payload, maxChunkBytes = 2)
        val stream = NativeInputStream(mockk<File>(relaxed = true), channel)

        val buf1 = ByteBuffer.allocate(5)
        assertEquals(5, stream.readBlock(buf1))
        assertArrayEquals(byteArrayOf(10, 20, 30, 40, 50), buf1.array())

        val buf2 = ByteBuffer.allocate(5)
        assertEquals(2, stream.readBlock(buf2))
        assertArrayEquals(byteArrayOf(60, 70), buf2.array().copyOf(2))

        val buf3 = ByteBuffer.allocate(4)
        assertEquals(-1, stream.readBlock(buf3))
    }

    private class PartialReadFileChannel(
        private val data: ByteArray,
        private val maxChunkBytes: Int,
    ) : FileChannel() {
        private var pos = 0

        override fun read(dst: ByteBuffer): Int {
            if (pos >= data.size) return -1
            val chunk = minOf(maxChunkBytes, minOf(dst.remaining(), data.size - pos))
            dst.put(data, pos, chunk)
            pos += chunk
            return chunk
        }

        override fun position(): Long = pos.toLong()

        override fun position(newPosition: Long): FileChannel {
            pos = newPosition.toInt().coerceIn(0, data.size)
            return this
        }

        override fun size(): Long = data.size.toLong()

        override fun read(dsts: Array<out ByteBuffer>?, offset: Int, length: Int): Long = 0

        override fun write(src: ByteBuffer?): Int = 0

        override fun write(srcs: Array<out ByteBuffer>?, offset: Int, length: Int): Long = 0

        override fun truncate(size: Long): FileChannel = this

        override fun force(metaData: Boolean) {}

        override fun transferTo(position: Long, count: Long, target: WritableByteChannel?): Long = 0

        override fun transferFrom(src: ReadableByteChannel?, position: Long, count: Long): Long = 0

        override fun read(dst: ByteBuffer?, position: Long): Int = 0

        override fun write(src: ByteBuffer?, position: Long): Int = 0

        override fun map(mode: MapMode?, position: Long, size: Long): MappedByteBuffer =
            throw UnsupportedOperationException()

        override fun lock(position: Long, size: Long, shared: Boolean): FileLock =
            throw UnsupportedOperationException()

        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = null

        override fun implCloseChannel() {}
    }
}
