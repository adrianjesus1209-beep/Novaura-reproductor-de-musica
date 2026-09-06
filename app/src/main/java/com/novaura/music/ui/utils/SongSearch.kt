package com.novaura.music.ui.utils

import com.novaura.music.data.Song

/**
 * Filtra el catálogo por título, artista o álbum, ignorando mayúsculas.
 * Con la consulta vacía devuelve la lista completa.
 */
fun filterSongs(songs: List<Song>, query: String): List<Song> {
    val q = query.trim()
    if (q.isEmpty()) return songs
    return songs.filter { song ->
        song.title.contains(q, ignoreCase = true) ||
            song.artist.contains(q, ignoreCase = true) ||
            song.album.contains(q, ignoreCase = true)
    }
}