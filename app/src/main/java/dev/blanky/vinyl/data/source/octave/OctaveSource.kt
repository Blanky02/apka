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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
 *   GET /api/playback-token            -> {"token": ..., "expiresIn": ..., "gated": ..., "reason": ...}
 *
 * Strumienie są "gated": `GET /api/playback-token` dla klientów bez konta na
 * części sieci zwraca token ({"token":"octk_…","expiresIn":1800}), a na innych
 * `{"gated":true,"reason":"no-account"}`. Octave NIE ma działającego endpointu
 * logowania (wszystkie /api/account/ odpowiadają 404) — dlatego token pobieramy po prostu
 * (także anonimowo, bez klucza) i jeśli przyszedł, gramy pełne strumienie
 * (`/audio/{quality}?track={id}&token={...}`). Klucz konta jest opcjonalny:
 * próby logowania kończą się zwykle 404, ale niczego nie psują.
 */
class OctaveSource(
    private val http: OkHttpClient,
    private val settings: VinylSettings,
) : MusicSource {

    override val id: String = "octave"
    override val displayName: String = "Octave"

    private val userAgent = "Vinyl/0.1 (Android)"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _auth = MutableStateFlow(OctaveAuthState())
    val auth: StateFlow<OctaveAuthState> = _auth

    private var playbackToken: String? = null
    private var playbackTokenExpiresAt: Long = 0L // epoch ms

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

            // playback-token (bez klucza też) -> bez niego utwory gated grają tylko preview
            val key = settings.octaveKey.first().trim()
            if (!tokenFresh()) {
                fetchPlaybackToken(key)
            }

            try {
                val resp = get(url, authHeaders())
                if (resp.code in 200..299) {
                    val info = parseStreamResponse(resp.body)
                    val directBody = resp.body.trim().takeIf { it.startsWith("http") }
                    var finalUrl = info?.url ?: directBody ?: (if (resp.finalUrl != url) resp.finalUrl else null)
                    var isPreview = false

                    // pełny strumień wymaga tokenu (gated) -> url z odpowiedzi + token, ewentualnie preview
                    if (info != null && info.gated) {
                        finalUrl = when {
                            tokenFresh() -> {
                                val streamBase = info.url
                                    ?: "$base/audio/${audioQualityParam(quality)}?track=$tid"
                                val sep = if (streamBase.contains("?")) "&" else "?"
                                streamBase + sep + "token=" + Uri.encode(playbackToken.orEmpty())
                            }
                            info.preview != null -> {
                                isPreview = true
                                info.preview
                            }
                            else -> null
                        }
                    }

                    if (finalUrl != null) {
                        val summary = finalUrl.take(140) +
                            if (isPreview) " (preview 30 s — pełny strumień wymaga konta)" else ""
                        ApiLog.record(displayName, "stream", url, resp.code, summary, ok = true)
                        return@withContext StreamResult.Success(finalUrl, quality.tier)
                    }
                    ApiLog.record(displayName, "stream", url, resp.code, "brak URL: ${resp.body.take(200)}", ok = false)
                } else {
                    ApiLog.record(displayName, "stream", url, resp.code, resp.body.take(200), ok = false)
                }
            } catch (e: Exception) {
                ApiLog.record(displayName, "stream", url, -1, e.message ?: "błąd sieci", ok = false)
            }
            StreamResult.Error("Octave: nie udało się pobrać strumienia — sprawdź szablony i konto w Ustawieniach")
        }

    /**
     * Logowanie kluczem konta. Kolejność:
     *  1) próba endpointów logowania (z listy lub własnego szablonu),
     *  2) fallback: klucz jako nagłówek przy `/api/playback-token`.
     * Sukces = serwer zwrócił token (albo odpowiedź bez błędu). Wszystko w logu.
     */
    suspend fun loginWithKey(key: String): OctaveLoginResult = withContext(Dispatchers.IO) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            return@withContext OctaveLoginResult.Failure("Klucz jest pusty")
        }
        settings.setOctaveKey(trimmed)
        _auth.update { it.copy(hasKey = true, busy = true, detail = "Logowanie…") }

        val base = settings.octaveBase.first().trimEnd('/')
        val customPath = settings.octaveLoginTemplate.first().trim()
        val candidates = if (customPath.isNotBlank()) {
            listOf(
                LoginCandidate("POST", customPath, "key", null),
                LoginCandidate("POST", customPath, "accountKey", null),
                LoginCandidate("GET", customPath, null, "key"),
            )
        } else {
            DEFAULT_LOGIN_CANDIDATES
        }

        var lastDetail = "brak odpowiedzi"
        for (candidate in candidates) {
            val attempt = tryLoginCandidate(base, candidate, trimmed)
            if (attempt.ok) {
                attempt.token?.let { storeToken(it, attempt.expiresInSec) }
                _auth.update { it.copy(loggedIn = true, busy = false, detail = "Zalogowano (${attempt.via})") }
                return@withContext OctaveLoginResult.Success("Zalogowano przez ${attempt.via}")
            }
            lastDetail = attempt.detail
        }

        // fallback: klucz jako nagłówek przy playback-token
        val pt = fetchPlaybackToken(trimmed)
        if (pt != null && pt.token != null) {
            storeToken(pt.token, pt.expiresInSec)
            _auth.update { it.copy(loggedIn = true, busy = false, detail = "Zalogowano — serwer zwrócił playback-token") }
            return@withContext OctaveLoginResult.Success("Zalogowano — serwer zwrócił playback-token")
        }

        _auth.update { it.copy(loggedIn = false, busy = false, detail = lastDetail) }
        OctaveLoginResult.Failure(lastDetail)
    }

    /** Wylogowuje i czyści zapisany klucz + token. */
    suspend fun logout() {
        settings.setOctaveKey("")
        playbackToken = null
        playbackTokenExpiresAt = 0L
        _auth.update { OctaveAuthState(hasKey = false, loggedIn = false, busy = false, detail = "Wylogowano") }
    }

    /** Jeśli klucz był zapisany (np. po restarcie), spróbuj zalogować ponownie. */
    suspend fun refreshAuthFromStoredKey() {
        val key = settings.octaveKey.first().trim()
        _auth.update { it.copy(hasKey = key.isNotEmpty()) }
        if (key.isNotEmpty() && !_auth.value.loggedIn) {
            loginWithKey(key)
        }
    }

    override suspend fun testConnection(): SourceStatus = withContext(Dispatchers.IO) {
        if (!settings.octaveEnabled.first()) {
            return@withContext SourceStatus(false, null, "Wyłączone w ustawieniach")
        }
        val base = settings.octaveBase.first().trimEnd('/')
        val tracks = searchTracks("a", 1)
        val authState = _auth.value
        val detail = buildString {
            append(if (tracks.isNotEmpty()) "OK — wyszukiwarka odpowiada" else "Brak odpowiedzi — użyj przycisku „Wykryj API”")
            if (authState.loggedIn) append(" · konto: zalogowane")
            else if (authState.hasKey) append(" · konto: niezalogowane")
        }
        SourceStatus(tracks.isNotEmpty(), base, detail)
    }

    /** Automatyczne wykrywanie endpointu wyszukiwarki: testuje typowe warianty i zapisuje pierwszy działający. */
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

    // ---- internals ----

    private data class LoginCandidate(val method: String, val path: String, val bodyKey: String?, val queryKey: String?)

    private data class LoginAttempt(
        val ok: Boolean,
        val token: String?,
        val expiresInSec: Long?,
        val via: String,
        val detail: String,
    )

    private data class StreamInfo(val url: String?, val preview: String?, val gated: Boolean)

    private data class HttpResponse(val code: Int, val body: String, val finalUrl: String)

    private suspend fun tryLoginCandidate(base: String, c: LoginCandidate, key: String): LoginAttempt {
        val path = c.path.replace("{key}", Uri.encode(key))
        val url = if (path.startsWith("http")) path else "$base$path"
        val via = "${c.method} $url"
        return try {
            val resp = when (c.method) {
                "POST" -> {
                    val bodyKey = c.bodyKey ?: "key"
                    val body = """{"$bodyKey":"${escapeJson(key)}"}"""
                    execute(Request.Builder().url(url).post(body.toRequestBody(jsonMediaType)).build())
                }
                else -> {
                    val q = if (c.queryKey != null) "?${c.queryKey}=${Uri.encode(key)}" else ""
                    execute(Request.Builder().url(url + q).get().build())
                }
            }
            if (resp.code in 200..299 && !TrackParser.isErrorResponse(resp.body)) {
                val token = TrackParser.extractAuthToken(resp.body)
                ApiLog.record(displayName, "login", url, resp.code, if (token != null) "token (${token.take(12)}…)" else "OK", ok = true)
                LoginAttempt(ok = true, token = token, expiresInSec = null, via = via, detail = "OK")
            } else {
                val detail = "HTTP ${resp.code}: ${resp.body.take(120)}"
                ApiLog.record(displayName, "login", url, resp.code, resp.body.take(160), ok = false)
                LoginAttempt(ok = false, token = null, expiresInSec = null, via = via, detail = detail)
            }
        } catch (e: Exception) {
            val detail = e.message ?: "błąd sieci"
            ApiLog.record(displayName, "login", url, -1, detail, ok = false)
            LoginAttempt(ok = false, token = null, expiresInSec = null, via = via, detail = detail)
        }
    }

    /**
     * Pobiera playback-token. Octave wydaje token także anonimowo (bez klucza),
     * więc najpierw zwykły GET; z kluczem próbujemy też kilku nagłówków.
     */
    private suspend fun fetchPlaybackToken(key: String): TrackParser.PlaybackToken? {
        val base = settings.octaveBase.first().trimEnd('/')
        val url = "$base/api/playback-token"
        val headerSets = mutableListOf<Map<String, String>>(emptyMap())
        if (key.isNotBlank()) {
            headerSets += mapOf("Authorization" to "Bearer $key")
            headerSets += mapOf("x-account-key" to key)
            headerSets += mapOf("x-octave-key" to key)
        }
        for (headers in headerSets) {
            try {
                val resp = get(url, headers)
                val parsed = if (resp.code in 200..299) TrackParser.parsePlaybackToken(resp.body) else null
                ApiLog.record(
                    displayName, "playback-token", url, resp.code,
                    resp.body.take(160),
                    ok = parsed?.token != null,
                )
                if (parsed?.token != null) return parsed
            } catch (e: Exception) {
                ApiLog.record(displayName, "playback-token", url, -1, e.message ?: "błąd sieci", ok = false)
            }
        }
        return null
    }

    private fun storeToken(token: String, expiresInSec: Long?) {
        playbackToken = token
        val ttl = expiresInSec ?: 1800L
        playbackTokenExpiresAt = System.currentTimeMillis() + ttl * 1000
    }

    private fun tokenFresh(): Boolean =
        playbackToken != null && System.currentTimeMillis() < playbackTokenExpiresAt

    private suspend fun authHeaders(): Map<String, String> {
        val key = settings.octaveKey.first().trim()
        val auth = if (tokenFresh()) playbackToken.orEmpty() else key
        if (auth.isBlank()) return emptyMap()
        return buildMap {
            put("Authorization", "Bearer $auth")
            if (key.isNotEmpty()) put("x-account-key", key)
        }
    }

    /** Mapuje jakość na segment URL-a `/audio/{quality}` (zgodnie z regexem w sw.js Octave). */
    private fun audioQualityParam(quality: AudioQuality): String = when (quality) {
        AudioQuality.LOW -> "128"
        AudioQuality.HIGH -> "320"
        AudioQuality.LOSSLESS -> "lossless"
        AudioQuality.HI_RES, AudioQuality.HI_RES_LOSSLESS -> "flac"
    }

    private fun parseStreamResponse(raw: String): StreamInfo? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val obj = root as? JsonObject ?: return null
        fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull
        return StreamInfo(
            url = str("url"),
            preview = str("preview"),
            gated = str("gated") == "true",
        )
    }

    private fun buildUrl(base: String, template: String, vars: Map<String, String>): String {
        val path = template
            .replace("{query}", vars["query"]?.let(Uri::encode).orEmpty())
            .replace("{id}", vars["id"].orEmpty())
            .replace("{limit}", vars["limit"].orEmpty())
            .replace("{quality}", vars["quality"].orEmpty())
        return if (path.startsWith("http")) path else "$base$path"
    }

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json, */*")
        headers.forEach { (k, v) -> builder.header(k, v) }
        return execute(builder.get().build())
    }

    private fun execute(request: Request): HttpResponse =
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            HttpResponse(resp.code, body, resp.request.url.toString())
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

        /** Typowe warianty endpointu logowania kluczem konta. */
        private val DEFAULT_LOGIN_CANDIDATES: List<LoginCandidate> = listOf(
            LoginCandidate("POST", "/api/account/login", "key", null),
            LoginCandidate("POST", "/api/account/login", "accountKey", null),
            LoginCandidate("POST", "/api/account/auth", "key", null),
            LoginCandidate("POST", "/api/account/session", "key", null),
            LoginCandidate("GET", "/api/account/login", null, "key"),
            LoginCandidate("GET", "/api/account/login", null, "accountKey"),
        )
    }
}

/** Stan logowania Octave (do UI). */
data class OctaveAuthState(
    val loggedIn: Boolean = false,
    val hasKey: Boolean = false,
    val busy: Boolean = false,
    val detail: String? = null,
)

sealed interface OctaveLoginResult {
    data class Success(val detail: String) : OctaveLoginResult
    data class Failure(val detail: String) : OctaveLoginResult
}
