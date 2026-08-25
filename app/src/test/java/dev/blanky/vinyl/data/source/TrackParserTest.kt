package dev.blanky.vinyl.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackParserTest {

    @Test
    fun `parses bare array of tracks`() {
        val json = """
            [
              {"id": 123, "title": "Butterfly", "artists": ["Grizzly Bear"], "duration": 205, "audioQuality": "HI_RES_LOSSLESS"},
              {"id": 124, "title": "Two Weeks", "artists": [{"name": "Grizzly Bear"}], "duration": 210}
            ]
        """.trimIndent()
        val tracks = TrackParser.parseTracks(json, "monochrome", "mono")
        assertNotNull(tracks)
        assertEquals(2, tracks!!.size)
        assertEquals("mono-123", tracks[0].id)
        assertEquals("Butterfly", tracks[0].title)
        assertEquals(listOf("Grizzly Bear"), tracks[0].artists)
        assertEquals(205_000L, tracks[0].durationMs)
        assertEquals("HI_RES_LOSSLESS", tracks[0].maxQuality)
        assertEquals(listOf("Grizzly Bear"), tracks[1].artists)
    }

    @Test
    fun `parses object wrapper with tracks key`() {
        val json = """
            {"tracks": [{"id": "a1", "title": "X", "artist": "Y", "durationSec": 30}]}
        """.trimIndent()
        val tracks = TrackParser.parseTracks(json, "monochrome", "mono")
        assertNotNull(tracks)
        assertEquals(1, tracks!!.size)
        assertEquals("mono-a1", tracks[0].id)
        assertEquals(30_000L, tracks[0].durationMs)
    }

    @Test
    fun `parses nested data items wrapper`() {
        val json = """
            {"data": {"items": [{"id": 7, "name": "Song", "artists": "Solo Artist"}]}}
        """.trimIndent()
        val tracks = TrackParser.parseTracks(json, "octave", "oct")
        assertNotNull(tracks)
        assertEquals(1, tracks!!.size)
        assertEquals(listOf("Solo Artist"), tracks[0].artists)
    }

    @Test
    fun `parses duration in milliseconds`() {
        val json = """
            [{"id": 1, "title": "X", "durationMs": 123456}]
        """.trimIndent()
        val tracks = TrackParser.parseTracks(json, "monochrome", "mono")
        assertEquals(123_000L, tracks!![0].durationMs)
    }

    @Test
    fun `returns null for garbage or empty`() {
        assertNull(TrackParser.parseTracks("not json", "monochrome", "mono"))
        assertNull(TrackParser.parseTracks("{}", "monochrome", "mono"))
        assertNull(TrackParser.parseTracks("[]", "monochrome", "mono"))
        assertNull(TrackParser.parseTracks("""{"unrelated": [1,2,3]}""", "monochrome", "mono"))
    }

    @Test
    fun `skips invalid entries and keeps valid ones`() {
        val json = """
            [{"id": 1, "title": "OK", "artists": "A"}, {"broken": true}, {"id": 2}]
        """.trimIndent()
        val tracks = TrackParser.parseTracks(json, "monochrome", "mono")
        assertEquals(1, tracks?.size)
        assertEquals("OK", tracks!![0].title)
    }

    @Test
    fun `firstHttpUrl finds manifest in json`() {
        val json = """{"status":"ok","stream":{"url":"https://cdn.example.com/audio.flac?token=abc"}}"""
        assertEquals("https://cdn.example.com/audio.flac?token=abc", TrackParser.firstHttpUrl(json))
    }

    @Test
    fun `firstHttpUrl null for non urls`() {
        assertNull(TrackParser.firstHttpUrl("""{"message":"no url here"}"""))
        assertNull(TrackParser.firstHttpUrl("plain text"))
        assertTrue(
            TrackParser.firstHttpUrl("""{"items":[{"cover":"https://x.io/a.jpg"}]}""") == "https://x.io/a.jpg"
        )
    }
}
