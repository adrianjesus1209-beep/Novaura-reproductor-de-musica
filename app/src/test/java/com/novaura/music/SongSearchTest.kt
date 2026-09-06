package com.novaura.music

import android.net.Uri
import com.novaura.music.ui.utils.filterSongs
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SongSearchTest {

    private val testUri: Uri = Uri.parse("content://mock")

    private fun song(title: String, artist: String = "", album: String = "") =
        com.novaura.music.data.Song(0L, title, artist, album, 0L, 0L, testUri)

    private val songs = listOf(
        song("Canción Uno", artist = "Artista A", album = "Álbum X"),
        song("Canción Dos", artist = "Artista B", album = "Álbum X"),
        song("Otra Pista", artist = "Artista C", album = "Colores")
    )

    @Test
    fun `consulta vacia devuelve todo`() {
        assertEquals(songs, filterSongs(songs, ""))
    }

    @Test
    fun `consulta con espacios devuelve todo`() {
        assertEquals(songs, filterSongs(songs, "   "))
    }

    @Test
    fun `coincide con el titulo sin importar mayusculas`() {
        assertEquals(1, filterSongs(songs, "otra PISTA").size)
        assertEquals("Otra Pista", filterSongs(songs, "otra PISTA").first().title)
    }

    @Test
    fun `coincide con el artista`() {
        assertEquals(1, filterSongs(songs, "artista b").size)
    }

    @Test
    fun `coincide con el album`() {
        assertEquals(2, filterSongs(songs, "álbum x").size)
    }

    @Test
    fun `busqueda parcial por subcadena`() {
        assertEquals(2, filterSongs(songs, "ción").size)
    }

    @Test
    fun `sin coincidencias devuelve lista vacia`() {
        assertEquals(0, filterSongs(songs, "inexistente").size)
    }
}