/*
 * Copyright (c) 2026 Auxio Project
 * ReplayGainAudioProcessorTest.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.replaygain

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import io.mockk.every
import io.mockk.mockk
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.ReplayGainAdjustment

class ReplayGainAudioProcessorTest {
    @Test
    fun queueInput_pcmFloatHandlesNanSamplesWithoutCorruptingOutput() {
        val settings =
            mockk<PlaybackSettings>(relaxed = true) {
                every { replayGainMode } returns ReplayGainMode.TRACK
                every { replayGainPreAmp } returns ReplayGainPreAmp(with = 6f, without = 0f)
            }
        val song =
            mockk<Song>(relaxed = true) {
                every { replayGainAdjustment } returns
                    ReplayGainAdjustment(track = -6f, album = null)
            }
        val manager =
            mockk<PlaybackStateManager>(relaxed = true) {
                every { currentSong } returns song
            }

        val processor = ReplayGainAudioProcessor(manager, settings)
        processor.onNewPlayback(null, listOf(song), 0, false)
        val fmt = processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT))
        assertEquals(C.ENCODING_PCM_FLOAT, fmt.encoding)

        // Even with non-unity extreme gain + NaN input, output floats remain finite and clamped.
        every { song.replayGainAdjustment } returns ReplayGainAdjustment(track = 50f, album = null)
        processor.onIndexMoved(0)

        val buf = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(Float.NaN.toRawBits())
        buf.putInt(0.9f.toRawBits())
        buf.flip()

        processor.queueInput(buf)
        val out = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        val s0 = Float.fromBits(out.getInt())
        val s1 = Float.fromBits(out.getInt())
        assertTrue(s0.isFinite())
        assertEquals(0f, s0, 0f)
        assertEquals(1f, s1, 0.001f)
    }
}
