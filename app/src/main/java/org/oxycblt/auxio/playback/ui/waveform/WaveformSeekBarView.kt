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
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * A customized waveform seekbar view that renders musical amplitude bars with top bars and bottom
 * reflection, animated smoothly using sub-pixel clipping and continuous 60fps tracking.
 */
class WaveformSeekBarView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val topActivePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

    private val bottomActivePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x80FFFFFF.toInt()
            style = Paint.Style.FILL
        }

    private val topInactivePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55FFFFFF.toInt()
            style = Paint.Style.FILL
        }

    private val bottomInactivePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x20FFFFFF.toInt()
            style = Paint.Style.FILL
        }

    private val barRect = RectF()
    private val barWidthDp = 2.2f
    private val barGapDp = 1.4f
    private val cornerRadiusDp = 1.2f
    private val baselineGapDp = 1.2f

    private var barWidthPx = 0f
    private var barGapPx = 0f
    private var cornerRadiusPx = 0f
    private var baselineGapPx = 0f

    // Waveform amplitude profile, generated once per song
    private var amplitudeProfile: FloatArray = floatArrayOf()
    private var profileSeed: Long = 0L

    private var basePositionMs: Long = 0L
    private var durationMs: Long = 1000L
    private var lastPositionUpdateUptime: Long = 0L
    private var currentRenderProgress: Float = 0f
    private var isSeeking: Boolean = false

    var isPlaying: Boolean = false
        set(value) {
            field = value
            lastPositionUpdateUptime = SystemClock.uptimeMillis()
            if (value) postInvalidateOnAnimation()
        }

    var audioLevel: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var audioLevelProvider: (() -> Float)? = null

    var listener: OnSeekListener? = null

    init {
        val density = resources.displayMetrics.density
        barWidthPx = barWidthDp * density
        barGapPx = barGapDp * density
        cornerRadiusPx = cornerRadiusDp * density
        baselineGapPx = baselineGapDp * density
    }

    /**
     * Updates the waveform profile for a new song. The [seed] is derived from a stable song
     * identifier (e.g. hashCode of webUrl or title) so the same song always shows the same waveform
     * pattern.
     */
    fun setSongSeed(seed: Long) {
        if (profileSeed == seed && amplitudeProfile.isNotEmpty()) return
        profileSeed = seed
        amplitudeProfile = generateAmplitudeProfile(seed, PROFILE_SIZE)
        invalidate()
    }

    fun setProgress(progressRatio: Float, currentPosMs: Long, totalDurationMs: Long) {
        basePositionMs = currentPosMs
        durationMs = totalDurationMs.coerceAtLeast(1000L)
        lastPositionUpdateUptime = SystemClock.uptimeMillis()
        if (!isSeeking) {
            currentRenderProgress = progressRatio.coerceIn(0f, 1f)
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (amplitudeProfile.isEmpty()) {
            amplitudeProfile = generateAmplitudeProfile(profileSeed, PROFILE_SIZE)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 60fps continuous sub-pixel progression during playback
        if (isPlaying && !isSeeking && durationMs > 0) {
            val now = SystemClock.uptimeMillis()
            val elapsed = (now - lastPositionUpdateUptime).coerceAtLeast(0L)
            val estimatedPosMs = (basePositionMs + elapsed).coerceAtMost(durationMs)
            currentRenderProgress =
                (estimatedPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            postInvalidateOnAnimation()
        }

        val totalBarWidth = barWidthPx + barGapPx
        val barCount = (w / totalBarWidth).toInt().coerceAtLeast(10)

        // Baseline separating top audio bars from bottom reflection bars (at ~60% height)
        val baselineY = h * 0.60f
        val maxTopHeight = (baselineY - baselineGapPx - 2f).coerceAtLeast(1f)
        val maxBottomHeight = (h - baselineY - baselineGapPx - 2f).coerceAtLeast(1f)
        val minTopHeight = (maxTopHeight * 0.12f).coerceAtLeast(1.5f)

        val liveAudio = audioLevelProvider?.invoke()?.coerceIn(0f, 1f) ?: audioLevel
        val boost = 1f + (liveAudio * 0.18f)

        fun drawBars(topPaint: Paint, bottomPaint: Paint) {
            for (i in 0 until barCount) {
                val barX = i * totalBarWidth + barGapPx / 2f
                if (barX + barWidthPx > w) break

                val factor = sampleAmplitude(i, barCount)
                val effectiveFactor = (factor * boost).coerceIn(0f, 1f)
                val topHeight = minTopHeight + (maxTopHeight - minTopHeight) * effectiveFactor
                val bottomHeight =
                    (topHeight * 0.45f)
                        .coerceAtMost(maxBottomHeight)
                        .coerceAtLeast(minTopHeight * 0.45f)

                // Top bar (rises from baseline upwards)
                barRect.set(
                    barX,
                    baselineY - baselineGapPx - topHeight,
                    barX + barWidthPx,
                    baselineY - baselineGapPx,
                )
                canvas.drawRoundRect(barRect, cornerRadiusPx, cornerRadiusPx, topPaint)

                // Bottom reflection bar (drops from baseline downwards)
                barRect.set(
                    barX,
                    baselineY + baselineGapPx,
                    barX + barWidthPx,
                    baselineY + baselineGapPx + bottomHeight,
                )
                canvas.drawRoundRect(barRect, cornerRadiusPx, cornerRadiusPx, bottomPaint)
            }
        }

        // Pass 1: Draw all bars across full width in inactive paints
        drawBars(topInactivePaint, bottomInactivePaint)

        // Pass 2: Clip to exact progress pixel and draw active overlay
        val progressPx = currentRenderProgress * w
        if (progressPx > 0f) {
            canvas.save()
            canvas.clipRect(0f, 0f, progressPx, h)
            drawBars(topActivePaint, bottomActivePaint)
            canvas.restore()
        }

        if (audioLevelProvider != null && liveAudio > 0.02f) {
            postInvalidateOnAnimation()
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
         * Generates a fixed-length amplitude profile using layered pseudo-random harmonics, beat
         * pulses, and musical section envelopes matching the natural audio waveform look from
         * Image 3.
         */
        fun generateAmplitudeProfile(seed: Long, size: Int): FloatArray {
            val profile = FloatArray(size)
            var rng = seed xor 0x5DEECE66DL
            fun nextFloat(): Float {
                rng = (rng * 0x5DEECE66DL + 0xBL) and 0xFFFFFFFFFFFFL
                return (rng ushr 17).toFloat() / 0x7FFFFFFF.toFloat()
            }

            val rawNoise = FloatArray(size) { nextFloat() }

            // Narrow smoothing to retain musical punch and sharp peaks
            val smoothedNoise = FloatArray(size)
            for (i in 0 until size) {
                val p0 = rawNoise[(i - 1).coerceAtLeast(0)]
                val p1 = rawNoise[i]
                val p2 = rawNoise[(i + 1).coerceAtMost(size - 1)]
                smoothedNoise[i] = p0 * 0.25f + p1 * 0.5f + p2 * 0.25f
            }

            // Song structure parameters based on seed
            val beatFrequency = 14f + (nextFloat() * 10f)
            val subBeatFrequency = beatFrequency * 2.1f
            val introEnd = 0.08f + nextFloat() * 0.06f
            val outroStart = 0.88f - nextFloat() * 0.06f

            for (i in 0 until size) {
                val t = i.toFloat() / size

                // Macro musical envelope: quiet intro, dynamic body with choruses, quiet outro
                val sectionEnvelope =
                    when {
                        t < introEnd -> (t / introEnd) * 0.6f + 0.15f
                        t > outroStart -> ((1f - t) / (1f - outroStart)) * 0.6f + 0.15f
                        else -> {
                            val midT = (t - introEnd) / (outroStart - introEnd)
                            0.45f +
                                0.35f * abs(sin(midT * Math.PI.toFloat() * 3.5f)) +
                                0.20f * abs(sin(midT * Math.PI.toFloat() * 1.5f))
                        }
                    }

                // Musical beat rhythm: rhythmic pulses and intervals of rise/fall
                val beatPulse =
                    0.30f * abs(sin(t * Math.PI.toFloat() * beatFrequency)) +
                        0.15f * abs(kotlin.math.cos(t * Math.PI.toFloat() * subBeatFrequency))

                val combined =
                    smoothedNoise[i] * 0.45f + beatPulse * 0.25f + sectionEnvelope * 0.30f
                profile[i] = combined.coerceIn(0.08f, 1f)
            }

            // Normalize so peaks reach 0.98 and valleys 0.12 for distinct peaks and falls
            val maxVal = profile.max()
            val minVal = profile.min()
            val range = maxVal - minVal
            if (range > 0f) {
                for (i in 0 until size) {
                    profile[i] = 0.12f + 0.86f * ((profile[i] - minVal) / range)
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
                isSeeking = true
                currentRenderProgress = (event.x / w).coerceIn(0f, 1f)
                listener?.onStartTrackingTouch()
                listener?.onProgressChanged(currentRenderProgress, fromUser = true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentRenderProgress = (event.x / w).coerceIn(0f, 1f)
                listener?.onProgressChanged(currentRenderProgress, fromUser = true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isSeeking = false
                currentRenderProgress = (event.x / w).coerceIn(0f, 1f)
                basePositionMs = (currentRenderProgress * durationMs).toLong()
                lastPositionUpdateUptime = SystemClock.uptimeMillis()
                listener?.onProgressChanged(currentRenderProgress, fromUser = true)
                listener?.onStopTrackingTouch(currentRenderProgress)
                invalidate()
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
