package dev.blanky.vinyl.data.source.octave

import android.net.Uri
import dev.blanky.vinyl.data.model.AudioQuality
import dev.blanky.vinyl.data.model.Track
import dev.blanky.vinyl.data.source.ApiLog
import dev.blanky.vinyl.data.source.MusicSource
import dev.blanky.vinyl.data.source.SourceStatus
import dev.blanky.vinyl.data.source.StreamResult
import dev.blanky.vinyl.data.source.TrackParser
import dev.blanky.vinyl.data.settings.VinylSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Źródło Octave (music.octavestreaming.com).
 *
 * Uwaga: Octave nie publikuje dokumentacji API, a endpointy nie są
 * traktowane jako stabilne. Dlatego adresy wyszukiwarki i strumienia są
 * KONFIGUROWALNE w ustawieniach (szablony z placeholderami {query} i {id}),
 * a przycisk "Wykryj API" testuje zestaw typowych wariantów i zapisuje
 * pierwszy, który zwróci poprawną listę utworów.
 *
 * Sprawdzone endpointy (stan: sierpień 2026):
 *   GET /api/search?q={query}          -> {"tracks":[...]}
 *   GET /api/track/{id}[/stream]       -> {"url": ..., "preview": ..., "gated": bool}
 * Pełne strumieniowanie jest "gated" (wymaga konta/tokenu) — dla anonimowych
 * klientów API zwraca tylko 30-sekundowy `preview`.
 */
