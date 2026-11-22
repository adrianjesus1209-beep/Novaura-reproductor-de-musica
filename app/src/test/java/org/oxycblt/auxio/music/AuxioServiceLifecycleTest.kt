/*
 * Copyright (c) 2026 Auxio Project
 * AuxioServiceLifecycleTest.kt is part of Auxio.
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
 
package org.oxycblt.auxio.music

import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import javax.inject.Provider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.AuxioService
import org.oxycblt.auxio.download.DownloadRepository
import org.oxycblt.auxio.image.ImageSettings
import org.oxycblt.auxio.image.covers.SettingCovers
import org.oxycblt.musikr.cache.MutableCache
import org.oxycblt.musikr.playlist.db.StoredPlaylists

class AuxioServiceLifecycleTest {
    @Test
    fun shouldStopIdleService_neverStopsActivityStartedService() {
        assertFalse(
            AuxioService.shouldStopIdleService(
                startedFromBackgroundFgs = false,
                hasPlaybackNotification = false,
                isIndexing = false,
            )
        )
        assertFalse(
            AuxioService.shouldStopIdleService(
                startedFromBackgroundFgs = false,
                hasPlaybackNotification = false,
                isIndexing = true,
            )
        )
    }

    @Test
    fun shouldStopIdleService_neverStopsWhileIndexingOrPlaying() {
        assertFalse(
            AuxioService.shouldStopIdleService(
                startedFromBackgroundFgs = true,
                hasPlaybackNotification = false,
                isIndexing = true,
            )
        )
        assertFalse(
            AuxioService.shouldStopIdleService(
                startedFromBackgroundFgs = true,
                hasPlaybackNotification = true,
                isIndexing = false,
            )
        )
        assertTrue(
            AuxioService.shouldStopIdleService(
                startedFromBackgroundFgs = true,
                hasPlaybackNotification = false,
                isIndexing = false,
            )
        )
    }

    @Test
    fun requestIndex_wakesServiceAndDrainsPendingRequestOnWorkerRegister() {
        val context = mockk<Context>(relaxed = true)
        every { context.startService(any<Intent>()) } returns null
        val musicSettings = mockk<MusicSettings>(relaxed = true)
        every { musicSettings.isInitialScanCompleted } returns false
        val repository =
            MusicRepositoryImpl(
                context = context,
                cache = mockk<MutableCache>(relaxed = true),
                storedPlaylists = mockk<StoredPlaylists>(relaxed = true),
                settingCovers = mockk<SettingCovers>(relaxed = true),
                musicSettings = musicSettings,
                imageSettings = mockk<ImageSettings>(relaxed = true),
                downloadRepository = mockk<Provider<DownloadRepository>>(relaxed = true),
            )

        repository.requestIndex(withCache = false)

        verify(exactly = 1) { context.startService(any<Intent>()) }

        val worker = mockk<MusicRepository.IndexingWorker>(relaxed = true)
        repository.registerWorker(worker)

        verify(exactly = 1) {
            worker.requestIndex(withCache = false, forceStorageScan = true, silent = false)
        }
    }
}
