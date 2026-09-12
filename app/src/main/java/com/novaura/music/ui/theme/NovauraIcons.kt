package com.novaura.music.ui.theme

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector
import com.novaura.music.navigation.HomeTab

/**
 * Iconos de Material que solo vivían en material-icons-extended.
 * Definidos a mano (~200 B c/u) para poder eliminar esa dependencia pesada.
 */
object NovauraIcons {

    val Pause: ImageVector
        get() {
            if (_pause != null) return _pause!!
            _pause = materialIcon(name = "Filled.Pause") {
                materialPath {
                    moveTo(6f, 19f)
                    horizontalLineTo(10f)
                    verticalLineTo(5f)
                    horizontalLineTo(6f)
                    close()
                    moveTo(14f, 5f)
                    verticalLineTo(19f)
                    horizontalLineTo(18f)
                    verticalLineTo(5f)
                    close()
                }
            }
            return _pause!!
        }
    private var _pause: ImageVector? = null

    val SkipNext: ImageVector
        get() {
            if (_skipNext != null) return _skipNext!!
            _skipNext = materialIcon(name = "Filled.SkipNext") {
                materialPath {
                    moveTo(6f, 18f)
                    lineTo(14.5f, 12f)
                    lineTo(6f, 6f)
                    close()
                    moveTo(16f, 6f)
                    verticalLineTo(18f)
                    horizontalLineTo(18f)
                    verticalLineTo(6f)
                    close()
                }
            }
            return _skipNext!!
        }
    private var _skipNext: ImageVector? = null

    val SkipPrevious: ImageVector
        get() {
            if (_skipPrevious != null) return _skipPrevious!!
            _skipPrevious = materialIcon(name = "Filled.SkipPrevious") {
                materialPath {
                    moveTo(6f, 6f)
                    horizontalLineTo(8f)
                    verticalLineTo(18f)
                    horizontalLineTo(6f)
                    close()
                    moveTo(9.5f, 12f)
                    lineTo(18f, 18f)
                    verticalLineTo(6f)
                    close()
                }
            }
            return _skipPrevious!!
        }
    private var _skipPrevious: ImageVector? = null

    val MusicNote: ImageVector
        get() {
            if (_musicNote != null) return _musicNote!!
            _musicNote = materialIcon(name = "Rounded.MusicNote") {
                materialPath {
                    moveTo(12f, 3f)
                    verticalLineTo(13.55f)
                    curveTo(11.41f, 13.21f, 10.73f, 13f, 10f, 13f)
                    curveTo(7.79f, 13f, 6f, 14.79f, 6f, 17f)
                    reflectiveCurveTo(7.79f, 21f, 10f, 21f)
                    reflectiveCurveTo(14f, 19.21f, 14f, 17f)
                    verticalLineTo(7f)
                    horizontalLineTo(18f)
                    verticalLineTo(3f)
                    close()
                    moveTo(10f, 19f)
                    curveTo(8.9f, 19f, 8f, 18.1f, 8f, 17f)
                    reflectiveCurveTo(8.9f, 15f, 10f, 15f)
                    reflectiveCurveTo(12f, 15.9f, 12f, 17f)
                    reflectiveCurveTo(11.1f, 19f, 10f, 19f)
                    close()
                }
            }
            return _musicNote!!
        }
    private var _musicNote: ImageVector? = null

    val LibraryMusic: ImageVector
        get() {
            if (_libraryMusic != null) return _libraryMusic!!
            _libraryMusic = materialIcon(name = "Outlined.LibraryMusic") {
                materialPath {
                    moveTo(20f, 2f)
                    horizontalLineTo(8f)
                    curveTo(6.9f, 2f, 6f, 2.9f, 6f, 4f)
                    verticalLineTo(16f)
                    curveTo(6f, 17.1f, 6.9f, 18f, 8f, 18f)
                    horizontalLineTo(20f)
                    curveTo(21.1f, 18f, 22f, 17.1f, 22f, 16f)
                    verticalLineTo(4f)
                    curveTo(22f, 2.9f, 21.1f, 2f, 20f, 2f)
                    close()
                    moveTo(20f, 16f)
                    horizontalLineTo(8f)
                    verticalLineTo(4f)
                    horizontalLineTo(20f)
                    close()
                    moveTo(12.5f, 15f)
                    curveTo(13.88f, 15f, 15f, 13.88f, 15f, 12.5f)
                    verticalLineTo(7f)
                    horizontalLineTo(18f)
                    verticalLineTo(5f)
                    horizontalLineTo(12f)
                    verticalLineTo(10.51f)
                    curveTo(11.58f, 10.19f, 11.07f, 10f, 10.5f, 10f)
                    curveTo(9.12f, 10f, 8f, 11.12f, 8f, 12.5f)
                    reflectiveCurveTo(9.12f, 15f, 10.5f, 15f)
                    reflectiveCurveTo(13f, 13.88f, 13f, 12.5f)
                    verticalLineTo(7f)
                    horizontalLineTo(15f)
                    verticalLineTo(12.5f)
                    curveTo(15f, 13.88f, 13.88f, 15f, 12.5f, 15f)
                    close()
                }
            }
            return _libraryMusic!!
        }
    private var _libraryMusic: ImageVector? = null

