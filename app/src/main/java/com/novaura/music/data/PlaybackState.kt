package com.novaura.music.data

import androidx.media3.common.Player

/**
 * Estado puro del reproductor, actualizado por [com.novaura.music.playback.PlaybackController].
 * No contiene lógica; solo datos observables por la UI.
 */
data class PlaybackState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isBuffering: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false
)