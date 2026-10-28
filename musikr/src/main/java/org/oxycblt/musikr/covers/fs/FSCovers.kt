/*
 * Copyright (c) 2025 Auxio Project
 * FSCovers.kt is part of Auxio.
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
 
package org.oxycblt.musikr.covers.fs

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.oxycblt.musikr.covers.Cover
import org.oxycblt.musikr.covers.CoverResult
import org.oxycblt.musikr.covers.Covers
import org.oxycblt.musikr.covers.FDCover
import org.oxycblt.musikr.covers.MutableCovers
import org.oxycblt.musikr.fs.File
import org.oxycblt.musikr.metadata.Metadata
import org.oxycblt.musikr.util.unlikelyToBeNull

private const val PREFIX = "mcf:"

/**
 * A [Covers] implementation that obtains cover art from the filesystem, such as cover.jpg.
 *
 * Cover.jpg is pretty widely used in music libraries to save space, so it's good to use this.
 *
 * This implementation does not search the directory tree given that it cannot access it. Rather, it
 * assumes the provided id ius one yielded by [MutableFSCovers].
 *
 * See [MutableFSCovers] for the mutable variant.
 *
 * @param context The [Context] to use to access the filesystem and check for ID validity.
 */
class FSCovers(private val context: Context) : Covers<FDCover> {
    private val verifiedUris = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    override suspend fun obtain(id: String): CoverResult<FDCover> {
        if (!id.startsWith(PREFIX)) {
            return CoverResult.Miss()
        }

        val uri = id.substring(PREFIX.length).toUri()

        // Check if the cover file still actually exists. Perhaps the file was deleted at some
        // point or superceded by a new one.
        val cachedExists = verifiedUris[id]
        val exists =
            if (cachedExists != null) {
                cachedExists
            } else {
                val res =
                    withContext(Dispatchers.IO) {
                        try {
                            if (uri.scheme == "file") {
                                val path = uri.path ?: return@withContext false
                                val file = java.io.File(path)
                                file.exists() && file.length() > 0
                            } else {
                                context.contentResolver.openFileDescriptor(uri, "r")?.also {
                                    it.close()
                                } != null
                            }
                        } catch (e: Exception) {
                            false
                        }
                    }
                verifiedUris[id] = res
                res
            }
        return if (exists) {
            CoverResult.Hit(FolderCoverImpl(context, uri))
        } else {
            CoverResult.Miss()
        }
    }
}

/**
 * A [MutableCovers] implementation that obtains cover art from the filesystem, such as cover.jpg.
 *
 * Cover.jpg is pretty widely used in music libraries to save space, so it's good to use this.
 *
 * This implementation will search the parent directory for the best cover art. "Best" being defined
 * as having cover-art-ish names and having a good format like png/jpg/webp.
 *
 * See [FSCovers] for the immutable variant.
 *
 * @param context The [Context] to use to access the filesystem and check for ID validity.
 */
class MutableFSCovers(private val context: Context) : MutableCovers<FDCover> {
    private val inner = FSCovers(context)

    override suspend fun obtain(id: String): CoverResult<FDCover> = inner.obtain(id)

