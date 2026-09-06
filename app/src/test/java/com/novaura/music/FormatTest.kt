package com.novaura.music

import com.novaura.music.ui.utils.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `cero segundos`() {
        assertEquals("0:00", formatDuration(0))
    }

    @Test
    fun `valores negativos se limitan a cero`() {
        assertEquals("0:00", formatDuration(-5_000))
    }

    @Test
    fun `menos de un minuto`() {
        assertEquals("0:59", formatDuration(59_999))
    }

    @Test
    fun `exactamente un minuto`() {
        assertEquals("1:00", formatDuration(60_000))
    }

    @Test
    fun `mas de una hora muestra minutos totales`() {
        assertEquals("61:01", formatDuration(3_661_000))
    }
}