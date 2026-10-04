/*
 * Copyright (c) 2026 Auxio Project
 * VerticalEqColumnView.kt is part of Auxio.
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
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.AttrRes
import androidx.core.graphics.ColorUtils
import kotlin.math.min
import org.oxycblt.auxio.util.getAttrColorCompat

/**
 * A custom vertical frequency band slider for the equalizer screen, displaying a rounded pill
 * track, an active gradient level indicator, and a frequency label.
 */
class VerticalEqColumnView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, @AttrRes defStyleAttr: Int = 0) :
    View(context, attrs, defStyleAttr) {

    private val trackPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#242424")
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 12f * resources.displayMetrics.scaledDensity
            color = Color.parseColor("#B3FFFFFF")
        }

    private val trackRect = RectF()
    private val fillRect = RectF()

    var minLevel: Float = -1500f
    var maxLevel: Float = 1500f

    var level: Float = 0f
        set(value) {
            field = value.coerceIn(minLevel, maxLevel)
            invalidate()
        }

    var frequencyText: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var onLevelChangeListener: ((Float, Boolean) -> Unit)? = null

    private var accentColor: Int = Color.WHITE
    private var accentSecondaryColor: Int = Color.parseColor("#CCCCCC")

    init {
        val primary = context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary)
        if (primary.defaultColor != 0) {
            accentColor = primary.defaultColor
            accentSecondaryColor = ColorUtils.blendARGB(accentColor, Color.WHITE, 0.25f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        val labelHeight = textPaint.textSize + 16f
        val trackTop = 16f
        val trackBottom = h - labelHeight
        val trackWidth = min(w * 0.55f, 44f * resources.displayMetrics.density)
        val trackLeft = (w - trackWidth) / 2f
        val trackRight = trackLeft + trackWidth
        val cornerRadius = trackWidth / 2f

        trackRect.set(trackLeft, trackTop, trackRight, trackBottom)

        // Draw track background
        trackPaint.alpha = if (isEnabled) 255 else 120
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackPaint)

        // Draw filled level
        val fraction = ((level - minLevel) / (maxLevel - minLevel)).coerceIn(0f, 1f)
        val fillHeight = trackRect.height() * fraction
        val fillTop = trackRect.bottom - fillHeight

        if (fillHeight > 0f) {
            fillRect.set(trackLeft, fillTop, trackRight, trackBottom)

            fillPaint.shader =
                LinearGradient(
                    trackLeft,
                    trackBottom,
                    trackLeft,
                    fillTop,
                    intArrayOf(accentColor, accentSecondaryColor),
                    null,
                    Shader.TileMode.CLAMP,
                )
            fillPaint.alpha = if (isEnabled) 255 else 80

            canvas.save()
            canvas.clipRect(trackRect)
            canvas.drawRoundRect(fillRect, cornerRadius, cornerRadius, fillPaint)
            canvas.restore()
        }

        // Draw frequency text
        textPaint.color =
            if (isEnabled) Color.parseColor("#CCCCCC") else Color.parseColor("#666666")
        canvas.drawText(frequencyText, w / 2f, h - 4f, textPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val labelHeight = textPaint.textSize + 16f
                val trackTop = 16f
                val trackBottom = height - labelHeight
                val trackHeight = trackBottom - trackTop
                if (trackHeight > 0) {
                    val touchY = event.y.coerceIn(trackTop, trackBottom)
                    val fraction = 1f - ((touchY - trackTop) / trackHeight)
                    val newLevel = minLevel + fraction * (maxLevel - minLevel)
                    level = newLevel
                    onLevelChangeListener?.invoke(level, true)
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
