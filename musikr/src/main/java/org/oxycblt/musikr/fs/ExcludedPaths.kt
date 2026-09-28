/*
 * Copyright (c) 2024 Auxio Project
 * ExcludedPaths.kt is part of Auxio.
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
 
package org.oxycblt.musikr.fs

/**
 * The single source of truth for folder markers whose contents are never music.
 *
 * Both file system backends used to carry their own copy of this list and normalize the path
 * differently, so SAF and MediaStore indexed different libraries from the same card: a top-level
 * `Ringtones/x.mp3` was skipped by one backend and kept by the other, because the markers are
 * written with leading *and* trailing separators.
 */
object ExcludedPaths {
    private val MARKERS =
        listOf(
            "/whatsapp voice notes/",
            "/.whatsapp/",
            "/voice notes/",
            "/voicerecordings/",
            "/soundrecorder/",
            "/ringtones/",
            "/notifications/",
            "/alarms/",
        )

    /** Whether the given unix path falls inside one of the excluded folders. */
    fun isExcluded(path: String): Boolean {
        val normalized = "/${path.trim('/').lowercase()}/"
        return MARKERS.any { normalized.contains(it) }
    }

    /** Whether the given path falls inside one of the excluded folders. */
    fun isExcluded(components: Components): Boolean = isExcluded(components.unixString)
}
