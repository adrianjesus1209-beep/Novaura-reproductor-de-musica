/*
 * Copyright (c) 2026 Auxio Project
 * DownloadRepository.kt is part of Auxio.
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
 
package org.oxycblt.auxio.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import timber.log.Timber as L

data class OnlineSong(
    val id: String,
    val title: String,
    val artist: String,
    val durationText: String,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val webUrl: String,
    val viewCount: Long = 0L,
) : java.io.Serializable

sealed interface DownloadState {
    data object Idle : DownloadState

    data class Downloading(val progressPercent: Int) : DownloadState

    data class Completed(val uri: Uri?) : DownloadState

    data class Error(val message: String) : DownloadState
}

@Singleton
class DownloadRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val downloader = NewPipeDownloader.getInstance()

    init {
        try {
            NewPipe.init(downloader)
        } catch (e: Exception) {
            L.e(e, "Failed to initialize NewPipe")
        }
    }

    suspend fun search(query: String): List<OnlineSong> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            try {
                val searchExtractor = ServiceList.YouTube.getSearchExtractor(query)
                searchExtractor.fetchPage()
                val page = searchExtractor.initialPage

                page.items.filterIsInstance<StreamInfoItem>().map { item ->
                    val cleanUploader =
                        item.uploaderName
                            ?.removeSuffix(" - Topic")
                            ?.removeSuffix(" Official")
                            ?.removeSuffix(" - Official")
                            ?.trim()
                            ?.ifEmpty { null }
                    val rawName = item.name ?: "Desconocido"
                    val (extractedArtist, extractedTitle) =
                        if (rawName.contains(" - ")) {
                            val parts = rawName.split(" - ")
                            val a = parts[0].trim()
                            val t = parts.drop(1).joinToString(" - ").trim()
                            if (a.isNotEmpty() && t.isNotEmpty()) {
                                a to t
                            } else {
                                null to rawName
                            }
                        } else {
                            null to rawName
                        }

                    val finalArtist = cleanUploader ?: extractedArtist ?: "Artista desconocido"
                    val finalTitle =
                        if (extractedArtist != null && cleanUploader != null) extractedTitle
                        else rawName

                    OnlineSong(
                        id = item.url ?: "",
                        title = finalTitle,
                        artist = finalArtist,
                        durationText = formatDuration(item.duration),
                        durationSeconds = item.duration,
                        thumbnailUrl = item.thumbnails.maxByOrNull { it.width }?.url,
                        webUrl = item.url ?: "",
                        viewCount = item.viewCount,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e(e, "Search failed for query: $query")
                emptyList()
            }
        }

    suspend fun extractAudioStreamUrl(webUrl: String): Stream? =
        withContext(Dispatchers.IO) {
            try {
                val fullUrl =
                    if (webUrl.startsWith("http://") || webUrl.startsWith("https://")) {
                        webUrl
                    } else {
                        "https://www.youtube.com$webUrl"
                    }
                val streamExtractor = ServiceList.YouTube.getStreamExtractor(fullUrl)
                streamExtractor.fetchPage()
                val audioStreams = streamExtractor.audioStreams.orEmpty()
                // Prefer M4A / AAC with good bitrate, or highest bitrate audio stream
                val selectedAudio =
                    audioStreams
                        .filter { it.format?.name.equals("m4a", ignoreCase = true) }
                        .maxByOrNull { it.averageBitrate }
                        ?: audioStreams.maxByOrNull { it.averageBitrate }
                        ?: audioStreams.firstOrNull()

                selectedAudio ?: streamExtractor.videoStreams?.firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e(e, "Failed to extract audio stream for: $webUrl")
                null
            }
        }

    suspend fun downloadSong(
        song: OnlineSong,
        onProgress: (Int) -> Unit = {},
    ): Uri? =
        withContext(Dispatchers.IO) {
            val audioStream = extractAudioStreamUrl(song.webUrl) ?: return@withContext null
            val streamUrl = audioStream.content
            val extension =
                if (audioStream.format?.name.equals("m4a", ignoreCase = true)) {
                    "m4a"
                } else if (audioStream.format?.name.equals("mp4", ignoreCase = true)) {
                    "mp4"
                } else {
                    "opus"
                }
            val mimeType =
                when (extension) {
                    "m4a",
                    "mp4" -> "audio/mp4"
                    else -> "audio/ogg"
                }

            val sanitizedTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            val sanitizedArtist = song.artist.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            val folderName =
                if (sanitizedArtist.isNotEmpty() && sanitizedArtist != "Artista desconocido") {
                    "$sanitizedArtist - $sanitizedTitle"
                } else {
                    sanitizedTitle
                }
            val displayName =
                if (sanitizedArtist.isNotEmpty() && sanitizedArtist != "Artista desconocido") {
                    "$sanitizedArtist - $sanitizedTitle.$extension"
                } else {
                    "$sanitizedTitle.$extension"
                }

            var insertedUri: Uri? = null
            var legacyFile: File? = null
            try {
                val request = Request.Builder().url(streamUrl).build()
                downloader.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        L.e("Failed to download audio stream, code: ${response.code}")
                        return@withContext null
                    }

                    val body = response.body ?: return@withContext null
                    val contentLength = body.contentLength()

                    val resultUri: Uri?
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val relativePath = "${Environment.DIRECTORY_MUSIC}/Novaura/$folderName"
                        val contentValues =
                            ContentValues().apply {
                                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                                put(MediaStore.Audio.Media.TITLE, song.title)
                                put(MediaStore.Audio.Media.ARTIST, song.artist)
                                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                                put(MediaStore.Audio.Media.IS_PENDING, 1)
                            }

                        val uri =
                            context.contentResolver.insert(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                contentValues,
                            ) ?: return@withContext null
                        insertedUri = uri

                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            copyStreamWithProgress(
                                body.byteStream(),
                                output,
                                contentLength,
                                onProgress,
                            )
                        }

                        contentValues.clear()
                        contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                        context.contentResolver.update(uri, contentValues, null, null)
                        insertedUri = null

                        val physicalDir =
                            File(
                                Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_MUSIC
                                ),
                                "Novaura/$folderName",
                            )
                        if (!physicalDir.exists()) {
                            physicalDir.mkdirs()
                        }

                        // Download thumbnail into internal download_covers and cover.jpg
                        downloadThumbnail(song.thumbnailUrl, folderName, displayName, song)

                        val audioFile = File(physicalDir, displayName)
                        val coverFile = File(physicalDir, "cover.jpg")
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(audioFile.absolutePath, coverFile.absolutePath),
                            arrayOf(mimeType, "image/jpeg"),
                            null,
                        )

                        L.d("Song downloaded successfully via MediaStore to: $uri")
                        resultUri = uri
                    } else {
                        val musicDir =
                            File(
                                Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_MUSIC
                                ),
                                "Novaura/$folderName",
                            )
                        if (!musicDir.exists()) {
                            musicDir.mkdirs()
                        }
                        val destFile = File(musicDir, displayName)
                        legacyFile = destFile
                        FileOutputStream(destFile).use { output ->
                            copyStreamWithProgress(
                                body.byteStream(),
                                output,
                                contentLength,
                                onProgress,
                            )
                        }
                        legacyFile = null
                        downloadThumbnail(song.thumbnailUrl, folderName, displayName, song)
                        val coverFile = File(musicDir, "cover.jpg")
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(destFile.absolutePath, coverFile.absolutePath),
                            arrayOf(mimeType, "image/jpeg"),
                            null,
                        )
                        L.d("Song downloaded successfully to file: ${destFile.absolutePath}")
                        resultUri = Uri.fromFile(destFile)
                    }
                    resultUri
                }
            } catch (e: CancellationException) {
                insertedUri?.let {
                    runCatching { context.contentResolver.delete(it, null, null) }
                }
                legacyFile?.let {
                    runCatching { it.delete() }
                }
                throw e
            } catch (e: Exception) {
                insertedUri?.let {
                    runCatching { context.contentResolver.delete(it, null, null) }
                }
                legacyFile?.let {
                    runCatching { it.delete() }
                }
                L.e(e, "Exception during download of ${song.title}")
                null
            }
        }

    private suspend fun copyStreamWithProgress(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long,
        onProgress: (Int) -> Unit,
    ) {
        val buffer = ByteArray(8 * 1024)
        var bytesRead: Int
        var totalRead = 0L
        var lastReportedPercent = -1

        input.use { inStream ->
            output.use { outStream ->
                while (inStream.read(buffer).also { bytesRead = it } != -1) {
                    currentCoroutineContext().ensureActive()
                    outStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (totalBytes > 0) {
                        val percent = ((totalRead * 100) / totalBytes).toInt()
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            onProgress(percent)
                        }
                    }
                }
                outStream.flush()
            }
        }
    }

    private fun downloadThumbnail(
        thumbnailUrl: String?,
        folderName: String,
        displayName: String,
        song: OnlineSong? = null,
    ) {
        if (thumbnailUrl.isNullOrBlank()) return
        try {
            val req = Request.Builder().url(thumbnailUrl).build()
            val bytes =
                downloader.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        L.w("Failed to download thumbnail, code: ${resp.code}")
                        return
                    }
                    resp.body?.bytes() ?: return
                }
            if (bytes.isEmpty()) return

            // 1. Bulletproof internal storage: context.filesDir/download_covers
            val downloadCoversDir = File(context.filesDir, "download_covers").apply { mkdirs() }
            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
            val displayNameWithoutExt = displayName.substringBeforeLast('.')

            val keys = mutableListOf<String>()
            keys.add("folder_${org.oxycblt.musikr.covers.CoverUtils.toMd5(folderName)}.jpg")
            keys.add("folder_${org.oxycblt.musikr.covers.CoverUtils.toSafeFsName(folderName)}.jpg")
            keys.add("${org.oxycblt.musikr.covers.CoverUtils.toSafeFsName(folderName)}.jpg")
            keys.add(
                "file_${org.oxycblt.musikr.covers.CoverUtils.toMd5(displayNameWithoutExt)}.jpg"
            )
            keys.add(
                "file_${org.oxycblt.musikr.covers.CoverUtils.toSafeFsName(displayNameWithoutExt)}.jpg"
            )
            keys.add(
                "${org.oxycblt.musikr.covers.CoverUtils.toSafeFsName(displayNameWithoutExt)}.jpg"
            )

            if (song != null) {
                val metaKey = "${song.artist}_${song.title}"
                keys.add("meta_${org.oxycblt.musikr.covers.CoverUtils.toMd5(metaKey)}.jpg")
                keys.add("meta_${org.oxycblt.musikr.covers.CoverUtils.toSafeFsName(metaKey)}.jpg")
            }

            for (key in keys) {
                try {
                    File(downloadCoversDir, key).writeBytes(bytes)
                    File(coversDir, key).writeBytes(bytes)
                } catch (e: Exception) {
                    L.w(e, "Error writing cover key: $key")
                }
            }
            L.d("Thumbnail saved to download_covers with ${keys.size} keys")

            // 2. Best-effort direct filesystem write to Music/Novaura/folderName/cover.jpg
            try {
                val physicalDir =
                    File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                        "Novaura/$folderName",
                    )
                if (physicalDir.exists() || physicalDir.mkdirs()) {
                    val coverFile = File(physicalDir, "cover.jpg")
                    coverFile.writeBytes(bytes)
                    L.d("cover.jpg written directly to ${coverFile.absolutePath}")
                }
            } catch (fe: Exception) {
                L.w(fe, "Direct file write for cover.jpg failed")
            }
        } catch (e: Exception) {
            L.w(e, "Failed to download thumbnail")
        }
    }

    suspend fun backfillCoverForSong(
        songTitle: String,
        songArtist: String,
        folderName: String,
        displayName: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val downloadCoversDir = File(context.filesDir, "download_covers")
                val key = "folder_${org.oxycblt.musikr.covers.CoverUtils.toMd5(folderName)}.jpg"
                if (File(downloadCoversDir, key).exists()) {
                    return@withContext true
                }
                val searchQuery = "$songArtist $songTitle".replace("Artista desconocido", "").trim()
                if (searchQuery.isBlank()) return@withContext false
                val results = search(searchQuery)
                val match = results.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }
                if (match?.thumbnailUrl != null) {
                    downloadThumbnail(match.thumbnailUrl, folderName, displayName, match)
                    return@withContext true
                }
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w(e, "Failed to backfill cover for $songTitle")
                false
            }
        }

    private fun formatDuration(durationSeconds: Long): String {
        if (durationSeconds <= 0) return "--:--"
        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}
