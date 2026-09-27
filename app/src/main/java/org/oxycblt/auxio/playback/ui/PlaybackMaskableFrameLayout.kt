/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackMaskableFrameLayout.kt is part of Auxio.
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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.carousel.MaskableFrameLayout
import com.google.android.material.shape.ShapeAppearanceModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.hypot
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.util.getAttrColorCompat

/**
 * The container for a playback cover that can bloom with the music.
 *
 * The glow is drawn on top of the children rather than behind them, and is transparent across the
 * middle of the frame, so it reads as light bleeding out of the artwork's edges instead of a flat
 * colour wash over the album art. Doing it here rather than with an extra child view means it
 * inherits [MaskableFrameLayout]'s shape automatically, in both rounded and square mode, and the
 * layout never has to change.
 *
 * @author Novaura
 */
@AndroidEntryPoint
class PlaybackMaskableFrameLayout
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleRes: Int = -1) :
    MaskableFrameLayout(context, attrs, defStyleRes) {
    @Inject lateinit var uiSettings: UISettings

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glowShader: RadialGradient? = null
    private var displayedLevel = 0f
    private var tickerScheduled = false

    /**
     * Supplies how strongly the glow should react to the audio, from 0 (no glow) to 1 (full range).
     *
     * A provider so that changing the setting takes effect on the next frame, with no listener
     * plumbing to keep in sync across every pooled cover item.
     */
    var audioReactivityProvider: (() -> Float)? = null

    /**
     * Supplies the current audio level in the range 0..1, sampled once per drawn frame.
     *
     * A provider rather than a pushed value so that the audio thread never has to wake the main
     * thread, matching how WavySlider reads the same source.
     */
    var audioLevelProvider: (() -> Float)? = null

    /** The current reactivity strength, re-read every frame. */
    private val reactivity: Float
        get() = audioReactivityProvider?.invoke()?.coerceIn(0f, 1f) ?: 0f

    /**
     * Whether this cover is the one currently on screen and should therefore animate its glow.
     *
     * The pager keeps the neighbouring pages attached, and every one of them would otherwise run
     * its own vsync ticker and paint a full-viewport gradient. Restricting it to the selected page
     * turns three permanent animations into one. When disabled the glow is dropped immediately
     * rather than faded, since the page is on its way out of view anyway.
     */
    var glowEnabled: Boolean = true
        set(value) {
            if (field == value) {
                return
            }
            field = value
            if (!value && displayedLevel != 0f) {
                displayedLevel = 0f
            }
            ensureTicker()
            invalidate()
        }

    private val frameTicker =
        object : Runnable {
            override fun run() {
                tickerScheduled = false
                if (!shouldTick()) {
                    // Reactivity was switched off: drop the glow instead of fading it.
                    if (displayedLevel != 0f) {
                        displayedLevel = 0f
                        invalidate()
                    }
                    return
                }

                val target = (audioLevelProvider?.invoke() ?: 0f).coerceIn(0f, 1f)
                // The processor updates every few buffers, not every frame. A light per-frame lerp
                // keeps the glow continuous instead of stepping between those updates.
                displayedLevel += (target - displayedLevel) * GLOW_LERP
                if (abs(target - displayedLevel) < LEVEL_EPSILON) {
                    displayedLevel = target
                }
                invalidate()
                scheduleTicker()
            }
        }

    init {
        // The parent FrameLayout will have already fetched/applied a rounded shape appearance
        // so in non-round mode we just force it back to sharp
        if (!uiSettings.roundMode) {
            shapeAppearanceModel = ShapeAppearanceModel.builder().build()
        }
        glowPaint.style = Paint.Style.FILL
    }

    // --- TOUCH ---

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // MaskableFrameLayout is weird and decides to register clicks even when masked.
        // Avoid this so that we can still have steppers work in the pager.
        if (
            event.actionMasked == MotionEvent.ACTION_DOWN && !maskRectF.contains(event.x, event.y)
        ) {
            return false
        }
        return super.dispatchTouchEvent(event)
    }

    // --- LIFECYCLE ---

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureTicker()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameTicker)
        tickerScheduled = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        ensureTicker()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        ensureTicker()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        glowShader = null
    }

    // --- DRAWING ---

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val strength = reactivity
        if (strength <= 0f || displayedLevel <= 0f) {
            return
        }
        val shader = obtainGlowShader() ?: return
        glowPaint.shader = shader
        // Alpha carries the level so the gradient itself, which is the expensive part, is built
        // once per size change rather than once per frame.
        glowPaint.alpha = (MAX_GLOW_ALPHA * displayedLevel * strength).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
    }

    private fun obtainGlowShader(): RadialGradient? {
        glowShader?.let {
            return it
        }
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            return null
        }
        val cx = w / 2f
        val cy = h / 2f
        // Reach the corners so the vignette falls off evenly from the middle of each edge.
        val radius = hypot(cx, cy)
        if (radius <= 0f) {
            return null
        }
        val color = context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary).defaultColor
        val shader =
            RadialGradient(
                cx,
                cy,
                radius,
                intArrayOf(color and 0x00FFFFFF, color and 0x00FFFFFF, color),
                floatArrayOf(0f, GLOW_START, 1f),
                Shader.TileMode.CLAMP,
            )
        glowShader = shader
        return shader
    }

    // --- TICKER ---

    private fun ensureTicker() {
        if (shouldTick()) {
            scheduleTicker()
        } else {
            removeCallbacks(frameTicker)
            tickerScheduled = false
        }
    }

    private fun scheduleTicker() {
        if (tickerScheduled) {
            return
        }
        tickerScheduled = true
        postOnAnimation(frameTicker)
    }

    private fun shouldTick() =
        glowEnabled &&
            reactivity > 0f &&
            audioLevelProvider != null &&
            isAttachedToWindow &&
            windowVisibility == VISIBLE &&
            isShown &&
            alpha > 0f

    private companion object {
        /** Peak opacity of the glow. Kept low so the album art stays readable underneath. */
        const val MAX_GLOW_ALPHA = 90f

        /** Where the glow starts to appear, as a fraction of the corner distance. */
        const val GLOW_START = 0.55f

        const val GLOW_LERP = 0.25f
        const val LEVEL_EPSILON = 0.001f
    }
}
