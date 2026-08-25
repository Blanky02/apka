package dev.blanky.vinyl.data.source

import dev.blanky.vinyl.data.model.AudioQuality
import dev.blanky.vinyl.data.model.Track

/** Wynik próby wyłonienia adresu strumienia. */
sealed interface StreamResult {
    data class Success(val url: String, val servedQuality: String? = null) : StreamResult
    data class Error(val message: String) : StreamResult
}

/** Status źródła po teście połączenia. */
data class SourceStatus(
    val available: Boolean,
    val activeInstance: String?,
    val detail: String,
)

/**
 * Abstrakcja źródła muzyki. Vinyl może łączyć wyniki z wielu źródeł;
 * każda ścieżka należy do dokładnie jednego źródła (`track.sourceId`).
 */
interface MusicSource {
    val id: String
    val displayName: String

    /**
     * Wyszukuje utwory. Zwraca pustą listę (nie rzuci wyjątku), gdy źródło
     * jest niedostępne — błędy lądują w logu diagnostycznym.
     */
    suspend fun searchTracks(query: String, limit: Int): List<Track>

    /** Wyłania adres strumienia do odtworzenia w żądanej jakości (dopuszczalne downgrade'u). */
    suspend fun resolveStreamUrl(track: Track, quality: AudioQuality): StreamResult

    /** Krótki test: czy źródło odpowiada? */
    suspend fun testConnection(): SourceStatus
}
