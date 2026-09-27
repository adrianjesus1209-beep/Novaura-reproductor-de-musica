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
import org.oxycblt.musikr.cache.CachedFile
import org.oxycblt.musikr.covers.CoverResult
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
        val filesTask = fs.explore(files)

        val classified = Channel<Classified>(PipelineTuning.stageBuffer)
        val classifiedTask =
            scope.mapParallel(PipelineTuning.parallelism, files, classified, Dispatchers.IO) { file
                ->
                val pathStr = file.path.components.unixString.lowercase()
                if (EXCLUDED_PATH_MARKERS.any { pathStr.contains(it) }) {
                    return@mapParallel Finalized(NotAudio)
                }
                val ext = file.path.name?.substringAfterLast('.', "")?.lowercase() ?: ""
                if (ext.isNotEmpty() && ext in EXCLUDED_EXTENSIONS) {
                    return@mapParallel Finalized(NotAudio)
                }
                if (
                    file.mimeType == M3U.MIME_TYPE ||
                        (!file.mimeType.startsWith("audio/") &&
                            file.mimeType != "application/ogg" &&
                            file.mimeType != "application/x-ogg" &&
                            file.mimeType != "application/octet-stream" &&
                            ext !in VALID_AUDIO_EXTENSIONS)
                ) {
                    return@mapParallel Finalized(NotAudio)
                }
                when (val cacheResult = storage.cache.read(file)) {
                    is CacheResult.Hit -> NeedsHydration(cacheResult.file)
                    is CacheResult.Stale -> Finalized(NewSong(cacheResult.file))
                    is CacheResult.Miss -> Finalized(NewSong(cacheResult.file))
                }
            }

        val finalized = Channel<Finalized>(PipelineTuning.stageBuffer)
        val exploredTask =
            scope.mapParallel(PipelineTuning.parallelism, classified, finalized, Dispatchers.IO) {
                item ->
                when (item) {
                    is Finalized -> item
                    is NeedsHydration -> {
                        val audio = item.cachedFile.audio ?: return@mapParallel Finalized(NotAudio)
                        if (
                            audio.tags.durationMs in 1 until 30000L ||
                                audio.properties.durationMs in 1 until 30000L
                        ) {
                            return@mapParallel Finalized(NotAudio)
                        }
                        val coverId =
                            when (
                                val result = audio.coverId?.let { id -> storage.covers.obtain(id) }
                            ) {
                                is CoverResult.Hit -> result.cover
                                is CoverResult.Miss ->
                                    return@mapParallel Finalized(NewSong(item.cachedFile.file))
                                null -> null
                            }

                        Finalized(
                            RawSong(
                                item.cachedFile.file,
                                audio.properties,
                                audio.tags,
                                coverId,
                                item.cachedFile.addedMs,
                            )
                        )
                    }
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
                    it.send(item.explored)
                }
                for (playlist in playlists) {
                    it.send(playlist)
                }
            }

        return scope.merge(filesTask, classifiedTask, exploredTask, playlistsTask, mergeTask)
    }

    private sealed interface Classified

    private data class NeedsHydration(val cachedFile: CachedFile) : Classified

    private data class Finalized(val explored: Explored) : Classified

    private companion object {
        val EXCLUDED_PATH_MARKERS =
            listOf(
                "android/",
                "whatsapp/",
                "telegram/",
                "recordings/",
                "call/",
                "callrecordings/",
                "call recordings/",
                "callrecorder/",
                "voicenotes/",
                "voice notes/",
                "voicerecordings/",
                "soundrecorder/",
                "recorder/",
                "ringtones/",
                "notifications/",
                "alarms/",
                "ui/",
                "system/",
            )

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
                "opus",
                "aac",
                "wma",
                "alac",
                "aiff",
                "3gp",
                "m4b",
            )
    }
}