    override suspend fun create(file: File, metadata: Metadata): CoverResult<FDCover> {
        // 1. SAF mode: Parent directory traversal if available
        val parent = file.parent?.await()
        if (parent != null) {
            val bestCover =
                parent.children.map { it to coverArtScore(it) }.maxByOrNull { it.second }
            if (bestCover != null && bestCover.second > 0) {
                return CoverResult.Hit(FolderCoverImpl(context, bestCover.first.uri))
            }
        }

        // 2. Downloaded covers directory check (for songs downloaded inside Novaura)
        val downloadCoversDir = java.io.File(context.filesDir, "download_covers")
        if (downloadCoversDir.exists()) {
            val fileNameWithoutExt = file.path.name?.substringBeforeLast('.')
            val folderName = file.path.directory.name
            val candidates = mutableListOf<String>()
            if (fileNameWithoutExt != null) {
                candidates.add(
                    "file_${org.oxycblt.musikr.covers.CoverUtils.toMd5(fileNameWithoutExt)}.jpg"
                )
                candidates.add(
                    "file_${org.oxycblt.musikr.covers.CoverUtils.toSafeFsName(fileNameWithoutExt)}.jpg"
                )
                candidates.add(
                    "${org.oxycblt.musikr.covers.CoverUtils.toSafeFsName(fileNameWithoutExt)}.jpg"
                )
            }
            if (folderName != null) {
                candidates.add(
                    "folder_${org.oxycblt.musikr.covers.CoverUtils.toMd5(folderName)}.jpg"
                )
                candidates.add(
                    "folder_${org.oxycblt.musikr.covers.CoverUtils.toSafeFsName(folderName)}.jpg"
                )
                candidates.add(
                    "${org.oxycblt.musikr.covers.CoverUtils.toSafeFsName(folderName)}.jpg"
                )
            }
            for (candidate in candidates) {
                val coverFile = java.io.File(downloadCoversDir, candidate)
                if (coverFile.exists() && coverFile.length() > 0) {
                    return CoverResult.Hit(FolderCoverImpl(context, Uri.fromFile(coverFile)))
                }
            }
        }

        // 3. MediaStore mode: Check external filesystem directory for sibling cover image
        val volumeStr = file.path.volume.components?.unixString
        val dirStr = file.path.directory.components.unixString
        if (volumeStr != null && dirStr.isNotEmpty()) {
            val fullDirPath = "/$volumeStr/$dirStr"
            val dirFile = java.io.File(fullDirPath)
            if (dirFile.exists() && dirFile.isDirectory) {
                val candidateNames =
                    listOf(
                        "cover.jpg",
                        "cover.png",
                        "folder.jpg",
                        "folder.png",
                        "artwork.jpg",
                        "front.jpg",
                    )
                for (name in candidateNames) {
                    val candidateFile = java.io.File(dirFile, name)
                    if (candidateFile.exists() && candidateFile.length() > 0) {
                        return CoverResult.Hit(
                            FolderCoverImpl(context, Uri.fromFile(candidateFile))
                        )
                    }
                }
            }
        }

        // No useful cover art was found.
        return CoverResult.Miss()
    }

    override suspend fun cleanup(excluding: Collection<Cover>) {
        // No cleanup needed for folder covers as they are external files
        // that should not be managed by the app
    }

    private suspend fun coverArtScore(file: File): Int {
        if (!file.mimeType.startsWith("image/", ignoreCase = true)) {
            // Not an image file. You lose!
            return 0
        }

        val filename = requireNotNull(file.path.name)
        val name = filename.substringBeforeLast('.')
        val extension = filename.substringAfterLast('.', "")
        // See if the name contains any of the preferred cover names. This helps weed out
        // images that are not actually cover art and are just there.,
        var score =
            (preferredCoverNames + requireNotNull(unlikelyToBeNull(file.parent).await().path.name))
                .withIndex()
                .filter { name.contains(it.value, ignoreCase = true) }
                .sumOf { it.index + 1 }
        // Multiply the score for preferred formats & extensions. Weirder formats are harder for
        // android to decode, but not the end of the world.
        score *= preferredFormats.indexOfFirst { file.mimeType.equals(it, ignoreCase = true) } + 2
        score *= preferredExtensions.indexOfFirst { extension.equals(it, ignoreCase = true) } + 2
        return score
    }

    private companion object {
        private val preferredCoverNames = listOf("front", "art", "album", "folder", "cover")

        private val preferredFormats = listOf("image/webp", "image/jpg", "image/jpeg", "image/png")

        private val preferredExtensions = listOf("webp", "jpg", "jpeg", "png")
    }
}

private class FolderCoverImpl(private val context: Context, private val uri: Uri) : FDCover {
    override val id = PREFIX + uri.toString()

    // Implies that client will manage freeing the resources themselves.

    @SuppressLint("Recycle")
    override suspend fun open(): InputStream? =
        withContext(Dispatchers.IO) {
            if (uri.scheme == "file") {
                try {
                    val path = uri.path ?: return@withContext null
                    java.io.FileInputStream(java.io.File(path))
                } catch (e: Exception) {
                    null
                }
            } else {
                context.contentResolver.openInputStream(uri)
            }
        }

    @SuppressLint("Recycle")
    override suspend fun fd(): ParcelFileDescriptor? =
        withContext(Dispatchers.IO) {
            if (uri.scheme == "file") {
                try {
                    val path = uri.path ?: return@withContext null
                    ParcelFileDescriptor.open(
                        java.io.File(path),
                        ParcelFileDescriptor.MODE_READ_ONLY,
                    )
                } catch (e: Exception) {
                    null
                }
            } else {
                context.contentResolver.openFileDescriptor(uri, "r")
            }
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FolderCoverImpl) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
