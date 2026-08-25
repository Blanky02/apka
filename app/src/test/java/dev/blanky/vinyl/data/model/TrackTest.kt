package dev.blanky.vinyl.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackTest {

    @Test
    fun `formatDuration handles null and negative`() {
        assertEquals("--:--", Track.formatDuration(null))
        assertEquals("--:--", Track.formatDuration(-1))
    }

    @Test
    fun `formatDuration mm ss`() {
        assertEquals("3:45", Track.formatDuration(225_000))
        assertEquals("0:05", Track.formatDuration(5_000))
        assertEquals("0:00", Track.formatDuration(0))
    }

    @Test
    fun `formatDuration h mm ss`() {
        assertEquals("1:00:00", Track.formatDuration(3_600_000))
        assertEquals("2:07:09", Track.formatDuration(7629_000))
    }

    @Test
    fun `qualityBadge maps tiers`() {
        assertEquals("24/192", Track.qualityBadge("HI_RES_LOSSLESS"))
        assertEquals("Hi-Res", Track.qualityBadge("HI_RES"))
        assertEquals("Lossless", Track.qualityBadge("LOSSLESS"))
        assertEquals("320k", Track.qualityBadge("HIGH"))
        assertEquals("128k", Track.qualityBadge("low"))
        assertEquals(null, Track.qualityBadge(null))
        assertEquals(null, Track.qualityBadge(""))
        assertEquals("custom", Track.qualityBadge("custom"))
    }

    @Test
    fun `dedupeKey normalizes artist case`() {
        val a = Track("mono-1", "monochrome", "Song", listOf("Artist"), null, null, null, null)
        val b = Track("oct-2", "octave", "Song", listOf("aRtiSt"), null, null, null, null)
        assertEquals(a.dedupeKey(), b.dedupeKey())
    }

    @Test
    fun `artistText falls back when empty`() {
        val t = Track("mono-1", "monochrome", "Song", emptyList(), null, null, null, null)
        assertEquals("Nieznany artysta", t.artistText)
    }
}
