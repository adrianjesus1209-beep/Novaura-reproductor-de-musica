package com.novaura.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.novaura.music.R
import com.novaura.music.ui.components.AlbumArt
import com.novaura.music.ui.components.PlayerControls
import com.novaura.music.ui.theme.NovauraIcons
import com.novaura.music.ui.utils.formatDuration
import com.novaura.music.viewmodel.PlayerUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    uiState: PlayerUiState,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onRepeatClick: () -> Unit
) {
    val song = uiState.currentSong

    if (song == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.player_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = NovauraIcons.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.no_song_playing),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        return
    }

    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val durationMs = uiState.durationMs.coerceAtLeast(0L)
    val trackDuration = if (durationMs > 0L) durationMs.toFloat() else 1f
    val sliderValue = if (isDragging) {
        sliderPosition
    } else {
        uiState.currentPositionMs.toFloat().coerceIn(0f, trackDuration)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.player_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AlbumArt(
                    song = song,
                    size = 300.dp,
                    shape = RoundedCornerShape(24.dp)
                )
            }

            Text(
                text = song.title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = song.artist,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Slider(
                value = sliderValue,
                onValueChange = {
                    isDragging = true
                    sliderPosition = it
                },
                onValueChangeFinished = {
                    onSeek(sliderPosition.toLong())
                    isDragging = false
                },
                valueRange = 0f..trackDuration,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration(
                        if (isDragging) sliderPosition.toLong() else uiState.currentPositionMs
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatDuration(durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            ModeControls(
                shuffleEnabled = uiState.shuffleEnabled,
                repeatMode = uiState.repeatMode,
                onShuffleChange = onShuffleChange,
                onRepeatClick = onRepeatClick
            )

            PlayerControls(
                isPlaying = uiState.isPlaying,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPrevious = onPrevious,
                large = true,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun ModeControls(
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onShuffleChange: (Boolean) -> Unit,
    onRepeatClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { onShuffleChange(!shuffleEnabled) }) {
            Icon(
                imageVector = NovauraIcons.Shuffle,
                contentDescription = stringResource(R.string.shuffle),
                tint = if (shuffleEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(28.dp)
            )
        }

        IconButton(onClick = onRepeatClick) {
            val isActive = repeatMode != Player.REPEAT_MODE_OFF
            Icon(
                imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) {
                    NovauraIcons.RepeatOne
                } else {
                    NovauraIcons.Repeat
                },
                contentDescription = stringResource(R.string.repeat),
                tint = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(28.dp)
            )
        }
    }
}