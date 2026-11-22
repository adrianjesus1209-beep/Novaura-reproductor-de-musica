/*
 * Copyright (c) 2026 Auxio Project
 * MediaSessionCoordinator.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.service

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arbitrates exclusive active ownership between local playback ([AuxioService] /
 * [MediaSessionHolder]) and online playback ([OnlinePlaybackService]) so that only one
 * MediaSessionCompat is active at a time and hardware media buttons route to the most recently
 * active engine.
 */
@Singleton
class MediaSessionCoordinator @Inject constructor() {
    enum class Owner {
        LOCAL,
        ONLINE,
    }

    @Volatile
    var activeOwner: Owner = Owner.LOCAL
        private set

    @Volatile private var pauseOnline: (() -> Unit)? = null
    @Volatile private var pauseLocal: (() -> Unit)? = null

    fun registerLocal(pauseHandler: () -> Unit) {
        pauseLocal = pauseHandler
    }

    fun unregisterLocal() {
        pauseLocal = null
    }

    fun registerOnline(pauseHandler: () -> Unit) {
        pauseOnline = pauseHandler
    }

    fun unregisterOnline() {
        pauseOnline = null
    }

    fun activateLocal() {
        if (activeOwner != Owner.LOCAL) {
            activeOwner = Owner.LOCAL
            pauseOnline?.invoke()
        }
    }

    fun activateOnline() {
        if (activeOwner != Owner.ONLINE) {
            activeOwner = Owner.ONLINE
            pauseLocal?.invoke()
        }
    }
}
