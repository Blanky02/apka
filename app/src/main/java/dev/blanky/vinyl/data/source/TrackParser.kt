package dev.blanky.vinyl.data.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import dev.blanky.vinyl.data.model.Track

/**
 * Lenient parser odpowiedzi API.
 *
 * Monochrome i Octave nie mają gwarantowanego, udokumentowanego kształtu JSON
 * (Octave w ogóle nie udostępnia dokumentacji), więc parser celowo próbuje
 * wiele typowych wariantów: array u rootu, obiekty z kluczami
 * "tracks"/"items"/"results"/"data"/"songs"/"entries", artyści jako array
 * obiektów, array stringów lub pojedynczy string.
 *
 * Plik celowo nie używa żadnych klas Androida — da się testować na JVM.
 */
object TrackParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parsuje odpowiedź wyszukiwarki do listy utworów. null = "to nie wygląda na listę utworów". */
    fun parseTracks(raw: String, sourceId: String, idPrefix: String): List<Track>? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val array = root.arrayOfTracks() ?: return null
        val tracks = array.mapNotNull { element ->
            runCatching { element.toTrack(sourceId, idPrefix) }.getOrNull()
        }
        return if (tracks.isEmpty()) null else tracks
    }

    /** Znajduje pierwszy string w JSON, który wygląda na adres URL (np. manifest strumienia). */
    fun firstHttpUrl(raw: String): String? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        return firstHttpUrlIn(root)
    }

    /**
     * Wyciąga adres manifestu z odpowiedzi `/trackManifests/` (nowszy format Monochrome).
     * Adres siedzi w `attributes.uri`, zwykle jako `data.data.attributes.uri` albo
     * `data.attributes.uri`. Manifest to DASH (`.mpd`) albo HLS (`.m3u8`) — ExoPlayer
     * odtworzy go bezpośrednio.
     */
    fun extractManifestUri(raw: String): String? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        return findManifestUri(root)
    }

    private fun findManifestUri(element: JsonElement): String? {
        when (element) {
            is JsonObject -> {
                (element["attributes"] as? JsonObject)?.let { attrs ->
                    val uri = (attrs["uri"] as? JsonPrimitive)?.contentOrNull
                    if (uri != null && (uri.startsWith("http://") || uri.startsWith("https://"))) return uri
                }
                for (value in element.values) findManifestUri(value)?.let { return it }
            }
            is JsonArray -> for (child in element) findManifestUri(child)?.let { return it }
            is JsonPrimitive, is JsonNull -> Unit
        }
        return null
    }

    // ---- parsowanie odpowiedzi uwierzytelniania Octave ----

    /** Odpowiedź `/api/playback-token`: `{"token": ..., "expiresIn": ..., "gated": ..., "reason": ...}`. */
    data class PlaybackToken(
        val token: String?,
        val expiresInSec: Long?,
        val gated: Boolean,
        val reason: String?,
    )

    private val TOKEN_KEYS: Set<String> = setOf(
        "token", "accesstoken", "access_token", "sessiontoken", "session_token",
        "authtoken", "auth_token", "jwt", "apikey", "api_key", "accounttoken",
    )

    /** Parsuje odpowiedź `/api/playback-token`. null = „to nie wygląda na odpowiedź tokenu”. */
    fun parsePlaybackToken(raw: String): PlaybackToken? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val obj = root as? JsonObject ?: return null
        val token = obj.stringValue(TOKEN_KEYS)?.takeIf { it.isNotBlank() && it != "null" }
        val expiresIn = obj.number("expiresIn")?.toLong() ?: obj.number("expires_in")?.toLong()
        // gated może być booleanem (true) albo stringiem ("true")
        val gated = (obj["gated"] as? JsonPrimitive)?.contentOrNull.equals("true")
        val reason = obj.text("reason")
        if (token == null && !gated && reason == null) return null
        return PlaybackToken(token, expiresIn, gated, reason)
    }

    /** Czy odpowiedź wygląda jak błąd (`{"error": ...}` / `{"success": false}` / `{"detail": "Not Found"}`). */
    fun isErrorResponse(raw: String): Boolean {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return false
        return hasErrorShape(root)
    }

    /** Wyciąga token z odpowiedzi logowania (dowolny klucz z TOKEN_KEYS, rekurencyjnie). */
    fun extractAuthToken(raw: String): String? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        return findToken(root)
    }

    private fun findToken(element: JsonElement): String? {
        when (element) {
            is JsonObject -> {
                element.stringValue(TOKEN_KEYS)?.let { return it }
                for (value in element.values) findToken(value)?.let { return it }
            }
            is JsonArray -> for (child in element) findToken(child)?.let { return it }
            is JsonPrimitive, is JsonNull -> Unit
        }
        return null
    }

    private fun JsonObject.stringValue(keys: Set<String>): String? {
        for ((key, value) in entries) {
            if (key.lowercase() in keys && value is JsonPrimitive) {
                value.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
            }
        }
        return null
    }

    // ---- wyłanianie adresu strumienia z odpowiedzi /track/ ----

    /** Klucze, pod którymi API najczęściej chowa adres strumienia/manifestu. */
    private val STREAM_KEYS: Set<String> = setOf(
        "url", "streamurl", "stream", "streams",
        "audiourl", "playurl", "trackurl",
        "originaltrackurl", "directurl", "downloadurl", "download",
        "file", "fileurl", "cdnurl", "link", "href", "path", "src",
        "manifest", "manifesturl", "data", "result", "content",
    )

    /** Klucze, które praktycznie nigdy nie niosą strumienia (okładki, metadane). */
    private val NON_STREAM_KEYS: Set<String> = setOf(
        "cover", "coverurl", "coverart", "artwork", "artworkurl",
        "image", "images", "thumbnail", "thumb", "picture", "album",
        "artist", "artists", "title", "name", "id", "isrc", "duration",
        "quality", "audioquality", "maxquality", "bitrate", "samplerate",
    )

    private val URL_IN_TEXT: Regex = Regex("""https?://[^\s"'<>)]+""")

    /**
     * Wyłania adres strumienia z odpowiedzi endpointu /track/. Rozumie m.in.:
     * czysty tekst z URL-em, `{"url": ...}` i warianty kluczy, URL zaszyte
     * w manifeście (`<BaseURL>https://...</BaseURL>`), payload w Base64 oraz
     * odpowiedź z błędem (`{"error": "..."}`) -> null (klient próbuje niżej).
     */
    fun extractStreamUrl(raw: String): String? {
        val body = raw.trim()
        if (body.isEmpty()) return null

        // 1) odpowiedź to po prostu adres
        if (body.startsWith("http://") || body.startsWith("https://")) {
            return URL_IN_TEXT.find(body)?.value
        }

        // 2) JSON: typowe klucze -> Base64/manifest -> dowolny URL
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull()
        if (element != null) {
            if (hasErrorShape(element)) return null
            keyedUrl(element)?.let { return it }
            decodedUrl(element)?.let { return it }
            return firstHttpUrlIn(element)
        }

        // 3) nie-JSON (np. XML/MPD) — pierwszy adres z tekstu
        return URL_IN_TEXT.find(body)?.value
    }

    /** `{"error": "..."}` / `{"success": false}` -> true (nie ma strumienia). */
    private fun hasErrorShape(element: JsonElement): Boolean {
        val obj = element as? JsonObject ?: return false
        val errorText = obj["error"] ?: obj["errors"] ?: obj["message"] ?: obj["detail"] ?: obj["reason"]
        if (errorText is JsonPrimitive && errorText !is JsonNull) {
            val asText = errorText.contentOrNull
            if (!asText.isNullOrBlank() && asText != "null" && asText != "false" && asText != "0") return true
        }
        val success = obj["success"] ?: obj["ok"] ?: obj["status"]
        if (success is JsonPrimitive) {
            val text = success.contentOrNull.orEmpty()
            if (text == "false" || text == "0" || text.equals("error", ignoreCase = true)) return true
        }
        return false
    }

    /** Szuka URL-a pod typowymi kluczami (rekurencyjnie, z pominięciem okładek). */
    private fun keyedUrl(element: JsonElement): String? {
        when (element) {
            is JsonObject -> {
                for ((key, value) in element.entries) {
                    if (key.lowercase() in STREAM_KEYS) {
                        urlFromPrimitive(value)?.let { return it }
                    }
                }
                for ((key, value) in element.entries) {
                    if (key.lowercase() in NON_STREAM_KEYS) continue
                    if (value is JsonObject || value is JsonArray) {
                        keyedUrl(value)?.let { return it }
                    }
                }
            }
            is JsonArray -> {
                for (child in element) keyedUrl(child)?.let { return it }
            }
            is JsonPrimitive -> urlFromPrimitive(element)?.let { return it }
            is JsonNull -> Unit
        }
        return null
    }

    /** Próbuje zdekodować Base64 (manifest MPD/HLS) i znaleźć w środku adres. */
    private fun decodedUrl(element: JsonElement): String? {
        val candidates = mutableListOf<String>()
        collectStrings(element, candidates, limit = 40)
        for (candidate in candidates) {
            if (candidate.startsWith("http://") || candidate.startsWith("https://")) continue
            if (candidate.length < 16) continue
            val decoded = decodeBase64(candidate.trim()) ?: continue
            URL_IN_TEXT.find(decoded)?.let { return it.value }
        }
        return null
    }

    private fun decodeBase64(value: String): String? {
        val standard = runCatching {
            String(java.util.Base64.getDecoder().decode(value), Charsets.UTF_8)
        }.getOrNull()
        if (standard != null) return standard
        return runCatching {
            String(java.util.Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun urlFromPrimitive(value: JsonElement): String? {
        val primitive = value as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        val text = primitive.contentOrNull ?: return null
        return when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            else -> URL_IN_TEXT.find(text)?.value
        }
    }

    private fun collectStrings(element: JsonElement, out: MutableList<String>, limit: Int) {
        if (out.size >= limit) return
        when (element) {
            is JsonPrimitive -> if (element.isString) element.contentOrNull?.let(out::add)
            is JsonArray -> element.forEach { collectStrings(it, out, limit) }
            is JsonObject -> element.values.forEach { collectStrings(it, out, limit) }
            is JsonNull -> Unit
        }
    }

    private fun firstHttpUrlIn(element: JsonElement): String? {
        when (element) {
            is JsonPrimitive -> {
                if (element.isString) {
                    val v = element.contentOrNull
                    if (v != null && (v.startsWith("http://") || v.startsWith("https://"))) return v
                }
            }
            is JsonArray -> {
                for (child in element) {
                    val found = firstHttpUrlIn(child)
                    if (found != null) return found
                }
            }
            is JsonObject -> {
                for (child in element.values) {
                    val found = firstHttpUrlIn(child)
                    if (found != null) return found
                }
            }
            is JsonNull -> Unit
        }
        return null
    }

    private fun JsonElement.arrayOfTracks(): JsonArray? {
        // root to array?
        if (this is JsonArray) {
            // upewnijmy się, że to faktycznie utwory (pierwszy element ma tytuł albo id)
            val first = firstOrNull() as? JsonObject
            if (first == null) return null
            if (first.hasTrackShape()) return this
            return null
        }
        val obj = this as? JsonObject ?: return null
        for (key in listOf("tracks", "items", "results", "data", "songs", "entries", "matches")) {
            val value = obj[key] ?: continue
            if (value is JsonArray) {
                val first = value.firstOrNull() as? JsonObject
                if (first != null && first.hasTrackShape()) return value
            }
            if (value is JsonObject) {
                // zagnieżdżone: data.items / tracks.results / results.data ...
                for (inner in listOf("items", "tracks", "results", "data", "songs", "entries")) {
                    val innerValue = value[inner]
                    if (innerValue is JsonArray) {
                        val first = innerValue.firstOrNull() as? JsonObject
                        if (first != null && first.hasTrackShape()) return innerValue
                    }
                }
            }
        }
        return null
    }

    private fun JsonObject.hasTrackShape(): Boolean =
        (text("title") != null || text("name") != null) &&
            (stringId() != null || text("isrc") != null || text("type")?.lowercase().orEmpty().contains("track"))

    private fun JsonElement.toTrack(sourceId: String, idPrefix: String): Track? {
        val obj = this as? JsonObject ?: return null
        val id = obj.stringId() ?: return null
        val title = obj.text("title") ?: obj.text("name") ?: return null
        val artists = parseArtists(obj)
        val albumObj = obj["album"] as? JsonObject
        val album = obj.text("album")
            ?: albumObj?.text("name")
            ?: albumObj?.text("title")
        val durationSec = obj.number("duration")
            ?: obj.number("durationSec")
            ?: obj.number("durationInSec")
            ?: obj.number("durationMs")?.div(1000.0)
        val durationMs = durationSec?.toLong()?.times(1000)
        val cover = obj.text("cover")
            ?: obj.text("coverUrl")
            ?: obj.text("cover_url")
            ?: obj.text("image")
            ?: obj.text("artwork")
            ?: ((obj["cover"] as? JsonPrimitive)?.stringHttpUrl())
            ?: ((obj["artwork"] as? JsonObject)?.text("url"))
            // Octave/Deezer chowa okładkę w album.cover_* (gotowe URL-e)
            ?: albumObj?.text("cover_xl")
            ?: albumObj?.text("cover_big")
            ?: albumObj?.text("cover_medium")
            ?: albumObj?.text("cover_small")
        val quality = obj.text("audioQuality")
            ?: obj.text("quality")
            ?: obj.text("maxQuality")
        return Track(
            id = "$idPrefix-$id",
            sourceId = sourceId,
            title = title,
            artists = artists.ifEmpty { listOf("Nieznany artysta") },
            album = album?.takeIf { it.isNotBlank() },
            durationMs = durationMs,
            coverUrl = cover,
            maxQuality = quality,
        )
    }

    private fun parseArtists(obj: JsonObject): List<String> {
        val raw = obj["artists"] ?: obj["artist"] ?: return emptyList()
        return when (raw) {
            is JsonArray -> raw.mapNotNull { el ->
                when (el) {
                    is JsonObject -> el.text("name")
                    is JsonPrimitive -> el.stringHttpUrl() ?: el.asText()
                    else -> null
                }
            }
            is JsonObject -> listOfNotNull(raw.text("name"))
            is JsonPrimitive -> listOfNotNull(raw.asText())
            else -> emptyList()
        }
    }

    // ---- małe pomocnicze rozszerzenia ----

    private fun JsonObject.stringId(): String? {
        for (key in listOf("id", "tid", "trackId", "track_id", "uid")) {
            val v = this[key] ?: continue
            if (v is JsonPrimitive && v !is JsonNull) {
                val s = v.contentOrNull
                if (!s.isNullOrBlank() && s != "null") return s
            }
        }
        return null
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.asText()

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonPrimitive.asText(): String? =
        if (isString) content?.takeIf { it.isNotBlank() && it != "null" } else null

    private fun JsonPrimitive.stringHttpUrl(): String? {
        if (!isString) return null
        val v = content ?: return null
        return if (v.startsWith("http://") || v.startsWith("https://")) v else null
    }
}
