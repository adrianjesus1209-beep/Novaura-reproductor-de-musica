/*
 * Copyright (c) 2026 Auxio Project
 * AudioLevelProcessorTest.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.playback.ActionMode
import org.oxycblt.auxio.playback.PlaySong
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.playback.replaygain.ReplayGainMode
import org.oxycblt.auxio.playback.replaygain.ReplayGainPreAmp

/**
 * Tests for [AudioLevelProcessor].
 *
 * The claim this file exists to back up is the one made in the release notes: analysing the audio
 * does not modify the sound. That is a strong claim about a processor sitting in ExoPlayer's audio
 * chain, and it was previously backed by nothing but the word "passthrough" in a KDoc. Every test
 * here drives the real [AudioLevelProcessor] as a real media3 [AudioProcessor], with no mocks on
 * the audio path, and checks the actual bytes coming out the other side.
 *
 * @author Novaura
 */
class AudioLevelProcessorTest {

    @Test
    fun queueInput_outputIsIdenticalToInput() {
        val samples = pseudoRandomSamples(1024)
        val processor = newProcessor(AudioReactivity.NORMAL)

        assertArrayEquals(
            "The processor must not alter a single byte of the audio it is measuring",
            samples.asLeBytes(),
            process(processor, samples),
        )
    }

    @Test
    fun queueInput_outputIsIdenticalToInputWithLeadingOffset() {
        // ExoPlayer hands over buffers that may already be part-consumed. The processor reads with
        // absolute indices and writes with relative ones, so a non-zero position is the case most
        // likely to go quietly wrong.
        val samples = pseudoRandomSamples(1024)
        val processor = newProcessor(AudioReactivity.NORMAL)

        assertArrayEquals(
            "A buffer whose position is not 0 must still come out untouched",
            samples.asLeBytes(),
            process(processor, samples, leadingPadding = 24),
        )
    }

    @Test
    fun queueInput_extremeSamplesAreNotCorrupted() {
        // Full-scale positive and negative values are where a sign or shift mistake in the
        // little-endian helpers would show up as a click.
        val samples =
            ShortArray(512) { i ->
                if (i % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE
            }
        val processor = newProcessor(AudioReactivity.NORMAL)

        assertArrayEquals(
            "Full-scale samples must survive the analysis unmodified",
            samples.asLeBytes(),
            process(processor, samples),
        )
    }

    @Test
    fun queueInput_silenceProducesNoLevel() {
        val processor = newProcessor(AudioReactivity.NORMAL)
        process(processor, ShortArray(2048))

        assertEquals("Silence must not register as any kind of level", 0f, processor.level, 0f)
    }

    @Test
    fun queueInput_levelRisesWithLoudness() {
        val quiet = newProcessor(AudioReactivity.NORMAL)
        val loud = newProcessor(AudioReactivity.NORMAL)
        val samples = ShortArray(4096)

        // Same buffer length, only the amplitude differs, so any difference in the resulting level
        // comes from the measurement rather than from the smoothing window.
        for (i in samples.indices) {
            samples[i] = if (i % 2 == 0) 3000 else (-3000).toShort()
        }
        repeat(8) { process(quiet, samples) }

        for (i in samples.indices) {
            samples[i] = if (i % 2 == 0) 30000 else (-30000).toShort()
        }
        repeat(8) { process(loud, samples) }

        assertTrue("A loud buffer must produce a level", loud.level > quiet.level)
        assertTrue("A near-full-scale buffer must produce a high level", loud.level > 0.9f)
        assertTrue("The level must never leave the 0..1 range", loud.level <= 1f)
    }

    @Test
    fun queueInput_levelFallsBackToSilenceOnSilence() {
        val processor = newProcessor(AudioReactivity.NORMAL)
        val loud = ShortArray(4096) { if (it % 2 == 0) 30000 else (-30000).toShort() }

        repeat(8) { process(processor, loud) }
        assertTrue("Precondition: the buffer above must make some noise", processor.level > 0f)

        // Release is slower than attack, so this needs a few buffers of silence to visibly fall.
        repeat(24) { process(processor, ShortArray(4096)) }

        assertTrue(
            "Silence must bring the level back down, was ${processor.level}",
            processor.level < 0.01f,
        )
    }

    @Test
    fun queueInput_offModePassesAudioThroughWithoutAnalysing() {
        val samples = ShortArray(2048) { if (it % 2 == 0) 20000 else (-20000).toShort() }
        val processor = newProcessor(AudioReactivity.OFF)

        assertArrayEquals(
            "OFF must still forward the audio, it only skips the analysis",
            samples.asLeBytes(),
            process(processor, samples),
        )
        assertEquals("OFF must not produce a level", 0f, processor.level, 0f)
    }

    @Test
    fun resetLevel_dropsTheLevelImmediately() {
        val processor = newProcessor(AudioReactivity.NORMAL)
        val loud = ShortArray(4096) { if (it % 2 == 0) 30000 else (-30000).toShort() }
        repeat(8) { process(processor, loud) }

        assertTrue("Precondition: the buffer above must make some noise", processor.level > 0f)

        // This is what pausing relies on: without it the UI keeps pulsing over a stopped track.
        processor.resetLevel()

        assertEquals("Pausing must silence the level at once", 0f, processor.level, 0f)
    }

    @Test
    fun configure_rejectsNonPcm16Input() {
        val processor = AudioLevelProcessor(FakePlaybackSettings(AudioReactivity.NORMAL))

        try {
            processor.configure(AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_FLOAT))
            throw AssertionError("Expected float input to be rejected")
        } catch (expected: AudioProcessor.UnhandledAudioFormatException) {
            // Correct: the pipeline only ever feeds this processor 16-bit PCM, and guessing
            // otherwise would silently measure the wrong samples.
        }
    }

