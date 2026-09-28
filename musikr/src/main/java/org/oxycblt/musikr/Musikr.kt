/*
 * Copyright (c) 2024 Auxio Project
 * Musikr.kt is part of Auxio.
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
 
package org.oxycblt.musikr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import org.oxycblt.musikr.pipeline.EvaluateStep
import org.oxycblt.musikr.pipeline.ExploreStep
import org.oxycblt.musikr.pipeline.Explored
import org.oxycblt.musikr.pipeline.ExtractStep
import org.oxycblt.musikr.pipeline.Extracted
import org.oxycblt.musikr.pipeline.PipelineTuning
import org.oxycblt.musikr.pipeline.ScanDiagnostics
import org.oxycblt.musikr.util.merge
import org.oxycblt.musikr.util.tryAsyncWith

/**
 * A highly opinionated, multi-threaded device music library.
 *
 * Use this to load music with [run].
 *
 * Note the following:
 * 1. Musikr's API surface is intended to be primarily "stateless", with side-effects mostly
 *    contained within [Storage]. It's your job to manage long-term state.
 * 2. There are no "defaults" in Musikr. You should think carefully about the parameters you are
 *    specifying and know consider they are desirable or not.
 * 3. Musikr is currently not extendable, so if you're embedding this elsewhere you should be ready
 *    to fork and modify the source code.
 */
interface Musikr {
    /**
     * Start loading music using the given config and the configuration provided earlier.
     *
     * @param onProgress Optional callback to receive progress on the current status of the music
     *   pipeline. Warning: These events will be rapid-fire.
     * @return A handle to the newly created library alongside further cleanup.
     */
    suspend fun run(onProgress: suspend (IndexingProgress) -> Unit = {}): LibraryResult

    companion object {
        /**
         * Create a new instance from the given configuration.
         *
         * @param context The context to use for loading resources.
         * @param config Side-effect laden storage for use within the music loader **and** when
         *   mutating [MutableLibrary]. You should take responsibility for managing their long-term
         *   state.
         * @param interpretation The configuration to use for interpreting certain vague tags. This
         *   should be configured by the user, if possible.
         */
        fun new(context: Context, config: Config): Musikr =
            MusikrImpl(
                config,
                ExploreStep.from(context, config),
                ExtractStep.from(context, config),
                EvaluateStep.new(context, config, config.interpretation),
            )
    }
}

/** Simple library handle returned by [Musikr.run]. */
interface LibraryResult {
    val library: MutableLibrary

    /**
     * Clean up expired resources. This should be done as soon as possible after music loading to
     * reduce storage use.
     *
     * This may have unexpected results if previous [Library]s are in circulation across your app,
     * so use it once you've fully updated your state.
     */
    suspend fun cleanup()
}

/** Music loading progress as reported by the music pipeline. */
sealed interface IndexingProgress {
    /**
     * Currently indexing and extracting tags from device music.
     *
     * @param explored The amount of music currently found from the given [Query].
     * @param loaded The amount of music that has had metadata extracted and parsed.
     */
    data class Songs(val loaded: Int, val explored: Int) : IndexingProgress

    /**
     * Currently creating the music graph alongside I/O finalization.
     *
     * There is no way to measure progress on these events.
     */
    data object Indeterminate : IndexingProgress
}

private class MusikrImpl(
    private val config: Config,
    private val exploreStep: ExploreStep,
    private val extractStep: ExtractStep,
    private val evaluateStep: EvaluateStep,
) : Musikr {
    override suspend fun run(onProgress: suspend (IndexingProgress) -> Unit) = coroutineScope {
        ScanDiagnostics.reset()
        onProgress(IndexingProgress.Songs(0, 0))
        var explored = 0
        var loaded = 0
        var lastProgressReportUptime = 0L

        suspend fun dispatchProgress() {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastProgressReportUptime >= 100L) {
                lastProgressReportUptime = now
                onProgress(IndexingProgress.Songs(loaded, explored))
            }
        }

        // The tracking channels are bounded so that a fast producer cannot buffer the whole
        // device in memory while a slow stage (JNI tag parsing, cover decoding) catches up. Each
        // capacity is a multiple of the stage's parallelism so that no worker is ever blocked
        // behind a full buffer.
        val exploredChannel = Channel<Explored>(PipelineTuning.stageBuffer)
        val exploredTask = exploreStep.explore(this, exploredChannel)
        val trackedExploredChannel = Channel<Explored>(PipelineTuning.stageBuffer)
        // These trackers only count items and forward them, so they must not run on the main
        // thread. On Dispatchers.Main with an UNLIMITED destination the send never suspended,
        // which turned this into a hot loop that starved the UI thread on large libraries.
        // Dispatchers.Default also keeps the main thread free to render the progress it reports.
        val trackedExploredTask =
            tryAsyncWith(trackedExploredChannel, Dispatchers.Default) {
                for (item in exploredChannel) {
                    explored++
                    dispatchProgress()
                    trackedExploredChannel.send(item)
                }
                onProgress(IndexingProgress.Songs(loaded, explored))
            }
        val extractedChannel = Channel<Extracted>(PipelineTuning.stageBuffer)
        val extractedTask = extractStep.extract(this, trackedExploredChannel, extractedChannel)
        val trackedExtractedChannel = Channel<Extracted>(PipelineTuning.stageBuffer)
        val trackedExtractedTask =
            tryAsyncWith(trackedExtractedChannel, Dispatchers.Default) {
                for (item in extractedChannel) {
                    loaded++
                    dispatchProgress()
                    trackedExtractedChannel.send(item)
                }
                onProgress(IndexingProgress.Indeterminate)
            }
        val library = evaluateStep.evaluate(trackedExtractedChannel)
        merge(exploredTask, extractedTask, trackedExploredTask, trackedExtractedTask).await()
        // Report the tally before the library is handed out, so an empty result always has a
        // matching explanation in logcat.
        ScanDiagnostics.logSummary(library.songs.size)
        LibraryResultImpl(config, library)
    }
}

private class LibraryResultImpl(private val config: Config, override val library: MutableLibrary) :
    LibraryResult {
    override suspend fun cleanup() {
        config.storage.covers.cleanup(library.songs.mapNotNull { it.cover })
    }
}
