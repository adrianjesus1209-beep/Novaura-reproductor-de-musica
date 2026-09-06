package com.novaura.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.novaura.music.data.Song
import com.novaura.music.ui.theme.NovauraIcons

/**
 * Carátula del álbum con un icono musical de respaldo.
 * La petición a Coil pide un tamaño acotado para no decodificar
 * carátulas a resolución completa dentro de las listas.
 */
@Composable
fun AlbumArt(
    song: Song,
    size: Dp,
    shape: Shape = RoundedCornerShape(8.dp),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = NovauraIcons.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(size * 0.45f)
        )

        if (song.albumArtUri != null) {
            val context = LocalContext.current
            val request = with(LocalDensity.current) {
                ImageRequest.Builder(context)
                    .data(song.albumArtUri)
                    .size(size.roundToPx())
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = song.album,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}