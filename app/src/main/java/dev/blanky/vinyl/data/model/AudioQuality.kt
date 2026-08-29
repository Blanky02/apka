package dev.blanky.vinyl.data.model

/**
 * Poziomy jakości strumienia. `tier` to nazwa rozumiana przez API Monochrome
 * (LOW / HIGH / LOSSLESS / HI_RES / HI_RES_LOSSLESS).
 */
enum class AudioQuality(val label: String, val detail: String, val tier: String) {
    LOW("Niska", "ok. 128–160 kbps", "LOW"),
    HIGH("Wysoka", "ok. 320 kbps", "HIGH"),
    LOSSLESS("Lossless", "CD / FLAC 16-bit", "LOSSLESS"),
    HI_RES("Hi-Res", "do 24-bit / 96 kHz", "HI_RES"),
    HI_RES_LOSSLESS("Hi-Res Lossless", "do 24-bit / 192 kHz", "HI_RES_LOSSLESS");

    /**
     * Łańcuch jakości do automatycznego obniżania: od tej jakości w dół,
     * np. HI_RES -> [HI_RES, LOSSLESS, HIGH, LOW].
     *
     * API nie zawsze samo downgrade'uje, a dla najwyższych tierów często w ogóle
     * nie ma strumienia — bez tego wybranie utworu kończyło się komunikatem
     * „żaden utwór nie jest dostępny w wybranej jakości”.
     */
    fun fallbackChain(): List<AudioQuality> = (ordinal downTo 0).map { AudioQuality.entries[it] }

    companion object {
        val DEFAULT: AudioQuality = HI_RES_LOSSLESS

        /** Niższa z dwóch jakości (do ograniczania żądania tym, co potrafi utwór). */
        fun lowerOf(a: AudioQuality, b: AudioQuality): AudioQuality =
            if (a.ordinal <= b.ordinal) a else b

        /** Lenient: akceptuje np. "hi_res", "Hi-Res", "LOSSLESS". */
        fun fromName(name: String?): AudioQuality {
            val normalized = name?.trim()?.uppercase()?.replace("-", "_")
            return entries.firstOrNull { it.name == normalized } ?: DEFAULT
        }
    }
}
