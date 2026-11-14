/*
 * Copyright (c) 2025 Auxio Project
 * CoverProvider.kt is part of Auxio.
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
 
package org.oxycblt.auxio.image

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlinx.coroutines.runBlocking
import org.oxycblt.auxio.BuildConfig
import org.oxycblt.auxio.image.covers.SettingCovers
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.musikr.Library
import org.oxycblt.musikr.covers.CoverResult

class CoverProvider : ContentProvider() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CoverProviderEntryPoint {
        fun musicRepository(): MusicRepository
    }

    @Volatile private var cachedLibraryIds: Pair<Library, Set<String>>? = null

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r" || uriMatcher.match(uri) != 1) {
            return null
        }
        val appContext = context?.applicationContext ?: return null
        val id = uri.lastPathSegment?.takeIf { it.isNotEmpty() } ?: return null
        if (!isAuthorizedCoverId(appContext, id)) {
            return null
        }
        return runBlocking {
            when (val result = SettingCovers.immutable(appContext).obtain(id)) {
                is CoverResult.Hit -> result.cover.fd()
                else -> null
            }
        }
    }

    private fun isAuthorizedCoverId(appContext: Context, id: String): Boolean {
        if (id.startsWith("mcs:")) {
            val fileName = id.removePrefix("mcs:")
            return fileName.isNotEmpty() &&
                fileName != "." &&
                fileName != ".." &&
                !fileName.contains('/') &&
                !fileName.contains('\\') &&
                !fileName.contains('\u0000') &&
                !fileName.endsWith(".tmp")
        }
        if (id.startsWith("mcf:")) {
            val library =
                try {
                    EntryPointAccessors.fromApplication(
                            appContext,
                            CoverProviderEntryPoint::class.java,
                        )
                        .musicRepository()
                        .library
                } catch (_: IllegalStateException) {
                    null
                }
            if (library != null) {
                val ids = getOrBuildLibraryCoverIds(library)
                return id in ids
            }
            val rawUri = Uri.parse(id.removePrefix("mcf:"))
            if (rawUri.scheme == "file") {
                val path = rawUri.path ?: return false
                return try {
                    val downloadCoversDir =
                        File(appContext.filesDir, "download_covers").canonicalFile
                    val target = File(path).canonicalFile
                    target.path.startsWith(downloadCoversDir.path + File.separator)
                } catch (_: Exception) {
                    false
                }
            }
            return false
        }
        return false
    }

    private fun getOrBuildLibraryCoverIds(library: Library): Set<String> {
        val current = cachedLibraryIds
        if (current != null && current.first === library) {
            return current.second
        }
        val built = HashSet<String>(library.songs.size)
        for (song in library.songs) {
            song.cover?.id?.let { built.add(it) }
        }
        cachedLibraryIds = library to built
        return built
    }

    override fun getType(uri: Uri): String? = if (uriMatcher.match(uri) == 1) "image/*" else null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.image.CoverProvider"
        private const val IMAGES_PATH = "covers"
        private val uriMatcher =
            UriMatcher(UriMatcher.NO_MATCH).apply { addURI(AUTHORITY, "$IMAGES_PATH/*", 1) }

        val CONTENT_URI: Uri =
            Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath(IMAGES_PATH)
                .build()
    }
}
