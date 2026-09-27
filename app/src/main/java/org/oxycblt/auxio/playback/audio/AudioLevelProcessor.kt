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
@OptIn(markerClass = [UnstableApi::class])
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

    private var sampleRate = 0

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
        // The pipeline caches isActive, so nudge it into re-evaluating.
        flush()
    }

    // --- OVERRIDES ---

    /**
     * Deliberately not overriding [isActive]. Returning false here would make the pipeline drop
     * this processor, and it only re-adds processors when it re-runs configure, which a settings
     * change mid-song would not trigger. Staying in the chain and skipping just the analysis makes
     * the setting apply immediately, at the cost of one buffer copy while disabled.
     */
    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - pos
        val buffer = replaceOutputBuffer(size)

        if (!isEnabled) {
            // Defensive: the pipeline normally skips us entirely, but if it ever calls through,
            // still hand the samples over untouched rather than emitting an empty buffer.
            buffer.put(inputBuffer)
            inputBuffer.position(limit)
            buffer.flip()
            return
        }

        // Single pass: analyze while copying. No allocation, no intermediate collection.
        var sumSquares = 0L
        for (i in pos until limit step BYTES_PER_SAMPLE) {
            val sample = inputBuffer.getLeShort(i).toInt()
            sumSquares += sample.toLong() * sample
            // Passthrough: the exact same sample goes back out.
            buffer.putLeShort(sample.toShort())
        }

        inputBuffer.position(limit)
        buffer.flip()

        val sampleCount = (limit - pos) / BYTES_PER_SAMPLE
        if (sampleCount <= 0) return

        val rms = sqrt(sumSquares.toDouble() / sampleCount).toFloat() / MAX_ABSOLUTE
        // Music rarely exceeds 0.5 RMS, so a square root expands the quiet end into the visible
        // range. Without it normal tracks would only ever move the UI a few percent.
        val target = sqrt(rms.coerceIn(0f, 1f))
        smoothed += (target - smoothed) * smoothingCoefficient(sampleCount, target)
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
     * derived from how much audio this buffer actually represents instead of being a fixed constant
     * that would behave differently per device.
     */
    private fun smoothingCoefficient(sampleCount: Int, target: Float): Float {
        if (sampleRate <= 0) return if (target > smoothed) ATTACK else RELEASE

        val seconds = sampleCount.toFloat() / sampleRate
        val tau = if (target > smoothed) ATTACK_TAU_SECONDS else RELEASE_TAU_SECONDS
        return 1f - exp(-seconds / tau)
    }

    /**
     * Always read a little-endian [Short] from the [ByteBuffer] at the given index, without
     * depending on the buffer's current byte order.
     */
    private fun ByteBuffer.getLeShort(at: Int) =
        get(at + 1).toInt().shl(8).or(get(at).toInt().and(0xFF)).toShort()

    /** Always write a little-endian [Short] to the [ByteBuffer]. */
    private fun ByteBuffer.putLeShort(short: Short) {
        put(short.toByte())
        put(short.toInt().shr(8).toByte())
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val MAX_ABSOLUTE = 32768f

        /** Rise quickly so percussion and transients register. */
        const val ATTACK_TAU_SECONDS = 0.015f

        /** Fall slowly so the UI settles rather than strobing between frames. */
        const val RELEASE_TAU_SECONDS = 0.28f

        /** Fallbacks used only if the sample rate is somehow unknown. */
        const val ATTACK = 0.5f
        const val RELEASE = 0.08f
    }
}