    @Test
    fun configure_acceptsPcm16Input() {
        val output =
            AudioLevelProcessor(FakePlaybackSettings(AudioReactivity.NORMAL))
                .configure(AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT))

        assertEquals("The sample rate must survive configuration", SAMPLE_RATE, output.sampleRate)
        assertEquals("The channel count must survive configuration", CHANNELS, output.channelCount)
        assertEquals(
            "The encoding must survive configuration",
            C.ENCODING_PCM_16BIT,
            output.encoding,
        )
    }

    // --- HELPERS ---

    /** Build a configured processor with the given reactivity already applied. */
    private fun newProcessor(reactivity: AudioReactivity) =
        AudioLevelProcessor(FakePlaybackSettings(reactivity)).apply {
            configure(AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT))
        }

    /**
     * Push [samples] through [processor] and return the bytes that came out.
     *
     * [leadingPadding] first writes junk at the front of the input buffer and then moves the
     * position past it, mimicking a buffer ExoPlayer has already part-read.
     */
    private fun process(
        processor: AudioLevelProcessor,
        samples: ShortArray,
        leadingPadding: Int = 0,
    ): ByteArray {
        val input =
            ByteBuffer.allocateDirect(leadingPadding + samples.size * Short.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
        repeat(leadingPadding) { input.put(FILLER.toByte()) }
        samples.forEach { input.putLeShort(it) }
        val end = input.position()
        input.position(leadingPadding)
        input.limit(end)

        processor.queueInput(input)

        val output = processor.output
        return ByteArray(output.remaining()).also { output.get(it) }
    }

    /** The same samples as [process] would return, laid out little-endian. */
    private fun ShortArray.asLeBytes() =
        ByteArray(size * Short.SIZE_BYTES).also { bytes ->
            ByteBuffer.wrap(bytes).let { out -> forEach { out.putLeShort(it) } }
        }

    /**
     * Write a little-endian [Short], spelled out byte by byte.
     *
     * Mirrors the equivalent helper in [AudioLevelProcessor]: `ByteBuffer.putLeShort` only exists
     * from Java 13 and is not in android.jar, so the unit-test classpath cannot see it either.
     */
    private fun ByteBuffer.putLeShort(short: Short) {
        put(short.toByte())
        put((short.toInt() shr 8).toByte())
    }

    /**
     * A deterministic spread of non-repeating sample values.
     *
     * A pattern is used rather than [java.util.Random] so a failure is always reproducible.
     */
    private fun pseudoRandomSamples(count: Int) =
        ShortArray(count) { ((it * 2_654_435_761L) shr 16).toShort() }

    private companion object {
        const val SAMPLE_RATE = 44_100

        /**
         * The processor treats the buffer as a flat run of shorts and never branches on the channel
         * count, so any non-zero value exercises the same code path. Declared locally rather than
         * read from [C] because the test only cares that the format is handed back untouched.
         */
        const val CHANNELS = 2

        const val FILLER = 0x7F
    }

    /**
     * A [PlaybackSettings] that only knows about [audioReactivity].
     *
     * [AudioLevelProcessor] touches nothing else, so everything it does not use fails loudly rather
     * than returning a plausible-looking value that hides a real dependency.
     */
    private class FakePlaybackSettings(override val audioReactivity: AudioReactivity) :
        PlaybackSettings {
        override val barAction: ActionMode
            get() = throw unused("barAction")

        override val headsetAutoplay: Boolean
            get() = throw unused("headsetAutoplay")

        override val replayGainMode: ReplayGainMode
            get() = throw unused("replayGainMode")

        override var replayGainPreAmp: ReplayGainPreAmp
            get() = throw unused("replayGainPreAmp")
            set(_) = throw unused("replayGainPreAmp")

        override val playInListWith: PlaySong
            get() = throw unused("playInListWith")

        override val inParentPlaybackMode: PlaySong?
            get() = throw unused("inParentPlaybackMode")

        override val keepShuffle: Boolean
            get() = throw unused("keepShuffle")

        override val rewindWithPrev: Boolean
            get() = throw unused("rewindWithPrev")

        override val pauseOnRepeat: Boolean
            get() = throw unused("pauseOnRepeat")

        override val rememberPause: Boolean
            get() = throw unused("rememberPause")

        override val exitOnTaskRemoval: Boolean
            get() = throw unused("exitOnTaskRemoval")

        override fun registerListener(listener: PlaybackSettings.Listener) = Unit

        override fun unregisterListener(listener: PlaybackSettings.Listener) = Unit

        private fun unused(name: String) =
            UnsupportedOperationException("AudioLevelProcessor should not read $name")
    }
}
