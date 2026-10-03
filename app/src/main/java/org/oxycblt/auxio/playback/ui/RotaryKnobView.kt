/*
 * Copyright (c) 2026 Auxio Project
 * RotaryKnobView.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.AttrRes
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import org.oxycblt.auxio.util.getAttrColorCompat

/**
 * A custom circular rotary dial control for adjusting Bass Boost and Virtualizer levels in the
 * equalizer.
 */
class RotaryKnobView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, @AttrRes defStyleAttr: Int = 0) :
    View(context, attrs, defStyleAttr) {

    private val trackPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#2A2730")
        }

    private val progressPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

    private val knobPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#1B191E")
        }

    private val knobBorderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#333038")
        }

    private val indicatorPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

    private val titlePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 13f * resources.displayMetrics.scaledDensity
            color = Color.parseColor("#E0E0E0")
        }

    private val arcBounds = RectF()

    var title: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var value: Int = 0 // 0 to 1000
        set(value) {
            field = value.coerceIn(0, 1000)
            invalidate()
        }

    var onValueChangedListener: ((Int, Boolean) -> Unit)? = null

    private var accentColor: Int = Color.parseColor("#FF5252")
    private var lastTouchY: Float = 0f

    init {
        val primary = context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary)
        if (primary.defaultColor != 0) {
            accentColor = primary.defaultColor
        }
        progressPaint.color = accentColor
        indicatorPaint.color = accentColor
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        val textSpacing = titlePaint.textSize + 12f
        val size = min(w, h - textSpacing)
        val cx = w / 2f
        val cy = size / 2f + 4f

        val strokeWidth = size * 0.08f
        trackPaint.strokeWidth = strokeWidth
        progressPaint.strokeWidth = strokeWidth
        indicatorPaint.strokeWidth = strokeWidth * 0.8f

        val radius = (size - strokeWidth * 2f) / 2f
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        val startAngle = 135f
        val totalSweep = 270f
        val currentSweep = (value / 1000f) * totalSweep

        // Draw background arc
        trackPaint.alpha = if (isEnabled) 255 else 100
        canvas.drawArc(arcBounds, startAngle, totalSweep, false, trackPaint)

        // Draw progress arc
        if (currentSweep > 0) {
            progressPaint.alpha = if (isEnabled) 255 else 80
            canvas.drawArc(arcBounds, startAngle, currentSweep, false, progressPaint)
        }

        // Draw inner circular knob
        val knobRadius = radius * 0.75f
        knobPaint.alpha = if (isEnabled) 255 else 120
        canvas.drawCircle(cx, cy, knobRadius, knobPaint)
        canvas.drawCircle(cx, cy, knobRadius, knobBorderPaint)

        // Draw knob indicator line
        val currentAngleRad = Math.toRadians((startAngle + currentSweep).toDouble())
        val indicatorStart = knobRadius * 0.45f
        val indicatorEnd = knobRadius * 0.85f
        val ix1 = cx + (indicatorStart * cos(currentAngleRad)).toFloat()
        val iy1 = cy + (indicatorStart * sin(currentAngleRad)).toFloat()
        val ix2 = cx + (indicatorEnd * cos(currentAngleRad)).toFloat()
        val iy2 = cy + (indicatorEnd * sin(currentAngleRad)).toFloat()

        indicatorPaint.alpha = if (isEnabled) 255 else 100
        canvas.drawLine(ix1, iy1, ix2, iy2, indicatorPaint)

        // Draw title label below
        titlePaint.color =
            if (isEnabled) Color.parseColor("#E0E0E0") else Color.parseColor("#666666")
        canvas.drawText(title, cx, h - 4f, titlePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = lastTouchY - event.y
                lastTouchY = event.y
                val deltaValue = (deltaY * 5f).toInt()
                val newValue = (value + deltaValue).coerceIn(0, 1000)
                if (newValue != value) {
                    value = newValue
                    onValueChangedListener?.invoke(value, true)
                }
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
