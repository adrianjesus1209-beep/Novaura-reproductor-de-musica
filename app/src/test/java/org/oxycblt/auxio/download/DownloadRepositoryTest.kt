/*
 * Copyright (c) 2026 Auxio Project
 * DownloadRepositoryTest.kt is part of Auxio.
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
 
package org.oxycblt.auxio.download

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRepositoryTest {
    private val repository = DownloadRepository(mockk<Context>(relaxed = true))

    @Test
    fun sanitizeFileComponent_removesTraversalControlCharsAndUnsafeFatChars() {
        val sanitized = repository.sanitizeFileComponent("../../etc/passwd\u0000:*?\"<>|")
        assertFalse(sanitized.contains('/'))
        assertFalse(sanitized.contains('\\'))
        assertFalse(sanitized.contains('\u0000'))
        assertFalse(sanitized.contains(".."))

        assertEquals("track", repository.sanitizeFileComponent("...   ..."))
        assertEquals("fallback", repository.sanitizeFileComponent(".", fallback = "fallback"))
        assertEquals("fallback", repository.sanitizeFileComponent("..", fallback = "fallback"))
    }

    @Test
    fun sanitizeFileComponent_disambiguatesReservedFatDeviceNames() {
        assertEquals("CON_", repository.sanitizeFileComponent("CON"))
        assertEquals("nul_", repository.sanitizeFileComponent("nul"))
        assertEquals("prn_", repository.sanitizeFileComponent("prn"))
        assertEquals("com1_", repository.sanitizeFileComponent("com1"))
        assertEquals("LPT9_", repository.sanitizeFileComponent("LPT9"))
    }

    @Test
    fun sanitizeFileComponent_truncatesOnUtf8ByteBoundaries() {
        val multiByteInput = "Canción Águila 日本語テスト"
        val result = repository.sanitizeFileComponent(multiByteInput, maxBytes = 12)
        val bytes = result.toByteArray(Charsets.UTF_8)

        assertTrue(bytes.size <= 12)
        assertTrue(result.isNotEmpty())
        assertEquals(result, String(bytes, Charsets.UTF_8))

        val emojiOnly = "🎵🎶🎵🎶"
        val emojiTooTight =
            repository.sanitizeFileComponent(emojiOnly, fallback = "alt", maxBytes = 2)
        assertEquals("alt", emojiTooTight)
    }
}
