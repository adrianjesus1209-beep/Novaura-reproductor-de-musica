package com.novaura.music.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novaura.music.R
import com.novaura.music.data.Song
import com.novaura.music.ui.components.NowPlayingBar
import com.novaura.music.ui.components.SongItem
import com.novaura.music.ui.theme.NovauraIcons
import com.novaura.music.ui.utils.filterSongs
import com.novaura.music.ui.utils.hasRequiredPermissions
import com.novaura.music.ui.utils.requiredPermissions
import com.novaura.music.util.CrashLogger
import com.novaura.music.viewmodel.PlayerUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongListScreen(
    uiState: PlayerUiState,
    onSongClick: (Song) -> Unit,
    onNowPlayingClick: () -> Unit,
    onPlayPause: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var crashHidden by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val crashLog = remember { CrashLogger.readLastCrash(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) {
            permissionDenied = false
            onRefresh()
        } else {
            permissionDenied = true
        }
    }

    LaunchedEffect(Unit) {
        if (hasRequiredPermissions(context)) {
            onRefresh()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    val filteredSongs = remember(searchQuery, uiState.songs) {
        filterSongs(uiState.songs, searchQuery)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(title = { Text(stringResource(R.string.library_title)) })
            }
        ) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (crashLog != null && !crashHidden) {
                    CrashBanner(
                        text = crashLog,
                        onCopy = { clipboard.setText(AnnotatedString(crashLog)) },
                        onDismiss = { crashHidden = true }
                    )
                }

                if (uiState.songs.isNotEmpty()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Search
                        )
                    )
                }

                when {
                    uiState.isLoading && uiState.songs.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    uiState.songs.isEmpty() -> {
                        EmptyLibrary(
                            permissionDenied = permissionDenied,
                            onRequestPermission = {
                                permissionLauncher.launch(requiredPermissions())
                            },
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
                    }

                    filteredSongs.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.search_no_results, searchQuery.trim()),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp)
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(bottom = 96.dp)
                        ) {
                            items(filteredSongs, key = { it.id }, contentType = { "song" }) { song ->
                                SongItem(
                                    song = song,
                                    isCurrent = song.id == uiState.currentSong?.id,
                                    onClick = { onSongClick(song) }
                                )
                            }
                        }
                    }
                }
            }
        }

        uiState.currentSong?.let { current ->
            NowPlayingBar(
                song = current,
                isPlaying = uiState.isPlaying,
                onPlayPause = onPlayPause,
                onClick = onNowPlayingClick,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun EmptyLibrary(
    permissionDenied: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = NovauraIcons.LibraryMusic,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.empty_library_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    if (permissionDenied) R.string.empty_library_permission
                    else R.string.empty_library_no_songs
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (permissionDenied) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onRequestPermission) {
                    Text(stringResource(R.string.grant_permissions))
                }
            }
        }
    }
}

@Composable
private fun CrashBanner(
    text: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.crash_banner_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCopy) {
                    Text(stringResource(R.string.copy))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}