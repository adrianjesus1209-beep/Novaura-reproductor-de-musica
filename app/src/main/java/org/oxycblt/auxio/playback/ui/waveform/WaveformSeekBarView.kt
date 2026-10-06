/*
 * Copyright (c) 2026 Auxio Project
 * WaveformSeekBarView.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.ui.waveform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.sin
import org.oxycblt.auxio.R

/**
 * A customized waveform seekbar view that renders musical amplitude bars with active/played bars in
 * gold accent and unplayed bars in translucent white.
 *
 * Amplitude bars are generated deterministically per-song using a seeded pseudo-random profile,
 * producing convincing peaks and valleys similar to a real audio waveform.
 */
class WaveformSeekBarView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val activePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.player_accent_gold)
            style = Paint.Style.FILL
        }

    private val inactivePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40FFFFFF.toInt()
            style = Paint.Style.FILL
        }

    private val barRect = RectF()
    private val barWidthDp = 2.5f
    private val barGapDp = 1.5f
    private val cornerRadiusDp = 1.5f

    private var barWidthPx = 0f
    private var barGapPx = 0f
    private var cornerRadiusPx = 0f

    // Waveform amplitude profile, generated once per song
    private var amplitudeProfile: FloatArray = floatArrayOf()
    private var profileSeed: Long = 0L

    var progress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var audioLevel: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var listener: OnSeekListener? = null

    init {
        val density = resources.displayMetrics.density
        barWidthPx = barWidthDp * density
        barGapPx = barGapDp * density
        cornerRadiusPx = cornerRadiusDp * density
    }

    /**
     * Updates the waveform profile for a new song. The [seed] should be derived from a stable song
     * identifier (e.g. hashCode of uid or durationMs) so the same song always shows the same
     * waveform pattern.
     */
    fun setSongSeed(seed: Long) {
        if (profileSeed == seed && amplitudeProfile.isNotEmpty()) return
        profileSeed = seed
        amplitudeProfile = generateAmplitudeProfile(seed, PROFILE_SIZE)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Regenerate if size changes (bar count changes)
        if (amplitudeProfile.isEmpty()) {
            amplitudeProfile = generateAmplitudeProfile(profileSeed, PROFILE_SIZE)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val totalBarWidth = barWidthPx + barGapPx
        val barCount = (w / totalBarWidth).toInt().coerceAtLeast(10)
        val centerY = h / 2f
        val maxAmplitude = (h / 2f) * 0.92f
        val minAmplitude = (h / 2f) * 0.08f

        // Subtle audio-level boost on played bars
        val boost = 1f + (audioLevel * 0.15f)

        for (i in 0 until barCount) {
            val barX = i * totalBarWidth + barGapPx / 2f
            if (barX + barWidthPx > w) break

            val factor = sampleAmplitude(i, barCount)
            val isPassed = (barX + barWidthPx / 2f) <= (progress * w)

            val effectiveFactor = if (isPassed) (factor * boost).coerceAtMost(1f) else factor
            val halfHeight = minAmplitude + (maxAmplitude - minAmplitude) * effectiveFactor

            barRect.set(
                barX,
                centerY - halfHeight,
                barX + barWidthPx,
                centerY + halfHeight,
            )

            canvas.drawRoundRect(
                barRect,
                cornerRadiusPx,
                cornerRadiusPx,
                if (isPassed) activePaint else inactivePaint,
            )
        }
    }

    /**
     * Samples the amplitude profile for bar [index] out of [barCount], interpolating from the
     * fixed-size profile array.
     */
    private fun sampleAmplitude(index: Int, barCount: Int): Float {
        if (amplitudeProfile.isEmpty()) return 0.5f
        val normalizedPos = index.toFloat() / barCount.toFloat()
        val profilePos = normalizedPos * (amplitudeProfile.size - 1)
        val lo = profilePos.toInt().coerceIn(0, amplitudeProfile.size - 1)
        val hi = (lo + 1).coerceIn(0, amplitudeProfile.size - 1)
        val frac = profilePos - lo
        return amplitudeProfile[lo] * (1f - frac) + amplitudeProfile[hi] * frac
    }

    companion object {
        private const val PROFILE_SIZE = 200

        /**
         * Generates a fixed-length amplitude profile using a seeded LCG combined with layered sine
         * waves to produce natural-looking audio waveform shapes with peaks and quiet sections.
         */
        fun generateAmplitudeProfile(seed: Long, size: Int): FloatArray {
            val profile = FloatArray(size)
            // Seeded LCG for deterministic noise
            var rng = seed xor 0x5DEECE66DL
            fun nextFloat(): Float {
                rng = (rng * 0x5DEECE66DL + 0xBL) and 0xFFFFFFFFFFFFL
                return (rng ushr 17).toFloat() / 0x7FFFFFFF.toFloat()
            }

            // Generate raw random noise
            val noise = FloatArray(size) { nextFloat() }

            // Smooth noise with a simple moving average (window = 5)
            val smoothed = FloatArray(size)
            for (i in 0 until size) {
                var sum = 0f
                var count = 0
                for (d in -3..3) {
                    val idx = (i + d).coerceIn(0, size - 1)
                    sum += noise[idx]
                    count++
                }
                smoothed[i] = sum / count
            }

            // Layer with low-frequency envelope for musical structure
            // (quiet intro → build → chorus → outro shape)
            for (i in 0 until size) {
                val t = i.toFloat() / size
                // Low-frequency envelope: rises, peaks, dips slightly in middle, peaks again
                val envelope =
                    0.4f +
                        0.35f * abs(sin(t * Math.PI.toFloat())) +
                        0.15f * abs(sin(t * Math.PI.toFloat() * 2.3f)) +
                        0.10f * abs(sin(t * Math.PI.toFloat() * 5.1f))

                // Combine smoothed noise with envelope
                val raw = smoothed[i] * 0.5f + envelope * 0.5f
                profile[i] = raw.coerceIn(0.05f, 1f)
            }

            // Normalize so max = 1.0 and min = 0.05
            val maxVal = profile.max()
            val minVal = profile.min()
            val range = maxVal - minVal
            if (range > 0f) {
                for (i in 0 until size) {
                    profile[i] = 0.05f + 0.95f * ((profile[i] - minVal) / range)
                }
            }

            return profile
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val w = width.toFloat()
        if (w <= 0f) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                progress = (event.x / w).coerceIn(0f, 1f)
                listener?.onStartTrackingTouch()
                listener?.onProgressChanged(progress, fromUser = true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                progress = (event.x / w).coerceIn(0f, 1f)
                listener?.onProgressChanged(progress, fromUser = true)
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                progress = (event.x / w).coerceIn(0f, 1f)
                listener?.onProgressChanged(progress, fromUser = true)
                listener?.onStopTrackingTouch(progress)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    interface OnSeekListener {
        fun onStartTrackingTouch()

        fun onProgressChanged(progress: Float, fromUser: Boolean)

        fun onStopTrackingTouch(progress: Float)
    }
}
