package dev.blanky.vinyl.data.source

import android.app.Application
import dev.blanky.vinyl.data.model.AudioQuality
import dev.blanky.vinyl.data.model.Track
import dev.blanky.vinyl.data.settings.VinylSettings
import dev.blanky.vinyl.data.source.monochrome.MonochromeSource
import dev.blanky.vinyl.data.source.octave.OctaveSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient

/**
 * Łączy wszystkie źródła: wyszukiwanie (merge wyników, deduplikacja po
 * tytuł+artyści) i wyłanianie strumieni dla poszczególnych utworów.
 */
class SourceManager(
    private val app: Application,
    private val settings: VinylSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val monochrome: MonochromeSource = MonochromeSource(http)
    val octave: OctaveSource = OctaveSource(http, settings)

    private val _status = MutableStateFlow(
        mapOf(
            monochrome.id to SourceStatus(available = true, activeInstance = "auto", detail = "nie testowano"),
            octave.id to SourceStatus(available = true, activeInstance = null, detail = "nie testowano"),
        )
    )
    val status: StateFlow<Map<String, SourceStatus>> = _status

    private val sourceById: Map<String, MusicSource> = mapOf(
        monochrome.id to monochrome,
        octave.id to octave,
    )

    fun sourceFor(track: Track): MusicSource =
        sourceById[track.sourceId] ?: monochrome

    /**
     * Wyszukuje w Monochrome + (jeśli włączone) Octave równolegle i scala wyniki.
     * Kolejność: najpierw wyniki Monochrome (stabilne, Hi-Res), potem Octave.
     */
    suspend fun search(query: String, limit: Int = 30): List<Track> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        val results = ConcurrentHashMap<String, List<Track>>()
        val jobs = mutableListOf(
            scope.async {
                results["monochrome"] = runCatching { monochrome.searchTracks(q, limit) }.getOrDefault(emptyList())
            },
        )
        if (settings.octaveEnabled.first()) {
            jobs += scope.async {
                results["octave"] = runCatching { octave.searchTracks(q, limit) }.getOrDefault(emptyList())
            }
        }
        jobs.forEach { it.await() }

        val merged = mutableListOf<Track>()
        val seen = HashSet<String>()
        for (sourceId in listOf(monochrome.id, octave.id)) {
            for (track in results[sourceId].orEmpty()) {
                if (seen.add(track.dedupeKey())) merged.add(track)
            }
        }
        return merged
    }

    suspend fun resolveStream(track: Track, quality: AudioQuality): StreamResult =
        sourceFor(track).resolveStreamUrl(track, quality)

    suspend fun testSource(sourceId: String): SourceStatus {
        val source = sourceById[sourceId] ?: return SourceStatus(false, null, "nieznane źródło")
        val result = runCatching { source.testConnection() }.getOrDefault(
            SourceStatus(false, null, "błąd testu")
        )
        _status.update { map -> map + (sourceId to result) }
        return result
    }

    fun markSourceStatus(sourceId: String, status: SourceStatus) {
        _status.update { map -> map + (sourceId to status) }
    }
}
