package com.novaura.music.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novaura.music.R
import com.novaura.music.data.Song
import com.novaura.music.ui.theme.NovauraIcons

/**
 * Barra mini flotante — diseño GRIS translúcido (sin tonos morados),
 * misma transparencia que la barra de opciones, controles compactos
 * y botón de cierre "X" circular bien visible en la esquina derecha.
 */
@Composable
fun NowPlayingBar(
    song: Song,
    isPlaying: Boolean,
    progressFraction: Float,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.30f), // Mismo gris translúcido que las opciones
        border = BorderStroke(
            width = 1.dp,
            color = Color.White.copy(alpha = 0.18f)
        ),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Arte circular de la canción
                Box(contentAlignment = Alignment.Center) {
                    AlbumArt(
                        song = song,
                        size = 46.dp,
                        shape = CircleShape
                    )
                    if (isPlaying) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.40f)),
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedEqualizer(
                                isPlaying = true,
                                barCount = 3,
                                barWidth = 3.dp,
                                maxHeight = 13.dp,
                                color = Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Título + artista
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.70f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Controles: Anterior · Play/Pause · Siguiente
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    IconButton(onClick = onPrevious, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = NovauraIcons.SkipPrevious,
                            contentDescription = stringResource(R.string.previous),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Botón Play / Pause circular (Gris claro / Blanco)
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.90f),
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onPlayPause)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) NovauraIcons.Pause else Icons.Filled.PlayArrow,
                                contentDescription = stringResource(
                                    if (isPlaying) R.string.pause else R.string.play
                                ),
                                tint = Color(0xFF1E1E22),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    IconButton(onClick = onNext, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = NovauraIcons.SkipNext,
                            contentDescription = stringResource(R.string.next),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Botón "X" de cierre — destacado en círculo gris translúcido para visibilidad garantizada
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cerrar reproductor",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Barra de progreso inferior neutral
            LinearProgressIndicator(
                progress = { progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = Color.White.copy(alpha = 0.90f),
                trackColor = Color.White.copy(alpha = 0.15f),
            )
        }
    }
}