class OctaveSource(
    private val http: OkHttpClient,
    private val settings: VinylSettings,
) : MusicSource {

    override val id: String = "octave"
    override val displayName: String = "Octave"

    private val userAgent = "Vinyl/0.1 (Android)"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun searchTracks(query: String, limit: Int): List<Track> = withContext(Dispatchers.IO) {
        val base = settings.octaveBase.first().trimEnd('/')
        val template = settings.octaveSearchTemplate.first()
        if (!settings.octaveEnabled.first()) return@withContext emptyList()
        val url = buildUrl(base, template, mapOf("query" to query, "limit" to limit.toString()))
        try {
            val resp = get(url)
            if (resp.code in 200..299) {
                val parsed = TrackParser.parseTracks(resp.body, id, ID_PREFIX)
                if (parsed != null) {
                    ApiLog.record(displayName, "search", url, resp.code, "${parsed.size} utworów", ok = true)
                    return@withContext parsed.take(limit)
                }
                ApiLog.record(displayName, "search", url, resp.code, "format: ${resp.body.take(200)}", ok = false)
            } else {
                ApiLog.record(displayName, "search", url, resp.code, resp.body.take(200), ok = false)
            }
        } catch (e: Exception) {
            ApiLog.record(displayName, "search", url, -1, e.message ?: "błąd sieci", ok = false)
        }
        emptyList()
    }

    override suspend fun resolveStreamUrl(track: Track, quality: AudioQuality): StreamResult =
        withContext(Dispatchers.IO) {
            val base = settings.octaveBase.first().trimEnd('/')
            val template = settings.octaveStreamTemplate.first()
            val tid = track.id.removePrefix("$ID_PREFIX-")
            val url = buildUrl(base, template, mapOf("id" to tid, "quality" to quality.tier))
            try {
                val resp = get(url)
                if (resp.code in 200..299) {
                    val fromJson = parseStreamResponse(resp.body)
                    val fromBody = resp.body.trim().takeIf { it.startsWith("http") }
                    val finalUrl = fromJson ?: fromBody ?: (if (resp.finalUrl != url) resp.finalUrl else null)
                    if (finalUrl != null) {
                        val summary = if (finalUrl.contains("cdnt-preview")) {
                            finalUrl.take(140) + " (preview 30 s — pełny strumień wymaga konta)"
                        } else {
                            finalUrl.take(140)
                        }
                        ApiLog.record(displayName, "stream", url, resp.code, summary, ok = true)
                        return@withContext StreamResult.Success(finalUrl)
                    }
                    ApiLog.record(displayName, "stream", url, resp.code, "brak URL: ${resp.body.take(200)}", ok = false)
                } else {
                    ApiLog.record(displayName, "stream", url, resp.code, resp.body.take(200), ok = false)
                }
            } catch (e: Exception) {
                ApiLog.record(displayName, "stream", url, -1, e.message ?: "błąd sieci", ok = false)
            }
            StreamResult.Error("Octave: nie udało się pobrać strumienia — sprawdź szablony endpointów w Ustawieniach")
        }

    /**
     * Parsuje odpowiedź `/api/track/{id}` lub `/api/track/{id}/stream`:
     * `{"url": "...", "preview": "...", "id": "...", "quality": "...", "gated": bool}`.
     *
     * Gdy `gated` jest ustawione (pełny strumień wymaga konta/tokenu), zwraca
     * `preview` (30 s), żeby zamiast twardego błędu grało przynajmniej zajawkę.
     * Gdy nie ma `gated` ani `preview`, zwraca `url` (stary/otwarty format).
     */
    private fun parseStreamResponse(raw: String): String? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val obj = root as? JsonObject ?: return null
        fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull
        val url = str("url")
        val gated = str("gated") == "true"
        val preview = str("preview")
        return when {
            url != null && !gated -> url
            preview != null -> preview
            else -> url
        }
    }

    override suspend fun testConnection(): SourceStatus = withContext(Dispatchers.IO) {
        if (!settings.octaveEnabled.first()) {
            return@withContext SourceStatus(false, null, "Wyłączone w ustawieniach")
        }
        val base = settings.octaveBase.first().trimEnd('/')
        val tracks = searchTracks("a", 1)
        if (tracks.isNotEmpty()) {
            SourceStatus(true, base, "OK — wyszukiwarka odpowiada")
        } else {
            SourceStatus(false, base, "Brak odpowiedzi — użyj przycisku „Wykryj API” lub podaj szablony ręcznie")
        }
    }

    /**
     * Automatyczne wykrywanie endpointu wyszukiwarki: testuje typowe warianty
     * i zapisuje pierwszy, który zwróci rozpoznawalną listę utworów.
     */
    suspend fun probeSearchEndpoint(): ProbeResult = withContext(Dispatchers.IO) {
        val base = settings.octaveBase.first().trimEnd('/')
        for (candidate in SEARCH_CANDIDATES) {
            val url = buildUrl(base, candidate, mapOf("query" to "test", "limit" to "5"))
            try {
                val resp = get(url)
                val parsed = if (resp.code in 200..299) {
                    TrackParser.parseTracks(resp.body, id, ID_PREFIX)
                } else null
                if (parsed != null) {
                    settings.setOctaveSearchTemplate(candidate)
                    ApiLog.record(displayName, "probe", url, resp.code, "ZNALEZIONO: $candidate", ok = true)
                    return@withContext ProbeResult(found = true, endpoint = candidate, sampleSize = parsed.size)
                }
                ApiLog.record(displayName, "probe", url, resp.code, "brak", ok = false)
            } catch (e: Exception) {
                ApiLog.record(displayName, "probe", url, -1, e.message ?: "błąd", ok = false)
            }
        }
        ProbeResult(found = false, endpoint = null, sampleSize = 0)
    }

    data class ProbeResult(val found: Boolean, val endpoint: String?, val sampleSize: Int)

    private fun buildUrl(base: String, template: String, vars: Map<String, String>): String {
        val path = template
            .replace("{query}", vars["query"]?.let(Uri::encode).orEmpty())
            .replace("{id}", vars["id"].orEmpty())
            .replace("{limit}", vars["limit"].orEmpty())
            .replace("{quality}", vars["quality"].orEmpty())
        return if (path.startsWith("http")) path else "$base$path"
    }

    private data class HttpResponse(val code: Int, val body: String, val finalUrl: String)

    private fun get(url: String): HttpResponse {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json, */*")
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return HttpResponse(resp.code, body, resp.request.url.toString())
        }
    }

    companion object {
        const val ID_PREFIX = "oct"

        /** Typowe warianty endpointu wyszukiwarki (Octave nie ma publicznych docs). */
        val SEARCH_CANDIDATES: List<String> = listOf(
            "/api/search?q={query}",
            "/api/search?query={query}",
            "/search?q={query}",
            "/v1/search?q={query}",
            "/search/tracks?q={query}",
            "/tracks?q={query}",
            "/tracks/search?q={query}",
            "/s?q={query}",
            "/catalog/search?q={query}",
            "/music/search?q={query}",
            "/songs?q={query}",
        )
    }
}
