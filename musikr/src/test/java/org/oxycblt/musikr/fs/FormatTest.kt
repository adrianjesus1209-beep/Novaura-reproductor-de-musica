/*
 * Copyright (c) 2026 Auxio Project
 * FormatTest.kt is part of Auxio.
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
 
package org.oxycblt.musikr.fs

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun infer_recognizesWebmMatroskaAndExtendedFormats() {
        assertEquals(Format.WebM(null), Format.infer("audio/webm", "audio/webm"))
        assertEquals(Format.WebM(Format.Opus), Format.infer("video/webm", "audio/opus"))
        assertEquals(Format.Matroska(null), Format.infer("audio/x-matroska", "audio/matroska"))
        assertEquals(Format.AIFF, Format.infer("audio/aiff", "audio/aiff"))
        assertEquals(
            Format.Speex,
            Format.infer("audio/ogg", "audio/speex").let { (it as Format.Ogg).containing },
        )
        assertEquals(Format.DSF, Format.infer("audio/dsf", "audio/dsf"))
        assertEquals(Format.DSDIFF, Format.infer("audio/dff", "audio/dff"))
        assertEquals(Format.Ogg(Format.FLAC), Format.infer("audio/ogg", "audio/flac"))
        assertEquals(Format.MPEG4(Format.AC3), Format.infer("audio/mp4", "audio/ac3"))
        assertEquals(Format.MPEG4(Format.EAC3), Format.infer("audio/mp4", "audio/eac3"))
        assertEquals(Format.MPEG4(Format.DTS), Format.infer("audio/mp4", "audio/dts"))
        assertEquals(Format.WebM(null), Format.infer("application/octet-stream", "audio/webm"))
    }
}
