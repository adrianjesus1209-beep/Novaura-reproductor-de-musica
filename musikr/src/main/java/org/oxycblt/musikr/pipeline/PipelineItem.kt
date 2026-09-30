/*
 * Copyright (c) 2025 Auxio Project
 * PipelineItem.kt is part of Auxio.
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

import org.oxycblt.musikr.covers.Cover
import org.oxycblt.musikr.fs.File
import org.oxycblt.musikr.metadata.Properties
import org.oxycblt.musikr.playlist.PlaylistFile
import org.oxycblt.musikr.tag.parse.ParsedTags

/**
 * Shared tuning for the indexing pipeline.
 *
 * Lives here so that [org.oxycblt.musikr.Musikr], [ExploreStep] and [ExtractStep] cannot drift
 * apart on how much work is in flight at once.
 */
internal object PipelineTuning {
    /**
     * Number of concurrent workers per stage.
     *
     * Half of the available cores is deliberately left free so the main thread, the JNI tag parser
     * and cover transcoding do not starve each other. A hard-coded 8 saturated CPU and IO on
     * low-end devices, which is what made the UI stutter while scanning.
     */
    val parallelism: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)

    /**
     * Capacity of the channels connecting pipeline stages.
     *
     * A small multiple of [parallelism], so a fast producer applies backpressure instead of
     * buffering the whole device in memory, while never leaving a worker blocked behind a full
     * buffer. Previously every stage channel was UNLIMITED, which let extracted songs (and the
     * embedded cover bitmaps they carry) pile up unboundedly on large libraries.
     */
    val stageBuffer: Int = parallelism * 2

    /**
     * Single source of truth for the smallest plausible song duration, in milliseconds.
     *
     * Used by every stage and by the MediaStore SQL filter, so a TagLib mis-report or a query
     * heuristic can no longer judge a file by three different rules at three different points.
     */
    const val MIN_PLAUSIBLE_DURATION_MS = 3000L
}

internal sealed interface PipelineItem

internal sealed interface Incomplete : PipelineItem

internal sealed interface Complete : PipelineItem

internal sealed interface Explored : PipelineItem {
    sealed interface New : Explored, Incomplete

    sealed interface Known : Explored, Complete
}

internal data class NewSong(val file: File) : Explored.New

internal sealed interface Extracted : PipelineItem {
    sealed interface Valid : Complete, Extracted

    sealed interface Invalid : Extracted
}

internal data object InvalidSong : Extracted.Invalid

internal data object NotAudio : Explored.Known, Extracted.Valid

internal data class RawPlaylist(val file: PlaylistFile) : Explored.Known, Extracted.Valid

internal data class RawSong(
    val file: File,
    val properties: Properties,
    val tags: ParsedTags,
    val cover: Cover?,
    val addedMs: Long,
) : Explored.Known, Extracted.Valid
