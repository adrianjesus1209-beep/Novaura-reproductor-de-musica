/*
 * Copyright (c) 2026 Auxio Project
 * MediaSessionCoordinatorTest.kt is part of Auxio.
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

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSessionCoordinatorTest {
    @Test
    fun activateLocalAndOnline_mutuallyPausesPreviousOwner() {
        val coordinator = MediaSessionCoordinator()
        var localPaused = 0
        var onlinePaused = 0
        coordinator.registerLocal { localPaused++ }
        coordinator.registerOnline { onlinePaused++ }

        assertEquals(MediaSessionCoordinator.Owner.LOCAL, coordinator.activeOwner)

        coordinator.activateOnline()
        assertEquals(MediaSessionCoordinator.Owner.ONLINE, coordinator.activeOwner)
        assertEquals(1, localPaused)
        assertEquals(0, onlinePaused)

        // Repeated activation by the same owner must not redundantly pause the other.
        coordinator.activateOnline()
        assertEquals(1, localPaused)

        coordinator.activateLocal()
        assertEquals(MediaSessionCoordinator.Owner.LOCAL, coordinator.activeOwner)
        assertEquals(1, onlinePaused)
    }
}
