/*
 * Copyright (c) 2026 Auxio Project
 * ExploreStepTest.kt is part of Auxio.
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
 
package org.oxycblt.musikr.pipeline

import android.content.Context
import android.net.Uri
import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.oxycblt.musikr.Config
import org.oxycblt.musikr.Interpretation
import org.oxycblt.musikr.Storage
import org.oxycblt.musikr.cache.CacheResult
import org.oxycblt.musikr.cache.MutableCache
import org.oxycblt.musikr.covers.Cover
import org.oxycblt.musikr.covers.MutableCovers
import org.oxycblt.musikr.fs.AddedMs
import org.oxycblt.musikr.fs.Components
import org.oxycblt.musikr.fs.FS
import org.oxycblt.musikr.fs.FSUpdate
import org.oxycblt.musikr.fs.File
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.fs.Volume
import org.oxycblt.musikr.playlist.db.StoredPlaylists
import org.oxycblt.musikr.tag.interpret.Naming
import org.oxycblt.musikr.tag.interpret.Separators

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreStepTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun explore_withMoreFilesThanStageBuffer_completes() = runBlocking {
        val emitCount = PipelineTuning.stageBuffer * 3
        val storage = makeStorage()
        val step =
            ExploreStep.from(
                context = mockk<Context>(relaxed = true),
                config =
                    Config(
                        fs = EmittingFS(emitCount),
                        storage = storage,
                        interpretation =
                            Interpretation(
                                naming = mockk<Naming>(relaxed = true),
                                separators = mockk<Separators>(relaxed = true),
                            ),
                    ),
            )

        val explored = Channel<Explored>(PipelineTuning.stageBuffer)
        val received = AtomicInteger(0)
        val drain = launch(mainDispatcher) { for (item in explored) received.incrementAndGet() }

        val result = withTimeout(10.seconds) { step.explore(this, explored).await() }
        result.getOrThrow()

        drain.join()
        assertEquals(emitCount, received.get())
    }
}

private class EmittingFS(private val emitCount: Int) : FS {
    override fun explore(
        scope: CoroutineScope,
        files: Channel<File>,
    ): Deferred<Result<Unit>> =
        scope.async(Dispatchers.IO) {
            try {
                repeat(emitCount) { index -> files.send(file(index)) }
                Result.success(Unit)
            } catch (e: Throwable) {
                Result.failure(e)
            } finally {
                files.close()
            }
        }

    override fun track(): Flow<FSUpdate> = emptyFlow()

    companion object {
        private val volume =
            object : Volume.Internal {
                override val mediaStoreName: String? = null
                override val components: Components? = null

                override fun resolveName(context: Context) = "test"
            }

        private fun file(index: Int) =
            File(
                uri = mockk<Uri>(relaxed = true),
                path = Path(volume, Components.parseUnix("Music/track$index.mp3")),
                addedMs = mockk<AddedMs>(relaxed = true),
                modifiedMs = 0L,
                mimeType = "audio/mpeg",
                size = 1024L,
                parent = null,
            )
    }
}

private fun makeStorage(): Storage {
    val cache = mockk<MutableCache>(relaxed = true)
    coEvery { cache.read(any()) } coAnswers { CacheResult.Miss(firstArg()) }

    val storedPlaylists = mockk<StoredPlaylists>(relaxed = true)
    coEvery { storedPlaylists.read() } returns emptyList()

    return Storage(
        cache = cache,
        covers = mockk<MutableCovers<Cover>>(relaxed = true),
        storedPlaylists = storedPlaylists,
    )
}
