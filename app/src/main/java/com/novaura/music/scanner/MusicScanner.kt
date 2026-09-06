package com.novaura.music.scanner

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.novaura.music.data.Song
import com.novaura.music.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Escanea la biblioteca de audio del dispositivo usando MediaStore.
 * Filtra tonos de llamada o clips cortos quedándose solo con música real.
 */
object MusicScanner {

    suspend fun getAllSongs(context: Context): List<Song> =
        withContext(Dispatchers.IO) {
            val songs = mutableListOf<Song>()

            val unknownTitle = context.getString(R.string.unknown_title)
            val unknownArtist = context.getString(R.string.unknown_artist)
            val unknownAlbum = context.getString(R.string.unknown_album)

            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.IS_MUSIC
            )

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
                    "${MediaStore.Audio.Media.DURATION} > 30000"

            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: unknownTitle
                    val artist = cursor.getString(artistCol) ?: unknownArtist
                    val album = cursor.getString(albumCol) ?: unknownAlbum
                    val albumId = cursor.getLong(albumIdCol)
                    val duration = cursor.getLong(durationCol)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    songs.add(
                        Song(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            albumId = albumId,
                            duration = duration,
                            uri = contentUri
                        )
                    )
                }
            }

            songs
        }
}