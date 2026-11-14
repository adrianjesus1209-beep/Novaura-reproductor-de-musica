/*
 * Copyright (c) 2026 Auxio Project
 * AudioLevelProcessor.kt is part of Auxio.
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

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.sqrt
import org.oxycblt.auxio.playback.PlaybackSettings

/**
 * A strictly [passthrough][BaseAudioProcessor] [AudioProcessor] that measures how loud the audio
 * actually is, so the UI can react to the music instead of animating blindly.
 *
 * The output is byte-for-byte identical to the input: samples are read for analysis and written
 * back unchanged in the same pass. Nothing about the sound is modified.
 *
 * This sits on ExoPlayer's audio thread, so [queueInput] must not allocate, must not block, and
 * must do a single pass. Results are published through a single [level] field, which the UI samples
 * from its own render callback. There is deliberately no Flow or coroutine here: a buffer arrives
 * every few milliseconds, and turning that into emissions would be pure overhead for a value the UI
 * only reads once per drawn frame.
 *
 * @author Novaura
 */
@OptIn(UnstableApi::class)
@Singleton
class AudioLevelProcessor @Inject constructor(private val playbackSettings: PlaybackSettings) :
    BaseAudioProcessor(), PlaybackSettings.Listener {
    /**
     * The most recent smoothed output level, in the range 0..1, with fast attack and slow release
     * so that transients stay visible but the UI settles instead of flickering.
     *
     * Written from the audio thread, read from the main thread. A `float` write is atomic on the
     * JVM and on ARM, so no additional synchronization is required.
     */
    @Volatile
    var level: Float = 0f
        private set

    /** The current user preference. Volatile so the UI can read it without a listener. */
    @Volatile
    var reactivity: AudioReactivity = AudioReactivity.NORMAL
        private set

    /** The strength of [reactivity], from 0 to 1. */
    val strength: Float
        get() = reactivity.strength

    /**
     * Whether to measure the audio. Mirrors [reactivity] so the pipeline can skip this processor
     * entirely when the user has opted out.
     */
    @Volatile private var isEnabled: Boolean = true

    @Volatile private var sampleRate = 0
    @Volatile private var channelCount = 1
    @Volatile private var configuredEncoding = C.ENCODING_PCM_16BIT

    /**
     * The smoothing state that the next buffer will build on.
     *
     * Volatile for the same reason as [level]: [resetLevel] clears this from the main thread when
     * playback stops, while the audio thread is reading and writing it. Without the guarantee, the
     * audio thread can miss the reset and carry the previous track's peak forward instead of
     * starting from silence.
     */
    @Volatile private var smoothed = 0f

    init {
        applyReactivity(playbackSettings.audioReactivity)
        playbackSettings.registerListener(this)
    }

    override fun onAudioReactivityChanged() {
        applyReactivity(playbackSettings.audioReactivity)
    }

    private fun applyReactivity(new: AudioReactivity) {
        reactivity = new
        val enabled = new.isEnabled
        if (isEnabled == enabled) {
            return
        }
        isEnabled = enabled
        if (!enabled) {
            resetLevel()
        }
    }

    // --- OVERRIDES ---

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (
            encoding != C.ENCODING_PCM_16BIT &&
                encoding != C.ENCODING_PCM_24BIT &&
                encoding != C.ENCODING_PCM_32BIT &&
                encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        configuredEncoding = encoding
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - pos
        val buffer = replaceOutputBuffer(size)

        if (!isEnabled) {
            buffer.put(inputBuffer)
            inputBuffer.position(limit)
            buffer.flip()
            return
        }

        val bytesPerSample: Int
        var sumSquaresNorm = 0.0
        when (configuredEncoding) {
            C.ENCODING_PCM_16BIT -> {
                bytesPerSample = 2
                for (i in pos until limit step 2) {
                    val sample = inputBuffer.getLeShort(i)
                    val norm = sample / MAX_ABSOLUTE_16
                    sumSquaresNorm += norm * norm
                    buffer.putLeShort(sample)
                }
            }
            C.ENCODING_PCM_24BIT -> {
                bytesPerSample = 3
                for (i in pos until limit step 3) {
                    val sample = inputBuffer.getLeInt24(i)
                    val norm = sample / MAX_ABSOLUTE_24
                    sumSquaresNorm += norm * norm
                    buffer.putLeInt24(sample)
                }
            }
            C.ENCODING_PCM_32BIT -> {
                bytesPerSample = 4
                for (i in pos until limit step 4) {
                    val sample = inputBuffer.getLeInt32(i)
                    val norm = sample / MAX_ABSOLUTE_32
                    sumSquaresNorm += norm * norm
                    buffer.putLeInt32(sample)
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                bytesPerSample = 4
                for (i in pos until limit step 4) {
                    val sample = Float.fromBits(inputBuffer.getLeInt32(i))
                    val norm = sample.toDouble().coerceIn(-1.0, 1.0)
                    sumSquaresNorm += norm * norm
                    buffer.putLeInt32(sample.toRawBits())
                }
            }
            else -> {
                buffer.put(inputBuffer)
                inputBuffer.position(limit)
                buffer.flip()
                return
            }
        }

        inputBuffer.position(limit)
        buffer.flip()

        val sampleCount = size / bytesPerSample
        val channels = channelCount.coerceAtLeast(1)
        val frameCount = sampleCount / channels
        if (sampleCount <= 0 || frameCount <= 0) return

        val rms = sqrt(sumSquaresNorm / sampleCount).toFloat()
        // Music rarely exceeds 0.5 RMS, so a square root expands the quiet end into the visible
        // range. Without it normal tracks would only ever move the UI a few percent.
        val target = sqrt(rms.coerceIn(0f, 1f))
        smoothed += (target - smoothed) * smoothingCoefficient(frameCount, target)
        level = smoothed
    }

    /**
     * [BaseAudioProcessor.flush] is final, so the reset hooks go here instead. ExoPlayer flushes
     * the audio pipeline on seeks and track changes, which is exactly when the level should drop
     * rather than stay frozen at the previous track's peak.
     */
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        resetLevel()
    }

    /**
     * Drop the level straight back to silence.
     *
     * Pausing does not flush the audio pipeline, so without this the last level would be held
     * indefinitely and the UI would keep pulsing over a stopped track. Named to avoid clashing with
     * [BaseAudioProcessor.reset], which reconfigures the processor rather than clearing our
     * smoothing state.
     */
    fun resetLevel() {
        smoothed = 0f
        level = 0f
    }

    // --- HELPERS ---

    /**
     * Frame-rate independent smoothing. Buffer sizes vary with the decoder, so the coefficient is
     * derived from how much audio duration [frameCount] frames actually represent instead of
     * confusing raw interleaved samples with time frames.
     */
    private fun smoothingCoefficient(frameCount: Int, target: Float): Float {
        if (sampleRate <= 0) return if (target > smoothed) ATTACK else RELEASE

        val seconds = frameCount.toFloat() / sampleRate
        val tau = if (target > smoothed) ATTACK_TAU_SECONDS else RELEASE_TAU_SECONDS
        return 1f - exp(-seconds / tau)
    }

    /** Always read a little-endian [Short] from the [ByteBuffer] at [at]. */
    private fun ByteBuffer.getLeShort(at: Int) =
        get(at + 1).toInt().shl(8).or(get(at).toInt().and(0xFF)).toShort()

    /** Always write a little-endian [Short] to the [ByteBuffer]. */
    private fun ByteBuffer.putLeShort(short: Short) {
        put(short.toByte())
        put(short.toInt().shr(8).toByte())
    }

    private fun ByteBuffer.getLeInt24(at: Int): Int {
        val b0 = get(at).toInt() and 0xFF
        val b1 = get(at + 1).toInt() and 0xFF
        val b2 = get(at + 2).toInt()
        return (b2 shl 16) or (b1 shl 8) or b0
    }

    private fun ByteBuffer.putLeInt24(value: Int) {
        put((value and 0xFF).toByte())
        put(((value ushr 8) and 0xFF).toByte())
        put(((value shr 16) and 0xFF).toByte())
    }

    private fun ByteBuffer.getLeInt32(at: Int): Int =
        (get(at).toInt() and 0xFF) or
            ((get(at + 1).toInt() and 0xFF) shl 8) or
            ((get(at + 2).toInt() and 0xFF) shl 16) or
            (get(at + 3).toInt() shl 24)

    private fun ByteBuffer.putLeInt32(value: Int) {
        put((value and 0xFF).toByte())
        put(((value ushr 8) and 0xFF).toByte())
        put(((value ushr 16) and 0xFF).toByte())
        put(((value shr 24) and 0xFF).toByte())
    }

    private companion object {
        const val MAX_ABSOLUTE_16 = 32768.0
        const val MAX_ABSOLUTE_24 = 8388608.0
        const val MAX_ABSOLUTE_32 = 2147483648.0

        /** Rise quickly so percussion and transients register. */
        const val ATTACK_TAU_SECONDS = 0.015f

        /** Fall slowly so the UI settles rather than strobing between frames. */
        const val RELEASE_TAU_SECONDS = 0.28f

        /** Fallbacks used only if the sample rate is somehow unknown. */
        const val ATTACK = 0.5f
        const val RELEASE = 0.08f
    }
}
