/*
 * Copyright (c) 2024 Auxio Project
 * ScanDiagnostics.kt is part of Auxio.
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
 
package org.oxycblt.musikr.pipeline

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray

/** The reasons the scanner can drop a candidate without the user ever noticing. */
enum class ScanReject {
    /** The file system could not map the raw row onto a [org.oxycblt.musikr.fs.Path]. */
    PATH_UNRESOLVED,

    /** A hardcoded voice-note/system folder marker matched. */
    EXCLUDED_PATH,

    /** A hardcoded non-audio extension matched. */
    EXCLUDED_EXTENSION,

    /** Neither the MIME type nor the extension looks like audio. */
    UNSUPPORTED_TYPE,

    /** A duration short enough to mean "TagLib could not parse this" was reported. */
    DURATION_TOO_SHORT,

    /** The extractor returned no metadata at all. */
    NO_METADATA,

    /** The content provider refused to open the file. */
    PROVIDER_FAILED,
}

/**
 * Counters describing how many candidates the file system handed to the pipeline and why the rest
 * were dropped.
 *
 * Every filter in the pipeline discards files silently, so an empty library is indistinguishable
 * from a device that genuinely has no music on it. These counters make the drop visible in logcat
 * under the `ScanDiagnostics` tag.
 */
object ScanDiagnostics {
    private const val TAG = "ScanDiagnostics"

    private val rows = AtomicInteger(0)
    private val rejected = AtomicInteger(0)
    private val byReason = AtomicIntegerArray(ScanReject.values().size)

    /** Clear all counters. Called once at the start of every indexing run. */
    fun reset() {
        rows.set(0)
        rejected.set(0)
        for (i in 0 until byReason.length()) {
            byReason.set(i, 0)
        }
    }

    /** A single row was returned by the file system query. */
    fun recordRow() {
        rows.incrementAndGet()
    }

    /** A candidate was dropped for the given [reason]. */
    fun recordRejected(reason: ScanReject) {
        rejected.incrementAndGet()
        byReason.incrementAndGet(reason.ordinal)
    }

    /**
     * Log the tally for the finished run.
     *
     * @param songs The amount of songs that survived the whole pipeline.
     */
    fun logSummary(songs: Int) {
        val detail = buildString {
            for (reason in ScanReject.values()) {
                val count = byReason.get(reason.ordinal)
                if (count > 0) {
                    append(' ')
                    append(reason.name)
                    append('=')
                    append(count)
                }
            }
        }
        Log.i(TAG, "rows=$rows rejected=$rejected songs=$songs.$detail")
    }
}
