/*
 * Copyright (c) 2025 Auxio Project
 * MediaStore.kt is part of Auxio.
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
 
package org.oxycblt.musikr.fs.mediastore

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore as AOSPMediaStore
import androidx.core.database.getStringOrNull
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.oxycblt.musikr.fs.AddedMs
import org.oxycblt.musikr.fs.FS
import org.oxycblt.musikr.fs.FSUpdate
import org.oxycblt.musikr.fs.File
import org.oxycblt.musikr.fs.Location
import org.oxycblt.musikr.fs.path.MediaStorePathInterpreter
import org.oxycblt.musikr.fs.path.VolumeManager
import org.oxycblt.musikr.fs.saf.contentResolverSafe
import org.oxycblt.musikr.fs.saf.useQuery
import org.oxycblt.musikr.fs.track.LocationObserver
import org.oxycblt.musikr.util.tryAsyncWith

/**
 * MediaStore implementation of [FS] that queries the Android MediaStore database for audio files
 * and yields them as [File] instances using high-speed indexed queries.
 */
class MediaStore
private constructor(
    private val context: Context,
    private val volumeManager: VolumeManager,
    private val query: Query,
) : FS {
    private val pathInterpreterFactory = MediaStorePathInterpreter.Factory.from(volumeManager)

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun explore(files: Channel<File>): Deferred<Result<Unit>> = coroutineScope {
        tryAsyncWith(files, Dispatchers.IO) { channel ->
            val baseProjection = BASE_PROJECTION + pathInterpreterFactory.projection
            val projection =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    !baseProjection.contains(AOSPMediaStore.Audio.AudioColumns.VOLUME_NAME)
                ) {
                    // Rows on API 29+ may reside on different volumes (e.g. a microSD), so we
                    // need the row's volume to build a file URI that can actually be opened for
                    // playback instead of the aggregate "external" volume.
                    baseProjection + AOSPMediaStore.Audio.AudioColumns.VOLUME_NAME
                } else {
                    baseProjection
                }
            var selector = BASE_SELECTOR
            val args = mutableListOf<String>()

            // Filter out audio that is not music, if enabled
            if (query.excludeNonMusic) {
                selector += " AND ${AOSPMediaStore.Audio.AudioColumns.IS_MUSIC} != 0"
            }

            // Handle include/exclude directories
            when (query.mode) {
                FilterMode.INCLUDE -> {
                    val pathSelector =
                        pathInterpreterFactory.createSelector(query.filtered.map { it.path })
                    if (pathSelector != null) {
                        selector += " AND (${pathSelector.template})"
                        args.addAll(pathSelector.args)
                    }
                }
                FilterMode.EXCLUDE -> {
                    val pathSelector =
                        pathInterpreterFactory.createSelector(query.filtered.map { it.path })
                    if (pathSelector != null) {
                        selector += " AND NOT (${pathSelector.template})"
                        args.addAll(pathSelector.args)
                    }
                }
            }

            // Collect all files and track unique directories
            val allFiles = mutableListOf<File>()

            // Query MediaStore.VOLUME_EXTERNAL on API 29+ to include mounted secondary/MicroSD storage
            val mediaUri =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    AOSPMediaStore.Audio.Media.getContentUri(AOSPMediaStore.VOLUME_EXTERNAL)
                } else {
                    AOSPMediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }

            context.contentResolverSafe.useQuery(
                mediaUri,
                projection,
                selector,
                args.toTypedArray(),
            ) { cursor ->
                val pathInterpreter = pathInterpreterFactory.wrap(cursor)
                val idIndex = cursor.getColumnIndexOrThrow(AOSPMediaStore.Audio.AudioColumns._ID)
                val mimeTypeIndex =
                    cursor.getColumnIndexOrThrow(AOSPMediaStore.Audio.AudioColumns.MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(AOSPMediaStore.Audio.AudioColumns.SIZE)
                val dateAddedIndex =
                    cursor.getColumnIndexOrThrow(AOSPMediaStore.Audio.AudioColumns.DATE_ADDED)
                val dateModifiedIndex =
                    cursor.getColumnIndexOrThrow(AOSPMediaStore.Audio.AudioColumns.DATE_MODIFIED)
                val volumeIndex =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndexOrThrow(AOSPMediaStore.Audio.AudioColumns.VOLUME_NAME)
                    } else {
                        -1
                    }

                while (cursor.moveToNext()) {
                    val path = pathInterpreter.extract() ?: continue

                    // Strict filter: exclude system folders (/Android/data/, /Android/media/)
                    // and messaging/call folders (WhatsApp/, Telegram/, Recordings/, Call/)
                    if (isExcludedPath(path.components.unixString)) {
                        continue
                    }

                    val id = cursor.getLong(idIndex)
                    val uri =
                        if (volumeIndex != -1) {
                            val volumeName = cursor.getStringOrNull(volumeIndex)
                            val baseUri =
                                if (volumeName != null) {
                                    AOSPMediaStore.Audio.Media.getContentUri(volumeName)
                                } else {
                                    mediaUri
                                }
                            Uri.withAppendedPath(baseUri, id.toString())
                        } else {
                            Uri.withAppendedPath(mediaUri, id.toString())
                        }
                    val mimeType = cursor.getStringOrNull(mimeTypeIndex) ?: "audio/*"
                    val size = cursor.getLong(sizeIndex)
                    val dateAdded = cursor.getLong(dateAddedIndex) * 1000 // Convert to milliseconds
                    val dateModified =
                        cursor.getLong(dateModifiedIndex) * 1000 // Convert to milliseconds

                    // Create file with empty deferred parent
                    val deviceFile =
                        File(
                            uri = uri,
                            path = path,
                            modifiedMs = dateModified,
                            mimeType = mimeType,
                            size = size,
                            addedMs = ForwardDateAdded(dateAdded),
                            parent = null,
                        )

                    allFiles.add(deviceFile)
                    channel.send(deviceFile)
                }
            }
        }
    }

    override fun track(): Flow<FSUpdate> = callbackFlow {
        val mediaUri =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                AOSPMediaStore.Audio.Media.getContentUri(AOSPMediaStore.VOLUME_EXTERNAL)
            } else {
                AOSPMediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
        val observer =
            LocationObserver(context, mediaUri) {
                trySend(FSUpdate.LocationChanged(null))
            }
        awaitClose { observer.release() }
    }

    data class Query(
        val mode: FilterMode,
        val filtered: List<Location.Unopened>,
        val excludeNonMusic: Boolean,
    )

    enum class FilterMode {
        INCLUDE,
        EXCLUDE,
    }

    private class ForwardDateAdded(val dateAdded: Long) : AddedMs {
        override suspend fun resolve() = dateAdded
    }

    companion object {
        fun from(context: Context, query: Query) =
            MediaStore(
                context = context,
                volumeManager = VolumeManager.from(context),
                query = query,
            )

        private fun isExcludedPath(path: String): Boolean {
            val lower = path.lowercase()
            return EXCLUDED_PATH_MARKERS.any { lower.contains(it) }
        }

        private val EXCLUDED_PATH_MARKERS =
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
            )

        /**
         * Direct indexed query selector:
         * Excludes zero-size files, non-music (IS_MUSIC != 0), tracks shorter than 30s (DURATION >= 30000 ms),
         * and system/messaging/call/voice-note directories.
         */
        private const val BASE_SELECTOR =
            "NOT ${AOSPMediaStore.Audio.Media.SIZE}=0 " +
            "AND ${AOSPMediaStore.Audio.AudioColumns.IS_MUSIC} != 0 " +
            "AND ${AOSPMediaStore.Audio.AudioColumns.DURATION} >= 30000 " +
            "AND ${AOSPMediaStore.Audio.AudioColumns.DATA} NOT LIKE '%/Android/%' " +
            "AND ${AOSPMediaStore.Audio.AudioColumns.DATA} NOT LIKE '%/WhatsApp/%' " +
            "AND ${AOSPMediaStore.Audio.AudioColumns.DATA} NOT LIKE '%/Telegram/%' " +
            "AND ${AOSPMediaStore.Audio.AudioColumns.DATA} NOT LIKE '%/Recordings/%' " +
            "AND ${AOSPMediaStore.Audio.AudioColumns.DATA} NOT LIKE '%/Call/%' " +
            "AND ${AOSPMediaStore.Audio.AudioColumns.DATA} NOT LIKE '%/CallRecordings/%' " +
            "AND ${AOSPMediaStore.Audio.AudioColumns.DATA} NOT LIKE '%/Call Recordings/%' " +
            "AND ${AOSPMediaStore.Audio.AudioColumns.DATA} NOT LIKE '%/CallRecorder/%' " +
            "AND ${AOSPMediaStore.Audio.AudioColumns.DATA} NOT LIKE '%/VoiceNotes/%' " +
            "AND ${AOSPMediaStore.Audio.AudioColumns.DATA} NOT LIKE '%/Voice Notes/%' " +
            "AND ${AOSPMediaStore.Audio.AudioColumns.DATA} NOT LIKE '%/VoiceRecordings/%' " +
            "AND ${AOSPMediaStore.Audio.AudioColumns.DATA} NOT LIKE '%/SoundRecorder/%' " +
            "AND ${AOSPMediaStore.Audio.AudioColumns.DATA} NOT LIKE '%/Recorder/%'"

        /** Base projection strictly limiting extracted columns (ID, Title, Artist, Album, Data, Duration + file attributes). */
        private val BASE_PROJECTION =
            arrayOf(
                AOSPMediaStore.Audio.AudioColumns._ID,
                AOSPMediaStore.Audio.AudioColumns.TITLE,
                AOSPMediaStore.Audio.AudioColumns.ARTIST,
                AOSPMediaStore.Audio.AudioColumns.ALBUM,
                AOSPMediaStore.Audio.AudioColumns.DATA,
                AOSPMediaStore.Audio.AudioColumns.DURATION,
                AOSPMediaStore.Audio.AudioColumns.DATE_ADDED,
                AOSPMediaStore.Audio.AudioColumns.DATE_MODIFIED,
                AOSPMediaStore.Audio.AudioColumns.SIZE,
                AOSPMediaStore.Audio.AudioColumns.MIME_TYPE,
            )
    }
}

