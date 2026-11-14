/*
 * Copyright (c) 2022 Auxio Project
 * ReplayGainAudioProcessor.kt is part of Auxio.
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

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import javax.inject.Inject
import kotlin.math.pow
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * An [AudioProcessor] that handles ReplayGain values and their amplification of the audio stream.
 * Instead of leveraging the volume attribute like other implementations, this system manipulates
 * the bitstream itself to modify the volume, which allows the use of positive ReplayGain values.
 *
 * Note: This audio processor must be attached to a respective [Player] instance as a
 * [Player.Listener] to function properly.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@OptIn(UnstableApi::class)
class ReplayGainAudioProcessor
@Inject
constructor(
    private val playbackManager: PlaybackStateManager,
    private val playbackSettings: PlaybackSettings,
) : BaseAudioProcessor(), PlaybackStateManager.Listener, PlaybackSettings.Listener {
    @Volatile private var volume = 1f
    @Volatile private var configuredEncoding = C.ENCODING_PCM_16BIT

    fun attach() {
        playbackManager.addListener(this)
        playbackSettings.registerListener(this)
    }

    /** Remove this instance from the components required for it to function correctly. */
    fun release() {
        playbackManager.removeListener(this)
        playbackSettings.unregisterListener(this)
    }

    // --- OVERRIDES ---

    override fun onIndexMoved(index: Int) {
        L.d("Index moved, updating current song")
        applyReplayGain(playbackManager.currentSong)
    }

    override fun onQueueChanged(queue: List<Song>, index: Int, change: QueueChange) {
        // Other types of queue changes preserve the current song.
        if (change.type == QueueChange.Type.SONG) {
            applyReplayGain(playbackManager.currentSong)
        }
    }

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean,
    ) {
        L.d("New playback started, updating playback information")
        applyReplayGain(playbackManager.currentSong)
    }

    override fun onReplayGainSettingsChanged() {
        // ReplayGain config changed, we need to set it up again.
        applyReplayGain(playbackManager.currentSong)
    }

    // --- REPLAYGAIN PARSING ---

    /**
     * Updates the volume adjustment based on the given [Format].
     *
     * @param song The [Format] of the currently playing track, or null if nothing is playing.
     */
    private fun applyReplayGain(song: Song?) {
        if (song == null) {
            L.d("Nothing playing, disabling adjustment")
            volume = 1f
            return
        }

        L.d("Applying ReplayGain adjustment for $song")

        val gain = song.replayGainAdjustment
        val preAmp = playbackSettings.replayGainPreAmp

        // ReplayGain is configurable, so determine what to do based off of the mode.
        val resolvedAdjustment =
            when (playbackSettings.replayGainMode) {
                // User wants no adjustment.
                ReplayGainMode.OFF -> {
                    L.d("ReplayGain is off")
                    null
                }
                // User wants track gain to be preferred. Default to album gain only if
                // there is no track gain.
                ReplayGainMode.TRACK -> {
                    L.d("Using track strategy")
                    gain.track ?: gain.album
                }
                // User wants album gain to be preferred. Default to track gain only if
                // here is no album gain.
                ReplayGainMode.ALBUM -> {
                    L.d("Using album strategy")
                    gain.album ?: gain.track
                }
                // User wants album gain to be used when in an album, track gain otherwise.
                ReplayGainMode.DYNAMIC -> {
                    L.d("Using dynamic strategy")
                    gain.album?.takeIf {
                        playbackManager.parent is Album &&
                            playbackManager.currentSong?.album == playbackManager.parent
                    } ?: gain.track
                }
            }

        val amplifiedAdjustment =
            if (resolvedAdjustment != null) {
                // Successfully resolved an adjustment, apply the corresponding pre-amp
                L.d("Applying with pre-amp")
                resolvedAdjustment + preAmp.with
            } else {
                // No adjustment found, use the corresponding user-defined pre-amp
                L.d("Applying without pre-amp")
                preAmp.without
            }

        L.d("Applying ReplayGain adjustment ${amplifiedAdjustment}db")

        // Final adjustment along the volume curve.
        volume = 10f.pow(amplifiedAdjustment / 20f)
    }

    // --- AUDIO PROCESSOR IMPLEMENTATION ---

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT -> {
                configuredEncoding = inputAudioFormat.encoding
                inputAudioFormat
            }
            else -> {
                L.w(
                    "Bypassing ReplayGain for unsupported PCM encoding: ${inputAudioFormat.encoding}"
                )
                AudioProcessor.AudioFormat.NOT_SET
            }
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val buffer = replaceOutputBuffer(limit - pos)
        val currentVolume = volume

        if (currentVolume == 1f) {
            buffer.put(inputBuffer.slice())
        } else {
            when (configuredEncoding) {
                C.ENCODING_PCM_16BIT -> {
                    for (i in pos until limit step 2) {
                        val sample = inputBuffer.getLeShort(i)
                        val scaled =
                            (sample * currentVolume)
                                .toInt()
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                .toShort()
                        buffer.putLeShort(scaled)
                    }
                }
                C.ENCODING_PCM_24BIT -> {
                    for (i in pos until limit step 3) {
                        val sample = inputBuffer.getLeInt24(i)
                        val scaled =
                            (sample.toDouble() * currentVolume)
                                .coerceIn(MIN_INT24.toDouble(), MAX_INT24.toDouble())
                                .toInt()
                        buffer.putLeInt24(scaled)
                    }
                }
                C.ENCODING_PCM_32BIT -> {
                    for (i in pos until limit step 4) {
                        val sample = inputBuffer.getLeInt32(i)
                        val scaled =
                            (sample.toDouble() * currentVolume)
                                .coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())
                                .toInt()
                        buffer.putLeInt32(scaled)
                    }
                }
                C.ENCODING_PCM_FLOAT -> {
                    for (i in pos until limit step 4) {
                        val sample = Float.fromBits(inputBuffer.getLeInt32(i))
                        val scaled = (sample * currentVolume).coerceIn(-1f, 1f)
                        buffer.putLeInt32(scaled.toRawBits())
                    }
                }
                else -> buffer.put(inputBuffer.slice())
            }
        }

        inputBuffer.position(limit)
        buffer.flip()
    }

    /** Always read a little-endian [Short] from the [ByteBuffer] at [at]. */
    private fun ByteBuffer.getLeShort(at: Int) =
        get(at + 1).toInt().shl(8).or(get(at).toInt().and(0xFF)).toShort()

    /** Always write a little-endian [Short] at the end of the [ByteBuffer]. */
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
        const val MIN_INT24 = -(1 shl 23)
        const val MAX_INT24 = (1 shl 23) - 1
    }
}
