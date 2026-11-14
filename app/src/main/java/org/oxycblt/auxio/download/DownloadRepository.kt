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

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.oxycblt.musikr.covers.CoverUtils
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import timber.log.Timber as L

@kotlinx.parcelize.Parcelize
data class OnlineSong(
    val id: String,
    val title: String,
    val artist: String,
    val durationText: String,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val webUrl: String,
    val viewCount: Long = 0L,
) : android.os.Parcelable

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

    private val stalePendingCleaned = AtomicBoolean(false)
    private val processStartSeconds = System.currentTimeMillis() / 1000L

    private fun tempDir(): File = File(context.cacheDir, "download_tmp").apply { mkdirs() }

    private fun tempFileFor(webUrl: String): File =
        File(tempDir(), "${CoverUtils.toMd5(webUrl)}.part")

    fun cleanupTempFile(webUrl: String) {
        runCatching {
            val file = tempFileFor(webUrl)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    fun cleanupAllTempFiles() {
        runCatching {
            tempDir().listFiles()?.forEach { file ->
                if (file.isFile) {
                    file.delete()
                }
            }
        }
    }

    private fun cleanupStalePendingMediaStoreRows() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!stalePendingCleaned.compareAndSet(false, true)) return
        try {
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val selection =
                "${MediaStore.Audio.Media.IS_PENDING} = 1 AND " +
                    "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.Audio.Media.DATE_ADDED} < ?"
            val args =
                arrayOf(
                    "${Environment.DIRECTORY_MUSIC}/Novaura/%",
                    processStartSeconds.toString(),
                )
            val deleted = context.contentResolver.delete(uri, selection, args)
            if (deleted > 0) {
                L.d("Cleaned up $deleted stale pending MediaStore rows")
            }
        } catch (e: Exception) {
            L.w(e, "Failed to clean up stale pending MediaStore rows")
        }
    }

    suspend fun extractAudioStreamUrl(webUrl: String): AudioStream? =
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
                val progressiveAudio =
                    streamExtractor.audioStreams.orEmpty().filter {
                        !it.content.isNullOrBlank() &&
                            (it.deliveryMethod == null ||
                                it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP)
                    }
                // Prefer M4A / MP4 for maximum TagLib, MediaStore and ExoPlayer compatibility
                progressiveAudio
                    .filter {
                        it.format == MediaFormat.M4A ||
                            it.format == MediaFormat.MPEG_4 ||
                            it.format?.name.equals("m4a", ignoreCase = true)
                    }
                    .maxByOrNull { it.averageBitrate }
                    ?: progressiveAudio.maxByOrNull { it.averageBitrate }
                    ?: progressiveAudio.firstOrNull()
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
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) != PackageManager.PERMISSION_GRANTED
            ) {
                L.w("WRITE_EXTERNAL_STORAGE missing on API < 29; cannot download ${song.title}")
                return@withContext null
            }

            cleanupStalePendingMediaStoreRows()

            var audioStream = extractAudioStreamUrl(song.webUrl) ?: return@withContext null
            val (extension, mimeType) = resolveFormatSpec(audioStream)

            val sanitizedTitle = sanitizeFileComponent(song.title, fallback = "track")
            val sanitizedArtist = sanitizeFileComponent(song.artist, fallback = "")
            val hasKnownArtist =
                sanitizedArtist.isNotEmpty() &&
                    !sanitizedArtist.equals("Artista desconocido", ignoreCase = true)
            val rawBaseName =
                if (hasKnownArtist) {
                    "$sanitizedArtist - $sanitizedTitle"
                } else {
                    sanitizedTitle
                }
            val safeBaseName =
                sanitizeFileComponent(rawBaseName, fallback = "track", maxBytes = 180)
            val folderName = safeBaseName
            val displayName = "$safeBaseName.$extension"
            val partFile = tempFileFor(song.webUrl)

            var insertedUri: Uri? = null
            var legacyTempFile: File? = null
            try {
                val downloadedOk =
                    downloadToPartFile(
                        song = song,
                        initialStream = audioStream,
                        partFile = partFile,
                        onProgress = onProgress,
                    )
                if (!downloadedOk || !partFile.exists() || partFile.length() <= 0L) {
                    return@withContext null
                }

                val resultUri: Uri
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

                    val outStream = context.contentResolver.openOutputStream(uri)
                    if (outStream == null) {
                        runCatching { context.contentResolver.delete(uri, null, null) }
                        insertedUri = null
                        return@withContext null
                    }

                    partFile.inputStream().use { input ->
                        outStream.use { output ->
                            input.copyTo(output)
                            output.flush()
                        }
                    }

                    val publishValues =
                        ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                    val updated = context.contentResolver.update(uri, publishValues, null, null)
                    if (updated <= 0) {
                        L.w("MediaStore update IS_PENDING=0 returned $updated for $uri")
                    }
                    insertedUri = null
                    runCatching { partFile.delete() }

                    downloadThumbnail(song.thumbnailUrl, folderName, displayName)
                    L.d("Song downloaded successfully via MediaStore to: $uri")
                    resultUri = uri
                } else {
                    @Suppress("DEPRECATION")
                    val musicDir =
                        File(
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_MUSIC
                            ),
                            "Novaura/$folderName",
                        )
                    if (!musicDir.exists() && !musicDir.mkdirs()) {
                        L.e("Failed to create legacy download directory: ${musicDir.absolutePath}")
                        return@withContext null
                    }
                    val destFile = File(musicDir, displayName)
                    val tmpDest = File(musicDir, "$displayName.tmp")
                    legacyTempFile = tmpDest

                    partFile.inputStream().use { input ->
                        FileOutputStream(tmpDest).use { output ->
                            input.copyTo(output)
                            output.flush()
                        }
                    }
                    if (destFile.exists()) {
                        destFile.delete()
                    }
                    if (!tmpDest.renameTo(destFile)) {
                        tmpDest.copyTo(destFile, overwrite = true)
                        tmpDest.delete()
                    }
                    legacyTempFile = null
                    runCatching { partFile.delete() }

                    val thumbSaved = downloadThumbnail(song.thumbnailUrl, folderName, displayName)
                    val pathsToScan = mutableListOf(destFile.absolutePath)
                    val mimesToScan = mutableListOf(mimeType)
                    if (thumbSaved) {
                        val folderCover =
                            File(
                                File(context.filesDir, "download_covers"),
                                "folder_${CoverUtils.toMd5(folderName)}.jpg",
                            )
                        val legacyCover = File(musicDir, "cover.jpg")
                        if (folderCover.exists()) {
                            runCatching {
                                folderCover.copyTo(legacyCover, overwrite = true)
                                pathsToScan.add(legacyCover.absolutePath)
                                mimesToScan.add("image/jpeg")
                            }
                        }
                    }
                    MediaScannerConnection.scanFile(
                        context,
                        pathsToScan.toTypedArray(),
                        mimesToScan.toTypedArray(),
                        null,
                    )
                    L.d("Song downloaded successfully to file: ${destFile.absolutePath}")
                    resultUri = Uri.fromFile(destFile)
                }
                resultUri
            } catch (e: CancellationException) {
                insertedUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                legacyTempFile?.let { runCatching { it.delete() } }
                throw e
            } catch (e: Exception) {
                insertedUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                legacyTempFile?.let { runCatching { it.delete() } }
                L.e(e, "Exception during download of ${song.title}")
                null
            }
        }

    private suspend fun downloadToPartFile(
        song: OnlineSong,
        initialStream: AudioStream,
        partFile: File,
        onProgress: (Int) -> Unit,
    ): Boolean {
        var currentStream = initialStream
        for (attempt in 0 until 2) {
            currentCoroutineContext().ensureActive()
            val streamUrl = currentStream.content
            val existingBytes = if (partFile.exists()) partFile.length() else 0L
            val requestBuilder = Request.Builder().url(streamUrl)
            if (existingBytes > 0L) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
            }

            downloader.client.newCall(requestBuilder.build()).execute().use { response ->
                when (response.code) {
                    403,
                    410 -> {
                        L.w("Expired stream URL (${response.code}) for ${song.title}, retrying")
                        if (attempt == 0) {
                            currentStream = extractAudioStreamUrl(song.webUrl) ?: return false
                            return@use
                        }
                        return false
                    }
                    416 -> {
                        L.w("Range mismatch (416) for ${song.title}, restarting clean")
                        runCatching { partFile.delete() }
                        if (attempt == 0) {
                            return@use
                        }
                        return false
                    }
                }

                if (!response.isSuccessful) {
                    L.e("Failed to download audio stream, code: ${response.code}")
                    return false
                }

                val body = response.body ?: return false
                val isPartial = response.code == 206 && existingBytes > 0L
                val baseOffset = if (isPartial) existingBytes else 0L
                val remainingLength = body.contentLength()
                val totalLength = if (remainingLength > 0L) baseOffset + remainingLength else -1L

                FileOutputStream(partFile, isPartial).use { output ->
                    copyStreamWithProgress(
                        input = body.byteStream(),
                        output = output,
                        initialBytes = baseOffset,
                        totalBytes = totalLength,
                        onProgress = onProgress,
                    )
                }

                val finalLength = partFile.length()
                if (finalLength <= 0L || (totalLength > 0L && finalLength < totalLength)) {
                    L.w("Incomplete download for ${song.title}: $finalLength / $totalLength bytes")
                    return false
                }
                return true
            }
        }
        return false
    }

    private fun resolveFormatSpec(audioStream: AudioStream): Pair<String, String> =
        when (audioStream.format) {
            MediaFormat.M4A,
            MediaFormat.MPEG_4 -> "m4a" to "audio/mp4"
            MediaFormat.MP3 -> "mp3" to "audio/mpeg"
            MediaFormat.WEBMA,
            MediaFormat.WEBMA_OPUS -> "webm" to "audio/webm"
            MediaFormat.OGG,
            MediaFormat.OPUS -> "ogg" to "audio/ogg"
            else -> {
                val urlLower = audioStream.content.lowercase(Locale.ROOT)
                when {
                    "mime=audio%2fmp4" in urlLower || "mime=audio/mp4" in urlLower ->
                        "m4a" to "audio/mp4"
                    "mime=audio%2fwebm" in urlLower || "mime=audio/webm" in urlLower ->
                        "webm" to "audio/webm"
                    "mime=audio%2fogg" in urlLower || "mime=audio/ogg" in urlLower ->
                        "ogg" to "audio/ogg"
                    else -> "m4a" to "audio/mp4"
                }
            }
        }

    internal fun sanitizeFileComponent(
        raw: String,
        fallback: String = "track",
        maxBytes: Int = 120,
    ): String {
        var cleaned =
            raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
                .replace(Regex("\\.{2,}"), ".")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trimStart('.')
                .trimEnd('.', ' ')
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
            cleaned = fallback
        }
        val upper = cleaned.uppercase(Locale.ROOT)
        if (upper in RESERVED_FAT_NAMES) {
            cleaned = "${cleaned}_"
        }
        if (cleaned.toByteArray(Charsets.UTF_8).size <= maxBytes) {
            return cleaned
        }
        val sb = StringBuilder()
        var currentBytes = 0
        var index = 0
        while (index < cleaned.length) {
            val cp = cleaned.codePointAt(index)
            val charCount = Character.charCount(cp)
            val cpBytes =
                cleaned.substring(index, index + charCount).toByteArray(Charsets.UTF_8).size
            if (currentBytes + cpBytes > maxBytes) break
            sb.appendCodePoint(cp)
            currentBytes += cpBytes
            index += charCount
        }
        val truncated = sb.toString().trimEnd('.', ' ')
        return if (truncated.isEmpty()) fallback else truncated
    }

    private suspend fun copyStreamWithProgress(
        input: InputStream,
        output: OutputStream,
        initialBytes: Long,
        totalBytes: Long,
        onProgress: (Int) -> Unit,
    ) {
        val buffer = ByteArray(16 * 1024)
        var bytesRead: Int
        var totalRead = initialBytes
        var lastReportedPercent =
            if (totalBytes > 0L && initialBytes > 0L) {
                ((initialBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
            } else {
                -1
            }

        input.use { inStream ->
            output.use { outStream ->
                while (inStream.read(buffer).also { bytesRead = it } != -1) {
                    currentCoroutineContext().ensureActive()
                    outStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (totalBytes > 0L) {
                        val percent = ((totalRead * 100L) / totalBytes).toInt().coerceIn(0, 100)
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
    ): Boolean {
        if (thumbnailUrl.isNullOrBlank()) return false
        return try {
            val req = Request.Builder().url(thumbnailUrl).build()
            val bytes =
                downloader.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        L.w("Failed to download thumbnail, code: ${resp.code}")
                        return false
                    }
                    val body = resp.body ?: return false
                    if (body.contentLength() > MAX_COVER_BYTES) {
                        L.w("Thumbnail exceeds max allowed size")
                        return false
                    }
                    body.byteStream().use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8 * 1024)
                        var total = 0
                        while (true) {
                            val read = stream.read(buf)
                            if (read == -1) break
                            total += read
                            if (total > MAX_COVER_BYTES) {
                                L.w("Thumbnail stream exceeded max allowed size")
                                return false
                            }
                            out.write(buf, 0, read)
                        }
                        out.toByteArray()
                    }
                }
            if (bytes.isEmpty()) return false

            val downloadCoversDir = File(context.filesDir, "download_covers").apply { mkdirs() }
            val displayNameWithoutExt = displayName.substringBeforeLast('.')
            val keys =
                listOf(
                    "folder_${CoverUtils.toMd5(folderName)}.jpg",
                    "file_${CoverUtils.toMd5(displayNameWithoutExt)}.jpg",
                )

            var wroteAny = false
            for (key in keys) {
                try {
                    val target = File(downloadCoversDir, key)
                    val tmp = File(downloadCoversDir, "$key.tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(target)) {
                        target.writeBytes(bytes)
                        tmp.delete()
                    }
                    if (target.exists() && target.length() > 0L) {
                        wroteAny = true
                    }
                } catch (e: Exception) {
                    L.w(e, "Error writing cover key: $key")
                }
            }
            wroteAny
        } catch (e: Exception) {
            L.w(e, "Failed to download thumbnail")
            false
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
                val folderKey = "folder_${CoverUtils.toMd5(folderName)}.jpg"
                val fileKey = "file_${CoverUtils.toMd5(displayName.substringBeforeLast('.'))}.jpg"
                val existingFolderCover = File(downloadCoversDir, folderKey)
                val existingFileCover = File(downloadCoversDir, fileKey)
                if (
                    (existingFolderCover.exists() && existingFolderCover.length() > 0L) ||
                        (existingFileCover.exists() && existingFileCover.length() > 0L)
                ) {
                    return@withContext true
                }
                val searchQuery = "$songArtist $songTitle".replace("Artista desconocido", "").trim()
                if (searchQuery.isBlank()) return@withContext false
                val results = search(searchQuery)
                val match = results.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }
                val thumbUrl = match?.thumbnailUrl ?: return@withContext false
                return@withContext downloadThumbnail(thumbUrl, folderName, displayName)
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

    private companion object {
        private const val MAX_COVER_BYTES = 8 * 1024 * 1024
        private val RESERVED_FAT_NAMES =
            setOf(
                "CON",
                "PRN",
                "AUX",
                "NUL",
                "COM1",
                "COM2",
                "COM3",
                "COM4",
                "COM5",
                "COM6",
                "COM7",
                "COM8",
                "COM9",
                "LPT1",
                "LPT2",
                "LPT3",
                "LPT4",
                "LPT5",
                "LPT6",
                "LPT7",
                "LPT8",
                "LPT9",
            )
    }
}
