/*
 * Copyright (c) 2024 Auxio Project
 * ExploreStep.kt is part of Auxio.
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

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import org.oxycblt.musikr.Config
import org.oxycblt.musikr.Storage
import org.oxycblt.musikr.cache.CacheResult
import org.oxycblt.musikr.covers.CoverResult
import org.oxycblt.musikr.fs.ExcludedPaths
import org.oxycblt.musikr.fs.FS
import org.oxycblt.musikr.fs.File
import org.oxycblt.musikr.playlist.m3u.M3U
import org.oxycblt.musikr.util.mapParallel
import org.oxycblt.musikr.util.merge
import org.oxycblt.musikr.util.tryAsyncWith

internal interface ExploreStep {
    suspend fun explore(scope: CoroutineScope, explored: Channel<Explored>): Deferred<Result<Unit>>

    companion object {
        fun from(context: Context, config: Config): ExploreStep =
            ExploreStepImpl(config.fs, config.storage)
    }
}

private class ExploreStepImpl(private val fs: FS, private val storage: Storage) : ExploreStep {
    override suspend fun explore(
        scope: CoroutineScope,
        explored: Channel<Explored>,
    ): Deferred<Result<Unit>> {
        val files = Channel<File>(PipelineTuning.stageBuffer)
        val filesTask = fs.explore(scope, files)

        val finalized = Channel<Explored>(PipelineTuning.stageBuffer)
        val exploredTask =
            scope.mapParallel(PipelineTuning.parallelism, files, finalized, Dispatchers.IO) { file
                ->
                if (ExcludedPaths.isExcluded(file.path.components)) {
                    ScanDiagnostics.recordRejected(ScanReject.EXCLUDED_PATH)
                    return@mapParallel NotAudio
                }
                val ext = file.path.name?.substringAfterLast('.', "")?.lowercase() ?: ""
                if (ext.isNotEmpty() && ext in EXCLUDED_EXTENSIONS) {
                    ScanDiagnostics.recordRejected(ScanReject.EXCLUDED_EXTENSION)
                    return@mapParallel NotAudio
                }
                if (
                    file.mimeType == M3U.MIME_TYPE ||
                        (!file.mimeType.startsWith("audio/") &&
                            file.mimeType != "application/ogg" &&
                            file.mimeType != "application/x-ogg" &&
                            file.mimeType != "application/octet-stream" &&
                            ext !in VALID_AUDIO_EXTENSIONS)
                ) {
                    ScanDiagnostics.recordRejected(ScanReject.UNSUPPORTED_TYPE)
                    return@mapParallel NotAudio
                }
                when (val cacheResult = storage.cache.read(file)) {
                    is CacheResult.Hit -> {
                        val audio = cacheResult.file.audio
                        if (audio == null) {
                            ScanDiagnostics.recordRejected(ScanReject.NO_METADATA)
                            return@mapParallel NotAudio
                        }
                        // Only the parsed tag duration is checked. The same file is also measured
                        // by its raw properties during extraction, and rejecting on either
                        // measurement
                        // made a single TagLib mis-report enough to drop a song forever.
                        if (audio.tags.durationMs in 1 until MIN_PLAUSIBLE_DURATION_MS) {
                            ScanDiagnostics.recordRejected(ScanReject.DURATION_TOO_SHORT)
                            return@mapParallel NotAudio
                        }
                        val coverId =
                            when (
                                val result = audio.coverId?.let { id -> storage.covers.obtain(id) }
                            ) {
                                is CoverResult.Hit -> result.cover
                                is CoverResult.Miss ->
                                    return@mapParallel NewSong(cacheResult.file.file)
                                null -> null
                            }

                        RawSong(
                            cacheResult.file.file,
                            audio.properties,
                            audio.tags,
                            coverId,
                            cacheResult.file.addedMs,
                        )
                    }
                    is CacheResult.Stale -> NewSong(cacheResult.file)
                    is CacheResult.Miss -> NewSong(cacheResult.file)
                }
            }

        val playlists = Channel<Explored>(PipelineTuning.stageBuffer)
        val playlistsTask =
            scope.tryAsyncWith(playlists, Dispatchers.IO) {
                for (playlist in storage.storedPlaylists.read()) {
                    val rawPlaylist = RawPlaylist(playlist)
                    it.send(rawPlaylist)
                }
            }

        val mergeTask =
            scope.tryAsyncWith(explored, Dispatchers.Default) {
                for (item in finalized) {
                    it.send(item)
                }
                for (playlist in playlists) {
                    it.send(playlist)
                }
            }

        return scope.merge(filesTask, exploredTask, playlistsTask, mergeTask)
    }

    private companion object {
        /** Derived from [PipelineTuning] so every stage judges a file against the same duration. */
        val MIN_PLAUSIBLE_DURATION_MS = PipelineTuning.MIN_PLAUSIBLE_DURATION_MS

        val EXCLUDED_EXTENSIONS =
            setOf(
                "nomedia",
                "txt",
                "jpg",
                "png",
                "jpeg",
                "gif",
                "webp",
                "xml",
                "json",
                "db",
                "pdf",
                "zip",
                "apk",
                "amr",
                "3ga",
            )

        val VALID_AUDIO_EXTENSIONS =
            setOf(
                "mp3",
                "wav",
                "flac",
                "m4a",
                "ogg",
                "oga",
                "opus",
                "aac",
                "wma",
                "alac",
                "aiff",
                "aif",
                "aifc",
                "ape",
                "wv",
                "tta",
                "mka",
                "mpc",
                "dsf",
                "dff",
                "3gp",
                "3gpp",
                "m4b",
                "m4p",
                "mid",
                "midi",
            )
    }
}
