/*
 * Copyright (c) 2026 Auxio Project
 * MusicSettingsTest.kt is part of Auxio.
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
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.oxycblt.auxio.R

class MusicSettingsTest {
    private val map = mutableMapOf<String, Any?>()
    private lateinit var prefs: SharedPreferences
    private lateinit var context: Context

    @Before
    fun setUp() {
        map.clear()
        val editor =
            mockk<SharedPreferences.Editor>(relaxed = true) {
                every { putString(any(), any()) } answers
                    {
                        val k = firstArg<String>()
                        val v = secondArg<String?>()
                        if (v == null) map.remove(k) else map[k] = v
                        this@mockk
                    }
                every { remove(any()) } answers
                    {
                        map.remove(firstArg())
                        this@mockk
                    }
            }
        prefs =
            mockk<SharedPreferences>(relaxed = true) {
                every { edit() } returns editor
                every { getString(any(), any()) } answers
                    {
                        (map[firstArg()] as? String) ?: secondArg()
                    }
            }
        context =
            mockk<Context>(relaxed = true) {
                every { applicationContext } returns this
                every { getString(R.string.set_key_library_revision) } returns "revision_key"
            }
        mockkStatic(PreferenceManager::class)
        every { PreferenceManager.getDefaultSharedPreferences(any()) } returns prefs
    }

    @After
    fun tearDown() {
        unmockkStatic(PreferenceManager::class)
    }

    @Test
    fun revision_handlesNullAndCorruptStringSafely() {
        val settings = MusicSettingsImpl(context)
        val valid = UUID.randomUUID()

        settings.revision = valid
        assertEquals(valid, settings.revision)

        settings.revision = null
        assertNull(map["revision_key"])
        assertNull(settings.revision)

        // Pre-existing corrupt string written by older versions
        map["revision_key"] = "null"
        assertNull(settings.revision)
        map["revision_key"] = "not-a-uuid"
        assertNull(settings.revision)
    }
}
