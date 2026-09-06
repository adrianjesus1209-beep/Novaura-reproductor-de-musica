package com.novaura.music.playback

import androidx.media3.common.Player

/**
 * Cicla el estado de repetición de Media3: OFF -> ALL -> ONE -> OFF.
 * Función pura, testeable.
 */
fun nextRepeatMode(current: Int): Int = when (current) {
    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
    else -> Player.REPEAT_MODE_OFF
}