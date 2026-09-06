package com.novaura.music

import androidx.media3.common.Player
import com.novaura.music.playback.nextRepeatMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatModeUtilTest {

    @Test
    fun `de apagado pasa a repetir todo`() {
        assertEquals(Player.REPEAT_MODE_ALL, nextRepeatMode(Player.REPEAT_MODE_OFF))
    }

    @Test
    fun `de repetir todo pasa a repetir una`() {
        assertEquals(Player.REPEAT_MODE_ONE, nextRepeatMode(Player.REPEAT_MODE_ALL))
    }

    @Test
    fun `de repetir una vuelve a apagado`() {
        assertEquals(Player.REPEAT_MODE_OFF, nextRepeatMode(Player.REPEAT_MODE_ONE))
    }
}