    val Repeat: ImageVector
        get() {
            if (_repeat != null) return _repeat!!
            _repeat = materialIcon(name = "Filled.Repeat") {
                materialPath {
                    moveTo(7f, 7f)
                    horizontalLineTo(17f)
                    verticalLineTo(10f)
                    lineTo(21f, 6f)
                    lineTo(17f, 2f)
                    verticalLineTo(5f)
                    horizontalLineTo(5f)
                    verticalLineTo(11f)
                    horizontalLineTo(7f)
                    close()
                    moveTo(17f, 17f)
                    horizontalLineTo(7f)
                    verticalLineTo(14f)
                    lineTo(3f, 18f)
                    lineTo(7f, 22f)
                    verticalLineTo(19f)
                    horizontalLineTo(19f)
                    verticalLineTo(13f)
                    horizontalLineTo(17f)
                    close()
                }
            }
            return _repeat!!
        }
    private var _repeat: ImageVector? = null

    val RepeatOne: ImageVector
        get() {
            if (_repeatOne != null) return _repeatOne!!
            _repeatOne = materialIcon(name = "Filled.RepeatOne") {
                materialPath {
                    moveTo(7f, 7f)
                    horizontalLineTo(17f)
                    verticalLineTo(10f)
                    lineTo(21f, 6f)
                    lineTo(17f, 2f)
                    verticalLineTo(5f)
                    horizontalLineTo(5f)
                    verticalLineTo(11f)
                    horizontalLineTo(7f)
                    close()
                    moveTo(17f, 17f)
                    horizontalLineTo(7f)
                    verticalLineTo(14f)
                    lineTo(3f, 18f)
                    lineTo(7f, 22f)
                    verticalLineTo(19f)
                    horizontalLineTo(19f)
                    verticalLineTo(13f)
                    horizontalLineTo(17f)
                    close()
                    moveTo(13f, 15f)
                    verticalLineTo(9f)
                    horizontalLineTo(12f)
                    lineTo(10f, 10f)
                    verticalLineTo(11f)
                    horizontalLineTo(11.5f)
                    verticalLineTo(15f)
                    close()
                }
            }
            return _repeatOne!!
        }
    private var _repeatOne: ImageVector? = null

    val Shuffle: ImageVector
        get() {
            if (_shuffle != null) return _shuffle!!
            _shuffle = materialIcon(name = "Filled.Shuffle") {
                materialPath {
                    moveTo(10.59f, 9.17f)
                    lineTo(5.41f, 4f)
                    lineTo(4f, 5.41f)
                    lineTo(9.17f, 10.58f)
                    lineTo(10.59f, 9.17f)
                    close()
                    moveTo(14.5f, 4f)
                    lineTo(16.54f, 6.04f)
                    lineTo(4f, 18.59f)
                    lineTo(5.41f, 20f)
                    lineTo(17.96f, 7.46f)
                    lineTo(20f, 9.5f)
                    verticalLineTo(4f)
                    close()
                    moveTo(14.83f, 14.41f)
                    lineTo(13.42f, 15.82f)
                    lineTo(15.54f, 17.94f)
                    lineTo(14.5f, 20f)
                    horizontalLineTo(20f)
                    verticalLineTo(14.5f)
                    lineTo(17.96f, 16.54f)
                    close()
                }
            }
            return _shuffle!!
        }
    private var _shuffle: ImageVector? = null

    val Album: ImageVector
        get() {
            if (_album != null) return _album!!
            _album = materialIcon(name = "Filled.Album") {
                materialPath {
                    moveTo(12f, 2f)
                    curveTo(17.52f, 2f, 22f, 7.52f, 22f, 12f)
                    curveTo(22f, 16.48f, 17.52f, 22f, 12f, 22f)
                    curveTo(6.48f, 22f, 2f, 16.48f, 2f, 12f)
                    curveTo(2f, 7.52f, 6.48f, 2f, 12f, 2f)
                    close()
                    moveTo(12f, 8f)
                    curveTo(14.21f, 8f, 16f, 9.79f, 16f, 12f)
                    curveTo(16f, 14.21f, 14.21f, 16f, 12f, 16f)
                    curveTo(9.79f, 16f, 8f, 14.21f, 8f, 12f)
                    curveTo(8f, 9.79f, 9.79f, 8f, 12f, 8f)
                    close()
                }
            }
            return _album!!
        }
    private var _album: ImageVector? = null

