package dev.blanky.vinyl.data.model

/**
 * Utwór z dowolnego źródła. `id` jest globalnie unikalne: `{prefix}-{sourceTrackId}`,
 * np. "mono-123456" albo "oct-789".
 */
data class Track(
    val id: String,
    val sourceId: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long?,
    val coverUrl: String?,
    val maxQuality: String?,
) {
    val artistText: String
        get() = artists.joinToString(", ").ifBlank { "Nieznany artysta" }

    /** Klucz do deduplikacji wyników z wielu źródeł. */
    fun dedupeKey(): String =
        "$title|${artists.joinToString("").lowercase()}"

    companion object {
        fun formatDuration(ms: Long?): String {
            if (ms == null || ms < 0) return "--:--"
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) {
                "%d:%02d:%02d".format(h, m, s)
            } else {
                "%d:%02d".format(m, s)
            }
        }

        /** Krótka etykieta jakości, np. "24/192" dla HI_RES_LOSSLESS. */
        fun qualityBadge(quality: String?): String {
            return when (quality?.uppercase()?.replace("-", "_")) {
                "HI_RES_LOSSLESS" -> "24/192"
                "HI_RES" -> "Hi-Res"
                "LOSSLESS" -> "Lossless"
                "HIGH" -> "320k"
                "LOW" -> "128k"
                else -> quality?.ifBlank { null }
            }
        }
    }
}
