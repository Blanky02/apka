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

    @Test
    fun `extractStreamUrl plain url body`() {
        assertEquals(
            "https://cdn.example.com/a.flac?token=1",
            TrackParser.extractStreamUrl("https://cdn.example.com/a.flac?token=1\n")
        )
    }

    @Test
    fun `extractStreamUrl from typical keys`() {
        assertEquals(
            "https://cdn.example.com/x.flac",
            TrackParser.extractStreamUrl("""{"url":"https://cdn.example.com/x.flac"}""")
        )
        assertEquals(
            "https://cdn.example.com/y.flac",
            TrackParser.extractStreamUrl("""{"data":{"originalTrackUrl":"https://cdn.example.com/y.flac"}}""")
        )
    }

    @Test
    fun `extractStreamUrl prefers stream key over cover`() {
        val json = """{"cover":"https://img.example.com/a.jpg","streamUrl":"https://cdn.example.com/z.flac"}"""
        assertEquals("https://cdn.example.com/z.flac", TrackParser.extractStreamUrl(json))
    }

    @Test
    fun `extractStreamUrl decodes base64 manifest`() {
        val mpd = "<MPD><BaseURL>https://cdn.example.com/dash/1.mpd</BaseURL></MPD>"
        val b64 = java.util.Base64.getEncoder().encodeToString(mpd.toByteArray())
        val json = """{"manifest":"$b64","mimeType":"application/dash+xml"}"""
        assertEquals("https://cdn.example.com/dash/1.mpd", TrackParser.extractStreamUrl(json))
    }

    @Test
    fun `extractStreamUrl finds url in xml manifest`() {
        val mpd = """<?xml version="1.0"?><MPD><BaseURL>https://cdn.example.com/m/1.m4s</BaseURL></MPD>"""
        assertEquals("https://cdn.example.com/m/1.m4s", TrackParser.extractStreamUrl(mpd))
    }

    @Test
    fun `extractStreamUrl returns null for error payloads`() {
        assertNull(TrackParser.extractStreamUrl("""{"error":"track not available in this quality"}"""))
        assertNull(TrackParser.extractStreamUrl("""{"success":false,"message":"nope"}"""))
        assertNull(TrackParser.extractStreamUrl(""))
        assertNull(TrackParser.extractStreamUrl("{}"))
    }

    @Test
    fun `parses octave api search shape with album cover`() {
        val json = """
            {"tracks":[
              {"id":"3995047821","title":"Sexy Nana",
               "artist":{"id":"8909272","name":"Aya Nakamura"},
               "album":{"id":"973081821","title":"Sexy Nana",
                        "cover_big":"https://cdn-images.dzcdn.net/images/cover/x/250x250.jpg"},
               "duration":156,"explicit":false,"rank":995879}
            ]}
        """.trimIndent()
        val tracks = TrackParser.parseTracks(json, "octave", "oct")
        assertNotNull(tracks)
        assertEquals(1, tracks!!.size)
        val t = tracks[0]
        assertEquals("oct-3995047821", t.id)
        assertEquals("Sexy Nana", t.title)
        assertEquals(listOf("Aya Nakamura"), t.artists)
        assertEquals("Sexy Nana", t.album)
        assertEquals(156_000L, t.durationMs)
        assertEquals("https://cdn-images.dzcdn.net/images/cover/x/250x250.jpg", t.coverUrl)
    }

    @Test
    fun `extractManifestUri finds uri in trackManifests response`() {
        val json = """
            {"version":"2.10","data":{"data":{"id":4167846,"duration":271,
              "attributes":{"uri":"https://cdn.example.com/manifests/1.mpd","formats":["FLAC"]}}}}
        """.trimIndent()
        assertEquals("https://cdn.example.com/manifests/1.mpd", TrackParser.extractManifestUri(json))
    }

    @Test
    fun `extractManifestUri handles data attributes wrapper`() {
        val json = """{"data":{"attributes":{"uri":"https://cdn.example.com/1.m3u8"}}}"""
        assertEquals("https://cdn.example.com/1.m3u8", TrackParser.extractManifestUri(json))
    }

    @Test
    fun `extractManifestUri null when no attributes uri`() {
        assertNull(TrackParser.extractManifestUri("""{"detail":"Not Found"}"""))
        assertNull(TrackParser.extractManifestUri("""{"data":{"id":1}}"""))
        assertNull(TrackParser.extractManifestUri("not json"))
    }
}
