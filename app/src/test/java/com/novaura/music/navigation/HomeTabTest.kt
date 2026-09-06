package com.novaura.music.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeTabTest {

    @Test
    fun `todas las pestañas mapean a una ruta distinta`() {
        val routes = HomeTab.entries.map { it.routeObject() }
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun `cada pestaña es la unica que apunta a su ruta`() {
        HomeTab.entries.forEach { tab ->
            val twins = HomeTab.entries.filter { it.routeObject() == tab.routeObject() }
            assertEquals(listOf(tab), twins)
        }
    }

    @Test
    fun `ninguna pestaña queda asignada a la ruta del reproductor`() {
        HomeTab.entries.forEach { tab ->
            assertNotEquals(NowPlayingRoute, tab.routeObject())
        }
    }
}