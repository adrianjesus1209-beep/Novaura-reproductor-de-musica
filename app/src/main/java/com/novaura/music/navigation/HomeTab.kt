package com.novaura.music.navigation

import androidx.annotation.StringRes
import com.novaura.music.R

/**
 * Pestañas de la barra de navegación inferior.
 * Cada pestaña mapea a una ruta [Serializable] del NavHost.
 */
enum class HomeTab(@StringRes val labelRes: Int) {
    SONGS(R.string.tab_songs),
    ARTISTS(R.string.tab_artists),
    ALBUMS(R.string.tab_albums),
    PLAYLISTS(R.string.tab_playlists),
    GENRES(R.string.tab_genres),
    FOLDERS(R.string.tab_folders);

    fun routeObject(): Any = when (this) {
        SONGS -> SongListRoute
        ARTISTS -> ArtistListRoute
        ALBUMS -> AlbumListRoute
        PLAYLISTS -> PlaylistListRoute
        GENRES -> GenreListRoute
        FOLDERS -> FolderListRoute
    }
}