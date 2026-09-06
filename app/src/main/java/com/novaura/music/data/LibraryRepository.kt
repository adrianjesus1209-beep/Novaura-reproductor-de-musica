package com.novaura.music.data

/**
 * Repositorio en memoria del catálogo de canciones.
 * Permite restaurar la lista de reproducción si el servicio
 * se reinicia en segundo plano (mismo proceso de la app).
 */
object LibraryRepository {
    @Volatile
    var songs: List<Song> = emptyList()
}