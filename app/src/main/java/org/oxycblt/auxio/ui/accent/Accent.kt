/*
 * Copyright (c) 2021 Auxio Project
 * Accent.kt is part of Auxio.
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
 
package org.oxycblt.auxio.ui.accent

import org.oxycblt.auxio.R
import timber.log.Timber as L

private val accentNames =
    intArrayOf(
        R.string.clr_mono_white,
        R.string.clr_mono_silver,
        R.string.clr_mono_grey,
        R.string.clr_mono_graphite,
        R.string.clr_mono_charcoal,
    )

private val accentThemes =
    intArrayOf(
        R.style.Theme_Auxio_MonoWhite,
        R.style.Theme_Auxio_MonoSilver,
        R.style.Theme_Auxio_Grey,
        R.style.Theme_Auxio_MonoGraphite,
        R.style.Theme_Auxio_MonoCharcoal,
    )

private val accentBlackThemes =
    intArrayOf(
        R.style.Theme_Auxio_MonoWhite_Black,
        R.style.Theme_Auxio_MonoSilver_Black,
        R.style.Theme_Auxio_Grey_Black,
        R.style.Theme_Auxio_MonoGraphite_Black,
        R.style.Theme_Auxio_MonoCharcoal_Black,
    )

private val accentPrimaryColors =
    intArrayOf(
        R.color.mono_white_primary,
        R.color.mono_silver_primary,
        R.color.grey_primary,
        R.color.mono_graphite_primary,
        R.color.mono_charcoal_primary,
    )

/**
 * The data object for a colored theme to use in the UI. This can be nominally used to gleam some
 * attributes about a given color scheme, but this is not recommended. Attributes are the better
 * option in nearly all cases.
 *
 * @param index The unique number for this particular accent.
 * @author Alexander Capehart (OxygenCobalt)
 */
class Accent private constructor(val index: Int) {
    /** The name of this [Accent]. */
    val name: Int
        get() = accentNames[index]

    /** The theme resource for this accent. */
    val theme: Int
        get() = accentThemes[index]

    /**
     * The black theme resource for this accent. Identical to [theme], but with a black background.
     */
    val blackTheme: Int
        get() = accentBlackThemes[index]

    /** The accent's primary color. */
    val primary: Int
        get() = accentPrimaryColors[index]

    override fun equals(other: Any?) = other is Accent && index == other.index

    override fun hashCode() = index.hashCode()

    companion object {
        /**
         * Create a new instance.
         *
         * @param index The unique number for this particular accent.
         * @return A new [Accent] with the specified [index]. If [index] is not within the range of
         *   valid accents, [index] will be [DEFAULT] instead.
         */
        fun from(index: Int): Accent {
            if (index !in 0 until MAX) {
                L.w("Accent is out of bounds [idx: $index]")
                return Accent(DEFAULT)
            }
            return Accent(index)
        }

        const val MONO_WHITE = 0
        const val MONO_SILVER = 1
        const val MONO_GREY = 2
        const val MONO_GRAPHITE = 3
        const val MONO_CHARCOAL = 4

        /** The default accent. Pure white high contrast. */
        val DEFAULT = MONO_WHITE

        /** The amount of valid accents. */
        val MAX = accentThemes.size
    }
}
