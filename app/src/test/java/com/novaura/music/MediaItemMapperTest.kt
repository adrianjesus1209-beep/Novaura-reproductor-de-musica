package com.novaura.music

import android.net.Uri
import com.novaura.music.data.Song
import com.novaura.music.playback.MediaItemMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaItemMapperTest {

    private val testUri: Uri = Uri.parse("content://mock")

    private fun song(id: Long, title: String = "Título $id") =
        Song(id, title, "Artista $id", "Álbum $id", -1L, 60_000L, testUri)

    @Test
    fun `lista vacia produce lista vacia`() {
        assertTrue(MediaItemMapper.toMediaItems(emptyList()).isEmpty())
    }

    @Test
    fun `mapea media id como repetido del id de la cancion`() {
        val mediaItems = MediaItemMapper.toMediaItems(listOf(song(42L)))
        assertEquals("42", mediaItems[0].mediaId)
    }

    @Test
    fun `metadatos toman titulo artista y album`() {
        val mediaItems = MediaItemMapper.toMediaItems(listOf(song(1L, "Mi Canción")))
        val metadata = mediaItems[0].mediaMetadata
        assertNotNull(metadata)
        assertEquals("Mi Canción", metadata.title)
        assertEquals("Artista 1", metadata.artist)
        assertEquals("Álbum 1", metadata.albumTitle)
    }
}