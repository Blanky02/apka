package dev.blanky.vinyl.data.source.monochrome

import android.net.Uri
import dev.blanky.vinyl.data.model.AudioQuality
import dev.blanky.vinyl.data.model.Track
import dev.blanky.vinyl.data.source.ApiLog
import dev.blanky.vinyl.data.source.MusicSource
import dev.blanky.vinyl.data.source.SourceStatus
import dev.blanky.vinyl.data.source.StreamResult
import dev.blanky.vinyl.data.source.TrackParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Źródło Monochrome (katalog Tidal, FLAC do 24-bit/192 kHz).
 *
 * API nie ma jednego stałego adresu — Monochrome działa z wielu instancji,
 * więc klient próbuje je po kolei i zapamiętuje tę, która odpowiada.
 *
 * Endpointy (zgodnie z dokumentacją społeczności Monochrome):
 *   GET /search/?s={query}&limit={n}
 *   GET /track/?id={tid}&quality={LOW|HIGH|LOSSLESS|HI_RES|HI_RES_LOSSLESS}
 *   GET /cover/?id={tid}
 *   GET /info/?id={tid}
 *   GET /lyrics/?id={tid}
 */
class MonochromeSource(
    private val http: OkHttpClient,
) : MusicSource {

    override val id: String = "monochrome"
    override val displayName: String = "Monochrome"

    private val instances: List<String> = DEFAULT_INSTANCES
    private var instanceIndex: Int = 0

    private val userAgent = "Vinyl/0.1 (Android)"

    override suspend fun searchTracks(query: String, limit: Int): List<Track> = withContext(Dispatchers.IO) {
        val n = instances.size
        var lastError = "brak instancji"
        for (i in 0 until n) {
            val base = instances[(instanceIndex + i) % n]
            val url = "$base/search/?s=${Uri.encode(query)}&limit=$limit"
            try {
                val resp = get(url)
                if (resp.code in 200..299) {
                    val parsed = TrackParser.parseTracks(resp.body, id, ID_PREFIX)
                    if (parsed != null) {
                        instanceIndex = i
                        ApiLog.record(displayName, "search", url, resp.code, "${parsed.size} utworów", ok = true)
                        return@withContext parsed
                    }
                    lastError = "nieoczekiwany format odpowiedzi"
                    ApiLog.record(displayName, "search", url, resp.code, resp.body.take(200), ok = false)
                } else {
                    lastError = "HTTP ${resp.code}"
                    ApiLog.record(displayName, "search", url, resp.code, resp.body.take(200), ok = false)
                }
            } catch (e: Exception) {
                lastError = e.message ?: "błąd sieci"
                ApiLog.record(displayName, "search", url, -1, lastError, ok = false)
            }
        }
        ApiLog.record(displayName, "search", "(wszystkie instancje)", -1, lastError, ok = false)
        emptyList()
    }

    override suspend fun resolveStreamUrl(track: Track, quality: AudioQuality): StreamResult =
        withContext(Dispatchers.IO) {
            val tid = track.id.removePrefix("$ID_PREFIX-")
            val n = instances.size
            var lastError = "nieznany błąd"
            for (i in 0 until n) {
                val base = instances[(instanceIndex + i) % n]
                val url = "$base/track/?id=$tid&quality=${quality.tier}"
                try {
                    val resp = get(url)
                    if (resp.code in 200..299) {
                        // odpowiedź może być: JSON z adresem, sam adres w ciele, albo redirect na CDN
                        val fromJson = TrackParser.firstHttpUrl(resp.body)
                        val fromBody = resp.body.trim().takeIf { it.startsWith("http") }
                        val finalUrl = fromJson ?: fromBody ?: (if (resp.finalUrl != url) resp.finalUrl else null)
                        if (finalUrl != null) {
                            instanceIndex = i
                            ApiLog.record(displayName, "stream", url, resp.code, finalUrl.take(140), ok = true)
                            return@withContext StreamResult.Success(finalUrl)
                        }
                        lastError = "brak adresu strumienia w odpowiedzi"
                        ApiLog.record(displayName, "stream", url, resp.code, resp.body.take(200), ok = false)
                    } else {
                        lastError = "HTTP ${resp.code}"
                        ApiLog.record(displayName, "stream", url, resp.code, resp.body.take(200), ok = false)
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: "błąd sieci"
                    ApiLog.record(displayName, "stream", url, -1, lastError, ok = false)
                }
            }
            StreamResult.Error("Monochrome: $lastError")
        }

    override suspend fun testConnection(): SourceStatus = withContext(Dispatchers.IO) {
        val tracks = searchTracks("a", 1)
        val base = instances[instanceIndex].removePrefix("https://")
        if (tracks.isNotEmpty()) {
            SourceStatus(true, base, "OK — wyszukiwarka odpowiada")
        } else {
            SourceStatus(false, base, "Brak odpowiedzi z żadnej instancji (sprawdź Diagnostykę)")
        }
    }

    /** URL okładki — źródło: /cover/?id= lub okładka z wyszukiwarki. */
    suspend fun coverUrlFor(track: Track): String? = withContext(Dispatchers.IO) {
        if (!track.coverUrl.isNullOrBlank()) return@withContext track.coverUrl
        val tid = track.id.removePrefix("$ID_PREFIX-")
        val base = instances[instanceIndex]
        val url = "$base/cover/?id=$tid"
        try {
            val resp = get(url)
            if (resp.code in 200..299) {
                val fromJson = TrackParser.firstHttpUrl(resp.body)
                val fromBody = resp.body.trim().takeIf { it.startsWith("http") }
                val finalUrl = fromJson ?: fromBody ?: (if (resp.finalUrl != url) resp.finalUrl else null)
                if (finalUrl != null) {
                    ApiLog.record(displayName, "cover", url, resp.code, finalUrl.take(140), ok = true)
                    return@withContext finalUrl
                }
            }
        } catch (e: Exception) {
            ApiLog.record(displayName, "cover", url, -1, e.message ?: "błąd sieci", ok = false)
        }
        null
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
        const val ID_PREFIX = "mono"

        val DEFAULT_INSTANCES: List<String> = listOf(
            "https://api.monochrome.tf",
            "https://eu-central.monochrome.tf",
            "https://eu-central-2.monochrome.tf",
            "https://frankfurt-2.monochrome.tf",
            "https://tidal-proxy.monochrome.tf",
        )
    }
}
