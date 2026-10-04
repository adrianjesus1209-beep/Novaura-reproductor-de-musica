/*
 * Copyright (c) 2025 Auxio Project
 * DBCache.kt is part of Auxio.
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
 
package org.oxycblt.musikr.cache.db

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.musikr.cache.Audio
import org.oxycblt.musikr.cache.Cache
import org.oxycblt.musikr.cache.CacheResult
import org.oxycblt.musikr.cache.CachedFile
import org.oxycblt.musikr.cache.MutableCache
import org.oxycblt.musikr.fs.AddedMs
import org.oxycblt.musikr.fs.Components
import org.oxycblt.musikr.fs.File
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.fs.Volume
import org.oxycblt.musikr.metadata.Properties
import org.oxycblt.musikr.tag.parse.ParsedTags

/**
 * An immutable [Cache] backed by an internal Room database.
 *
 * Create an instance with [from].
 */
class DBCache private constructor(private val readDao: CacheReadDao) : Cache {
    @Volatile private var mapping: Map<Uri, CachedFileData>? = null
    private val mappingLock = Mutex()

    override suspend fun read(file: File): CacheResult {
        val currentMapping =
            mapping
                ?: mappingLock.withLock {
                    mapping ?: readDao.selectAllSongs().associateBy { it.uri }.also { mapping = it }
                }
        val dbSong = currentMapping[file.uri] ?: return CacheResult.Miss(file)
        if (dbSong.modifiedMs != file.modifiedMs) {
            return CacheResult.Stale(file, dbSong.addedMs)
        }
        val song = dbSong.toCachedFile(file)
        return CacheResult.Hit(song)
    }

    override suspend fun readAll(): List<CachedFile> {
        val allSongs = readDao.selectAllSongs()
        return allSongs.map { it.toCachedFile() }
    }

    companion object {
        /**
         * Create a new instance of [DBCache] from the given [context].
         *
         * This instance should be a singleton, since it implicitly holds a Room database. As a
         * result, you should only create EITHER a [DBCache] or a [MutableDBCache].
         *
         * @param context The context to use to create the Room database.
         * @return A new instance of [DBCache].
         */
        fun from(context: Context) = from(CacheDatabase.from(context))

        internal fun from(db: CacheDatabase) = DBCache(db.readDao())

        internal fun from(readDao: CacheReadDao) = DBCache(readDao)
    }
}

/**
 * A mutable [Cache] backed by an internal Room database.
 *
 * Create an instance with [from].
 */
class MutableDBCache
private constructor(private val inner: DBCache, private val writeDao: CacheWriteDao) :
    MutableCache {
    override suspend fun read(file: File) = inner.read(file)

    override suspend fun readAll() = inner.readAll()

    override suspend fun write(cachedFile: CachedFile) {
        writeDao.updateSong(cachedFile.toDbData())
    }

    override suspend fun writeAll(cachedFiles: List<CachedFile>) {
        for (chunk in cachedFiles.chunked(BATCH_SIZE)) {
            writeDao.updateSongs(chunk.map { it.toDbData() })
        }
    }

    override suspend fun cleanup(excluding: List<CachedFile>) {
        writeDao.deleteExcludingUris(excluding.mapTo(mutableSetOf()) { it.file.uri.toString() })
    }

    companion object {
        /**
         * Create a new instance of [MutableDBCache] from the given [context].
         *
         * This instance should be a singleton, since it implicitly holds a Room database. As a
         * result, you should only create EITHER a [DBCache] or a [MutableDBCache].
         *
         * @param context The context to use to create the Room database.
         * @return A new instance of [MutableDBCache].
         */
        fun from(context: Context): MutableDBCache {
            val db = CacheDatabase.from(context)
            return MutableDBCache(DBCache.from(db), db.writeDao())
        }

        internal fun from(inner: DBCache, writeDao: CacheWriteDao) = MutableDBCache(inner, writeDao)

        private const val BATCH_SIZE = 500
    }
}

private fun CachedFile.toDbData() =
    CachedFileData(
        uri = file.uri,
        modifiedMs = file.modifiedMs,
        addedMs = addedMs,
        mimeType = audio?.properties?.mimeType,
        durationMs = audio?.properties?.durationMs,
        bitrateKbps = audio?.properties?.bitrateKbps,
        sampleRateHz = audio?.properties?.sampleRateHz,
        musicBrainzId = audio?.tags?.musicBrainzId,
        name = audio?.tags?.name,
        sortName = audio?.tags?.sortName,
        track = audio?.tags?.track,
        disc = audio?.tags?.disc,
        subtitle = audio?.tags?.subtitle,
        date = audio?.tags?.date,
        albumMusicBrainzId = audio?.tags?.albumMusicBrainzId,
        albumName = audio?.tags?.albumName,
        albumSortName = audio?.tags?.albumSortName,
        releaseTypes = audio?.tags?.releaseTypes,
        artistMusicBrainzIds = audio?.tags?.artistMusicBrainzIds,
        artistNames = audio?.tags?.artistNames,
        artistSortNames = audio?.tags?.artistSortNames,
        albumArtistMusicBrainzIds = audio?.tags?.albumArtistMusicBrainzIds,
        albumArtistNames = audio?.tags?.albumArtistNames,
        albumArtistSortNames = audio?.tags?.albumArtistSortNames,
        genreNames = audio?.tags?.genreNames,
        replayGainTrackAdjustment = audio?.tags?.replayGainTrackAdjustment,
        replayGainAlbumAdjustment = audio?.tags?.replayGainAlbumAdjustment,
        coverId = audio?.coverId,
    )

private fun CachedFileData.toCachedFile(fileOverride: File? = null): CachedFile {
    val file =
        fileOverride
            ?: run {
                val path =
                    Path(
                        Volume.ThirdParty(uri),
                        Components.parseUnix(uri.path ?: "/"),
                    )
                File(
                    uri = uri,
                    path = path,
                    addedMs =
                        object : AddedMs {
                            override suspend fun resolve() = addedMs
                        },
                    modifiedMs = modifiedMs,
                    mimeType = mimeType ?: "audio/*",
                    size = 0L,
                    parent = null,
                )
            }
    val audio = mimeType?.let {
        Audio(
            Properties(
                it,
                durationMs ?: 0L,
                bitrateKbps ?: 0,
                sampleRateHz ?: 0,
            ),
            ParsedTags(
                musicBrainzId = musicBrainzId,
                name = name,
                sortName = sortName,
                durationMs = durationMs ?: 0L,
                track = track,
                disc = disc,
                subtitle = subtitle,
                date = date,
                albumMusicBrainzId = albumMusicBrainzId,
                albumName = albumName,
                albumSortName = albumSortName,
                releaseTypes = releaseTypes ?: emptyList(),
                artistMusicBrainzIds = artistMusicBrainzIds ?: emptyList(),
                artistNames = artistNames ?: emptyList(),
                artistSortNames = artistSortNames ?: emptyList(),
                albumArtistMusicBrainzIds = albumArtistMusicBrainzIds ?: emptyList(),
                albumArtistNames = albumArtistNames ?: emptyList(),
                albumArtistSortNames = albumArtistSortNames ?: emptyList(),
                genreNames = genreNames ?: emptyList(),
                replayGainTrackAdjustment = replayGainTrackAdjustment,
                replayGainAlbumAdjustment = replayGainAlbumAdjustment,
            ),
            coverId = coverId,
        )
    }
    return CachedFile(file = file, audio = audio, addedMs = addedMs)
}
