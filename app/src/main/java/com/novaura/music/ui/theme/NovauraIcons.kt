package com.novaura.music.ui.theme

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

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
}