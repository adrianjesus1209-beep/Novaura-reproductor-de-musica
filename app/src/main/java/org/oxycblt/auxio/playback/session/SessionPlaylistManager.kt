/*
 * Copyright (c) 2026 Auxio Project
 * SessionPlaylistManager.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.playback.session

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Manages active listening sessions and dynamically creates/updates the "My Recent Mix" smart
 * playlist (YouTube Music style) when multiple songs are played in a session.
 */
@Singleton
class SessionPlaylistManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val playbackManager: PlaybackStateManager,
) : PlaybackStateManager.Listener {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sessionSongUids = mutableListOf<Music.UID>()
    private val _sessionSongs = MutableStateFlow<List<Song>>(emptyList())
    val sessionSongs: StateFlow<List<Song>> = _sessionSongs.asStateFlow()

    private var lastPlaybackTimeMs: Long = 0L

    init {
        restoreSession()
        playbackManager.addListener(this)
    }

    override fun onIndexMoved(index: Int) {
        val song = playbackManager.currentSong ?: return
        recordSong(song)
    }

    /** Records a played [Song] in the current listening session. */
    fun recordSong(song: Song) {
        val now = System.currentTimeMillis()
        if (lastPlaybackTimeMs > 0 && (now - lastPlaybackTimeMs) > SESSION_TIMEOUT_MS) {
            L.d("Session timeout exceeded (${now - lastPlaybackTimeMs} ms), starting fresh session")
            sessionSongUids.clear()
        }
        lastPlaybackTimeMs = now

        // Avoid adding the exact same song consecutively
        if (sessionSongUids.lastOrNull() != song.uid) {
            sessionSongUids.add(song.uid)
            // Limit session to most recent 50 tracks
            if (sessionSongUids.size > MAX_SESSION_SONGS) {
                sessionSongUids.removeAt(0)
            }
            saveSession()
            resolveAndSyncSongs()
        }
    }

    private fun resolveAndSyncSongs() {
        val resolved = sessionSongUids.mapNotNull { uid ->
            musicRepository.find(uid) as? Song
        }
        _sessionSongs.value = resolved

        if (resolved.size >= MIN_SESSION_SONGS) {
            scope.launch {
                syncSmartPlaylist(resolved)
            }
        }
    }

    private suspend fun syncSmartPlaylist(songs: List<Song>) {
        val playlistName = context.getString(R.string.lbl_recent_mix)
        val library = musicRepository.library ?: return
        val existingPlaylist = library.playlists.find { it.name.raw == playlistName }

        if (existingPlaylist != null) {
            L.d("Updating existing '$playlistName' with ${songs.size} tracks")
            musicRepository.rewritePlaylist(existingPlaylist, songs)
        } else {
            L.d("Auto-creating '$playlistName' with ${songs.size} tracks")
            musicRepository.createPlaylist(playlistName, songs)
        }
    }

    private fun saveSession() {
        val uidsString = sessionSongUids.joinToString(";") { it.toString() }
        prefs
            .edit()
            .putString(KEY_SESSION_UIDS, uidsString)
            .putLong(KEY_LAST_TIME, lastPlaybackTimeMs)
            .apply()
    }

    private fun restoreSession() {
        val lastTime = prefs.getLong(KEY_LAST_TIME, 0L)
        val now = System.currentTimeMillis()
        if (lastTime > 0 && (now - lastTime) <= SESSION_TIMEOUT_MS) {
            lastPlaybackTimeMs = lastTime
            val uidsString = prefs.getString(KEY_SESSION_UIDS, null)
            if (!uidsString.isNullOrEmpty()) {
                val uids =
                    uidsString.split(";").mapNotNull {
                        try {
                            Music.UID.fromString(it)
                        } catch (e: Exception) {
                            null
                        }
                    }
                sessionSongUids.clear()
                sessionSongUids.addAll(uids)
            }
        } else {
            prefs.edit().remove(KEY_SESSION_UIDS).remove(KEY_LAST_TIME).apply()
        }
    }

    fun getRecentMixPlaylist(): Playlist? {
        val playlistName = context.getString(R.string.lbl_recent_mix)
        return musicRepository.library?.playlists?.find { it.name.raw == playlistName }
    }

    companion object {
        private const val PREFS_NAME = "auxio_session_playlist"
        private const val KEY_SESSION_UIDS = "session_uids"
        private const val KEY_LAST_TIME = "session_last_time"
        const val MIN_SESSION_SONGS = 3
        const val MAX_SESSION_SONGS = 50
        const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes
    }
}
