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
import kotlin.math.sin
import org.oxycblt.auxio.R

/**
 * A customized waveform seekbar view that renders musical amplitude bars with active/played bars in
 * gold accent and unplayed bars in translucent white.
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
            color = 0x4DFFFFFF.toInt()
            style = Paint.Style.FILL
        }

    private val barRect = RectF()
    private val barWidthDp = 3f
    private val barGapDp = 2.5f
    private val cornerRadiusDp = 1.5f

    private var barWidthPx = 0f
    private var barGapPx = 0f
    private var cornerRadiusPx = 0f

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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val totalBarWidth = barWidthPx + barGapPx
        val barCount = (w / totalBarWidth).toInt().coerceAtLeast(10)
        val centerY = h / 2f
        val maxAmplitude = (h / 2f) * 0.9f
        val minAmplitude = (h / 2f) * 0.2f

        for (i in 0 until barCount) {
            val barX = i * totalBarWidth + barGapPx / 2f
            if (barX + barWidthPx > w) break

            val normIndex = i.toFloat() / barCount
            val wave1 = sin(normIndex * Math.PI.toFloat() * 3.5f)
            val wave2 = sin(normIndex * Math.PI.toFloat() * 7.2f) * 0.5f
            val wave3 = sin(normIndex * Math.PI.toFloat() * 1.8f) * 0.8f
            val factor = ((wave1 + wave2 + wave3).coerceIn(-1.5f, 1.5f) + 1.5f) / 3f

            val boost = 1f + (audioLevel * 0.25f)
            val halfHeight =
                (minAmplitude + (maxAmplitude - minAmplitude) * factor * boost).coerceAtMost(h / 2f)

            barRect.set(
                barX,
                centerY - halfHeight,
                barX + barWidthPx,
                centerY + halfHeight,
            )

            val isPassed = (barX + barWidthPx / 2f) <= (progress * w)
            canvas.drawRoundRect(
                barRect,
                cornerRadiusPx,
                cornerRadiusPx,
                if (isPassed) activePaint else inactivePaint,
            )
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