    val QueueMusic: ImageVector
        get() {
            if (_queueMusic != null) return _queueMusic!!
            _queueMusic = materialIcon(name = "Filled.QueueMusic") {
                materialPath {
                    moveTo(15f, 6f)
                    horizontalLineTo(3f)
                    verticalLineTo(8f)
                    horizontalLineTo(15f)
                    verticalLineTo(6f)
                    close()
                    moveTo(15f, 10f)
                    horizontalLineTo(3f)
                    verticalLineTo(12f)
                    horizontalLineTo(15f)
                    verticalLineTo(10f)
                    close()
                    moveTo(3f, 16f)
                    horizontalLineTo(11f)
                    verticalLineTo(14f)
                    horizontalLineTo(3f)
                    verticalLineTo(16f)
                    close()
                    moveTo(17f, 6f)
                    verticalLineTo(14.18f)
                    curveTo(16.69f, 14.07f, 16.35f, 14f, 16f, 14f)
                    curveTo(14.34f, 14f, 13f, 15.34f, 13f, 17f)
                    reflectiveCurveTo(14.34f, 20f, 16f, 20f)
                    reflectiveCurveTo(19f, 18.66f, 19f, 17f)
                    verticalLineTo(8f)
                    horizontalLineTo(22f)
                    verticalLineTo(6f)
                    horizontalLineTo(17f)
                    close()
                }
            }
            return _queueMusic!!
        }
    private var _queueMusic: ImageVector? = null

    val Sell: ImageVector
        get() {
            if (_sell != null) return _sell!!
            _sell = materialIcon(name = "Filled.Sell") {
                materialPath {
                    moveTo(21.41f, 11.58f)
                    lineTo(12.41f, 2.58f)
                    curveTo(12.05f, 2.22f, 11.55f, 2f, 11f, 2f)
                    horizontalLineTo(4f)
                    curveTo(2.9f, 2f, 2f, 2.9f, 2f, 4f)
                    verticalLineTo(11f)
                    curveTo(2f, 11.55f, 2.22f, 12.05f, 2.59f, 12.42f)
                    lineTo(11.59f, 21.42f)
                    curveTo(11.95f, 21.78f, 12.45f, 22f, 13f, 22f)
                    curveTo(13.55f, 22f, 14.05f, 21.78f, 14.41f, 21.41f)
                    lineTo(21.41f, 14.41f)
                    curveTo(21.78f, 14.05f, 22f, 13.55f, 22f, 13f)
                    curveTo(22f, 12.44f, 21.78f, 11.94f, 21.41f, 11.58f)
                    close()
                    moveTo(5.5f, 7f)
                    curveTo(4.67f, 7f, 4f, 6.33f, 4f, 5.5f)
                    reflectiveCurveTo(4.67f, 4f, 5.5f, 4f)
                    reflectiveCurveTo(7f, 4.67f, 7f, 5.5f)
                    reflectiveCurveTo(6.33f, 7f, 5.5f, 7f)
                    close()
                }
            }
            return _sell!!
        }
    private var _sell: ImageVector? = null

    val Folder: ImageVector
        get() {
            if (_folder != null) return _folder!!
            _folder = materialIcon(name = "Filled.Folder") {
                materialPath {
                    moveTo(10f, 4f)
                    horizontalLineTo(4f)
                    curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
                    lineTo(2f, 18f)
                    curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
                    horizontalLineTo(20f)
                    curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
                    verticalLineTo(8f)
                    curveTo(22f, 6.9f, 21.1f, 6f, 20f, 6f)
                    horizontalLineTo(12f)
                    lineTo(10f, 4f)
                    close()
                }
            }
            return _folder!!
        }
    private var _folder: ImageVector? = null

    val History: ImageVector
        get() {
            if (_history != null) return _history!!
            _history = materialIcon(name = "Filled.History") {
                materialPath {
                    moveTo(13f, 3f)
                    curveTo(8.03f, 3f, 4f, 7.03f, 4f, 12f)
                    horizontalLineTo(1f)
                    lineTo(4.89f, 15.89f)
                    lineTo(4.96f, 16.03f)
                    lineTo(9f, 12f)
                    horizontalLineTo(6f)
                    curveTo(6f, 8.13f, 9.13f, 5f, 13f, 5f)
                    reflectiveCurveTo(20f, 8.13f, 20f, 12f)
                    reflectiveCurveTo(16.87f, 19f, 13f, 19f)
                    curveTo(11.07f, 19f, 9.32f, 18.21f, 8.06f, 16.94f)
                    lineTo(6.64f, 18.36f)
                    curveTo(8.27f, 19.99f, 10.51f, 21f, 13f, 21f)
                    curveTo(17.97f, 21f, 22f, 16.97f, 22f, 12f)
                    reflectiveCurveTo(17.97f, 3f, 13f, 3f)
                    close()
                    moveTo(12f, 8f)
                    verticalLineTo(13f)
                    lineTo(16.25f, 15.52f)
                    lineTo(17f, 14.24f)
                    lineTo(13.5f, 12.15f)
                    verticalLineTo(8f)
                    close()
                }
            }
            return _history!!
        }
    private var _history: ImageVector? = null
}

fun HomeTab.icon(): ImageVector = when (this) {
    HomeTab.SONGS -> NovauraIcons.MusicNote
    HomeTab.ARTISTS -> Icons.Filled.Person
    HomeTab.ALBUMS -> NovauraIcons.Album
    HomeTab.PLAYLISTS -> NovauraIcons.QueueMusic
    HomeTab.GENRES -> NovauraIcons.Sell
    HomeTab.FOLDERS -> NovauraIcons.Folder
}