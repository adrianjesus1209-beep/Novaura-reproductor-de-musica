package com.novaura.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.novaura.music.R
import com.novaura.music.ui.theme.NovauraIcons

/**
 * Controles básicos del reproductor: anterior, reproducir/pausar, siguiente.
 */
@Composable
fun PlayerControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false
) {
    val sideButtonSize = if (large) 40.dp else 26.dp
    val playButtonSize = if (large) 88.dp else 54.dp
    val playIconSize = if (large) 42.dp else 28.dp

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious) {
            Icon(
                imageVector = NovauraIcons.SkipPrevious,
                contentDescription = stringResource(R.string.previous),
                modifier = Modifier.size(sideButtonSize),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        FilledIconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(playButtonSize)
        ) {
            Icon(
                imageVector = if (isPlaying) NovauraIcons.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (isPlaying) R.string.pause else R.string.play
                ),
                modifier = Modifier.size(playIconSize)
            )
        }

        IconButton(onClick = onNext) {
            Icon(
                imageVector = NovauraIcons.SkipNext,
                contentDescription = stringResource(R.string.next),
                modifier = Modifier.size(sideButtonSize),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}