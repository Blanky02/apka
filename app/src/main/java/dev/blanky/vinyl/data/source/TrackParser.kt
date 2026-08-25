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
        val album = obj.text("album")
            ?: (obj["album"] as? JsonObject)?.text("name")
            ?: (obj["album"] as? JsonObject)?.text("title")
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
