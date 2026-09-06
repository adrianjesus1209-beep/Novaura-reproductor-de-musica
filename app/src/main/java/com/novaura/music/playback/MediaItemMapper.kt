package com.novaura.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.novaura.music.data.Song

/**
 * Convierte el catálogo de la app en lista de reproducción de Media3.
 * Lógica pura: se puede probar sin Android.
 */
object MediaItemMapper {

    fun toMediaItems(songs: List<Song>): List<MediaItem> =
        songs.map { song ->
            MediaItem.Builder()
                .setUri(song.uri)
                .setMediaId(song.id.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .setArtworkUri(song.albumArtUri)
                        .build()
                )
                .build()
        }
}