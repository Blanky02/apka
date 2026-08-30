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
 *   GET /trackManifests/?id={tid}&quality={...}&adaptive=false&formats={...}  (nowszy: manifest DASH/HLS)
 *   GET /track/?id={tid}&quality={LOW|HIGH|LOSSLESS|HI_RES|HI_RES_LOSSLESS}   (starszy: bezpośredni adres)
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
                        instanceIndex = (instanceIndex + i) % n
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

    /**
     * Wyłania adres strumienia. Próbuje od żądanej jakości w dół
     * (np. HI_RES_LOSSLESS -> HI_RES -> LOSSLESS -> HIGH -> LOW), bo API nie
     * zawsze samo obniża jakość — bez tego wybranie utworu kończyło się
     * komunikatem „żaden utwór nie jest dostępny w wybranej jakości”.
     *
     * Jeśli wyszukiwarka podała maksymalną jakość utworu (maxQuality), nie
     * pytamy o wyższą, tylko schodzimy od razu do tej, którą utwór ma.
     *
     * Monochrome migruje strumieniowanie ze starego `/track/` na nowsze
     * `/trackManifests/` (manifest DASH/HLS), więc najpierw próbujemy nowego
     * endpointu, a stary zostaje jako fallback dla starszych instancji.
     */
    override suspend fun resolveStreamUrl(track: Track, quality: AudioQuality): StreamResult =
        withContext(Dispatchers.IO) {
            val tid = track.id.removePrefix("$ID_PREFIX-")
            val n = instances.size
            val startTier = track.maxQuality
                ?.let { AudioQuality.fromName(it) }
                ?.let { AudioQuality.lowerOf(quality, it) }
                ?: quality
            var lastError = "nieznany błąd"

            for (tier in startTier.fallbackChain()) {
                for (i in 0 until n) {
                    val base = instances[(instanceIndex + i) % n]

                    // 1) nowszy format: /trackManifests/ zwraca manifest DASH/HLS
                    val (manifestQuality, manifestFormat) = manifestFormatFor(tier)
                    val manifestUrl = "$base/trackManifests/?id=$tid&quality=$manifestQuality&adaptive=false&formats=$manifestFormat"
                    try {
                        val resp = get(manifestUrl)
                        if (resp.code in 200..299) {
                            val stream = TrackParser.extractManifestUri(resp.body)
                            if (stream != null) {
                                instanceIndex = (instanceIndex + i) % n
                                ApiLog.record(displayName, "stream/${tier.tier}", manifestUrl, resp.code, stream.take(140), ok = true)
                                return@withContext StreamResult.Success(stream, tier.tier)
                            }
                            lastError = "brak manifestu (${resp.body.take(120)})"
                            ApiLog.record(displayName, "stream/${tier.tier}", manifestUrl, resp.code, lastError, ok = false)
                        } else {
                            lastError = "HTTP ${resp.code}: ${resp.body.take(120)}"
                            ApiLog.record(displayName, "stream/${tier.tier}", manifestUrl, resp.code, resp.body.take(160), ok = false)
                        }
                    } catch (e: Exception) {
                        lastError = e.message ?: "błąd sieci"
                        ApiLog.record(displayName, "stream/${tier.tier}", manifestUrl, -1, lastError, ok = false)
                    }

                    // 2) starszy format: /track/ — bezpośredni adres albo redirect na CDN
                    val legacyUrl = "$base/track/?id=$tid&quality=${tier.tier}"
                    try {
                        val resp = get(legacyUrl)
                        if (resp.code in 200..299) {
                            val stream = TrackParser.extractStreamUrl(resp.body)
                                ?: (if (resp.finalUrl != legacyUrl) resp.finalUrl else null)
                            if (stream != null) {
                                instanceIndex = (instanceIndex + i) % n
                                ApiLog.record(displayName, "stream/${tier.tier}", legacyUrl, resp.code, stream.take(140), ok = true)
                                return@withContext StreamResult.Success(stream, tier.tier)
                            }
                            lastError = "brak adresu strumienia (${resp.body.take(120)})"
                            ApiLog.record(displayName, "stream/${tier.tier}", legacyUrl, resp.code, lastError, ok = false)
                        } else {
                            lastError = "HTTP ${resp.code}: ${resp.body.take(120)}"
                            ApiLog.record(displayName, "stream/${tier.tier}", legacyUrl, resp.code, resp.body.take(160), ok = false)
                        }
                    } catch (e: Exception) {
                        lastError = e.message ?: "błąd sieci"
                        ApiLog.record(displayName, "stream/${tier.tier}", legacyUrl, -1, lastError, ok = false)
                    }
                }
            }
            StreamResult.Error("Monochrome: $lastError")
        }

    /**
     * Parametry `/trackManifests/` dla tieru: (quality, formats).
     * Mapowanie zgodne z upstream (getTrackManifestFormats):
     * LOW->HEAACV1, HIGH->AACLC, LOSSLESS->FLAC, HI_RES(_LOSSLESS)->FLAC_HIRES.
     */
    private fun manifestFormatFor(tier: AudioQuality): Pair<String, String> = when (tier) {
        AudioQuality.LOW -> "LOW" to "HEAACV1"
        AudioQuality.HIGH -> "HIGH" to "AACLC"
        AudioQuality.LOSSLESS -> "LOSSLESS" to "FLAC"
        AudioQuality.HI_RES, AudioQuality.HI_RES_LOSSLESS -> "HI_RES_LOSSLESS" to "FLAC_HIRES"
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

        /**
         * Aktualna lista instancji. Upstream Monochrome odszedł od starych
         * hostów hifi-api (eu-central/us-west/arran.monochrome.tf, *.qqdl.site,
         * triton.squid.wtf, tidal.kinoplus.online — wszystkie obecnie martwe:
         * 503/520/530/DNS) i kieruje ruch przez load-balancer
         * `lol.samidy.workers.dev` (hifi-api v2.10). Zweryfikowane na żywo:
         * search + /trackManifests/ (podpisany .mpd) + /track/ (base64 MPD)
         * odpowiadają 200.
         *
         * `monochrome-api.samidy.com` zostaje jako zapasowa wyszukiwarka
         * (search działa; strumień potrafi zwrócić 403 „Upstream API error”).
         */
        val DEFAULT_INSTANCES: List<String> = listOf(
            "https://lol.samidy.workers.dev",
            "https://monochrome-api.samidy.com",
        )
    }
}
