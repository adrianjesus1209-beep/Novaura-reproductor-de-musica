/*
 * Copyright (c) 2024 Auxio Project
 * ParseUtil.kt is part of Auxio.
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
 
package org.oxycblt.musikr.util

/**
 * Split a [String] by the given selector, automatically handling escaped characters that satisfy
 * the selector.
 *
 * @param selector A block that determines if the string should be split at a given character.
 * @return One or more [String]s split by the selector.
 */
internal inline fun String.splitEscaped(selector: (Char) -> Boolean): List<String> {
    val split = mutableListOf<String>()
    val currentString = StringBuilder()
    var i = 0

    while (i < length) {
        val a = get(i)
        val b = getOrNull(i + 1)

        if (b != null && a == '\\' && (b == '\\' || selector(b))) {
            // Escaped backslash or escaped separator: emit the literal unescaped char.
            currentString.append(b)
            i += 2
            continue
        }

        if (selector(a)) {
            split.add(currentString.toString())
            currentString.clear()
            i++
            continue
        }

        currentString.append(a)
        i++
    }

    if (currentString.isNotEmpty()) {
        // Had an in-progress split string that is now terminated, add it.
        split.add(currentString.toString())
    }

    return split
}

// TODO: Remove the escaping checks, it's too expensive to do this for every single tag.

/**
 * Fix trailing whitespace or blank contents in a [String].
 *
 * @return A string with trailing whitespace remove,d or null if the [String] was all whitespace or
 *   empty.
 */
internal fun String.correctWhitespace() = trim().ifBlank { null }

/**
 * Fix trailing whitespace or blank contents within a list of [String]s.
 *
 * @return A list of non-blank strings with trailing whitespace removed.
 */
internal fun List<String>.correctWhitespace() = mapNotNull { it.correctWhitespace() }
