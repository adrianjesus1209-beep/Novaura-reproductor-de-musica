/*
 * Copyright (c) 2026 Auxio Project
 * ChannelPipelineTest.kt is part of Auxio.
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
 
package org.oxycblt.musikr.util

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelPipelineTest {
    /**
     * A worker that throws used to leave the output channel open, so the next pipeline stage
     * blocked on it forever and the index never reached a terminal state (the UI scanned
     * indefinitely). The output must be closed with the cause so the failure is reported.
     */
    @Test
    fun mapParallel_whenAWorkerThrows_closesOutputAndReportsFailure() = runBlocking {
        val input = Channel<Int>()
        val output = Channel<Int>(4)
        val producer =
            async(Dispatchers.Default) {
                try {
                    for (value in 1..8) {
                        input.send(value)
                    }
                } finally {
                    input.close()
                }
            }

        // A real pipeline stage always drains the previous one, so keep a consumer attached.
        var consumerError: Throwable? = null
        val consumer =
            async(Dispatchers.Default) {
                try {
                    for (item in output) {
                        // consume
                    }
                } catch (e: Throwable) {
                    consumerError = e
                }
            }

        val result =
            withTimeout(10.seconds) {
                mapParallel(2, input, output, Dispatchers.Default) { value ->
                        if (value == 3) throw IllegalStateException("unparseable file")
                        value
                    }
                    .await()
            }
        producer.await()
        consumer.await()

        assertTrue("worker failure must be reported", result.isFailure)
        // Draining must terminate instead of hanging on the unclosed channel. Because the
        // channel is closed with the cause, the iteration rethrows it once the buffered items
        // are consumed.
        assertTrue(
            "expected the failure to propagate, got $consumerError",
            consumerError is IllegalStateException,
        )
    }
}
