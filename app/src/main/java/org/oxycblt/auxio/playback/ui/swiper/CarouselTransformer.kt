/*
 * Copyright (c) 2026 Auxio Project
 * CarouselTransformer.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.ui.swiper

import android.graphics.RectF
import android.view.View
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.carousel.MaskableFrameLayout
import kotlin.math.abs
import org.oxycblt.auxio.R

class CarouselTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        val width = page.width.toFloat()
        val height = page.height.toFloat()

        if (width <= 0f || height <= 0f) {
            return
        }

        if (page is MaskableFrameLayout) {
            page.setMaskRectF(RectF(0f, 0f, width, height))
        }

        val absPos = abs(position)
        val clampedProgress = (1f - absPos).coerceIn(0f, 1f)

        // Scale down adjacent cards
        val scale = 0.82f + clampedProgress * 0.18f
        page.scaleX = scale
        page.scaleY = scale

        // Shift adjacent cards inwards to create the stacked depth effect
        val overlap = width * 0.22f
        page.translationX = -position * overlap

        // Elevation / 3D ordering: active card is on top
        page.translationZ = clampedProgress * 10f

        // Subtle alpha fade for background cards
        page.alpha = 0.70f + clampedProgress * 0.30f

        // Only the active card displays the title badge and favorite button
        val badge = page.findViewById<View?>(R.id.cover_badge)
        val fav = page.findViewById<View?>(R.id.cover_favorite)
        val badgeAlpha = (1f - absPos * 2.5f).coerceIn(0f, 1f)
        badge?.alpha = badgeAlpha
        fav?.alpha = badgeAlpha
    }
}
