package com.novaura.music.data

import android.content.Context
import com.novaura.music.scanner.MusicScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class LibraryState(
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = true
)

/**
 * Única fuente de verdad del catálogo de canciones.
 * Expone el estado como [StateFlow] y ejecuta el escaneo en segundo plano.
 */
class MusicRepository(private val scanner: MusicScanner = MusicScanner) {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    suspend fun refresh(context: Context) {
        _state.update { it.copy(isLoading = true) }
        val songs = withContext(Dispatchers.IO) {
            runCatching { scanner.getAllSongs(context) }
                .getOrDefault(emptyList())
        }
        _state.update { it.copy(songs = songs, isLoading = false) }
    }
}