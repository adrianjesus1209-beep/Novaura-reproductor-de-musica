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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.Name
import timber.log.Timber as L

/**
 * Manages active listening sessions and tracks recently played songs so the user can inspect and
 * explicitly save them as a playlist from the queue section (YouTube Music style).
 */
@Singleton
class SessionPlaylistManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val playbackManager: PlaybackStateManager,
) : PlaybackStateManager.Listener, MusicRepository.UpdateListener {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val sessionSongUids = mutableListOf<Music.UID>()
    private val _sessionSongs = MutableStateFlow<List<Song>>(emptyList())
    val sessionSongs: StateFlow<List<Song>> = _sessionSongs.asStateFlow()

    private var lastPlaybackTimeMs: Long = 0L

    init {
        restoreSession()
        playbackManager.addListener(this)
        musicRepository.addUpdateListener(this)
        resolveSongs()
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        if (changes.deviceLibrary) {
            resolveSongs()
        }
    }

    override fun onIndexMoved(index: Int) {
        val song = playbackManager.currentSong ?: return
        recordSong(song)
    }

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean,
    ) {
        val song = playbackManager.currentSong ?: return
        recordSong(song)
    }

    /** Records a played [Song] in the current listening session without duplicating entries. */
    @Synchronized
    fun recordSong(song: Song) {
        val now = System.currentTimeMillis()
        if (lastPlaybackTimeMs > 0 && (now - lastPlaybackTimeMs) > SESSION_TIMEOUT_MS) {
            L.d("Session timeout exceeded (${now - lastPlaybackTimeMs} ms), starting fresh session")
            sessionSongUids.clear()
        }
        lastPlaybackTimeMs = now

        // Avoid duplicates while preserving listening order (latest played moves to the end)
        sessionSongUids.remove(song.uid)
        sessionSongUids.add(song.uid)
        // Limit session to most recent 50 tracks
        if (sessionSongUids.size > MAX_SESSION_SONGS) {
            sessionSongUids.removeAt(0)
        }
        saveSession()
        resolveSongs()
    }

    @Synchronized
    private fun resolveSongs() {
        val resolved = sessionSongUids.mapNotNull { uid -> musicRepository.find(uid) as? Song }
        _sessionSongs.value = resolved
    }

    /**
     * Generates a YouTube Music-style "Playing from" title (e.g. parent name or "Mix de <Artist>").
     */
    fun generateMixTitle(parent: MusicParent?, currentSong: Song?): String {
        if (parent != null) {
            return parent.name.resolve(context)
        }
        val currentKnownArtist = currentSong?.artists?.firstOrNull { it.name is Name.Known }
        val dominantArtist =
            currentKnownArtist
                ?: _sessionSongs.value
                    .flatMap { it.artists }
                    .filter { it.name is Name.Known }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key

        return if (dominantArtist != null) {
            context.getString(R.string.fmt_mix_artist, dominantArtist.name.resolve(context))
        } else if (currentSong != null) {
            context.getString(R.string.fmt_mix_artist, currentSong.name.resolve(context))
        } else {
            context.getString(R.string.lbl_all_songs)
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
        const val SESSION_TIMEOUT_MS = 6 * 60 * 60 * 1000L // 6 hours
    }
}
