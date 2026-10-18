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
import kotlinx.coroutines.Dispatchers
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
) : java.io.Serializable

sealed interface DownloadState {
    data object Idle : DownloadState

    data class Downloading(val progressPercent: Int) : DownloadState

    data class Completed(val uri: Uri?) : DownloadState

    data class Error(val message: String) : DownloadState
}

@Singleton
class DownloadRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val downloader = NewPipeDownloader()

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
                    OnlineSong(
                        id = item.url ?: "",
                        title = item.name ?: "Desconocido",
                        artist = item.uploaderName ?: "Artista desconocido",
                        durationText = formatDuration(item.duration),
                        durationSeconds = item.duration,
                        thumbnailUrl = item.thumbnails.maxByOrNull { it.width }?.url,
                        webUrl = item.url ?: "",
                    )
                }
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

            try {
                val request = Request.Builder().url(streamUrl).build()
                val response = downloader.client.newCall(request).execute()
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

                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        copyStreamWithProgress(body.byteStream(), output, contentLength, onProgress)
                    }

                    contentValues.clear()
                    contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, contentValues, null, null)

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

                    // Download thumbnail into cover.jpg inside the same folder so Auxio/Novaura
                    // displays it
                    downloadThumbnail(song.thumbnailUrl, folderName, relativePath)

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
                    FileOutputStream(destFile).use { output ->
                        copyStreamWithProgress(body.byteStream(), output, contentLength, onProgress)
                    }
                    downloadThumbnailLegacy(song.thumbnailUrl, musicDir)
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
            } catch (e: Exception) {
                L.e(e, "Exception during download of ${song.title}")
                null
            }
        }

    private fun copyStreamWithProgress(
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
        relativePath: String,
    ) {
        if (thumbnailUrl.isNullOrBlank()) return
        try {
            val req = Request.Builder().url(thumbnailUrl).build()
            val resp = downloader.client.newCall(req).execute()
            if (!resp.isSuccessful) return
            val bytes = resp.body?.bytes() ?: return

            // 1. Direct file write to Music/Novaura/folderName/cover.jpg
            val physicalDir =
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "Novaura/$folderName",
                )
            if (!physicalDir.exists()) {
                physicalDir.mkdirs()
            }
            val coverFile = File(physicalDir, "cover.jpg")
            try {
                coverFile.writeBytes(bytes)
                L.d("cover.jpg written directly to ${coverFile.absolutePath}")
            } catch (fe: Exception) {
                L.w(fe, "Direct file write for cover.jpg failed")
            }

            // 2. On Android 10+ (Q+), also register via MediaStore.Files so system indexers see it
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val cv =
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, "cover.jpg")
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                    val uri =
                        context.contentResolver.insert(
                            MediaStore.Files.getContentUri("external"),
                            cv,
                        )
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    }
                } catch (me: Exception) {
                    L.w(me, "Failed to insert cover.jpg via MediaStore.Files")
                }
            }
        } catch (e: Exception) {
            L.w(e, "Failed to download thumbnail")
        }
    }

    private fun downloadThumbnailLegacy(thumbnailUrl: String?, targetDir: File) {
        if (thumbnailUrl.isNullOrBlank()) return
        try {
            val req = Request.Builder().url(thumbnailUrl).build()
            val resp = downloader.client.newCall(req).execute()
            if (!resp.isSuccessful) return
            val bytes = resp.body?.bytes() ?: return
            val coverFile = File(targetDir, "cover.jpg")
            coverFile.writeBytes(bytes)
        } catch (e: Exception) {
            L.w(e, "Failed to save legacy cover.jpg")
        }
    }

    private fun formatDuration(durationSeconds: Long): String {
        if (durationSeconds <= 0) return "--:--"
        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}
