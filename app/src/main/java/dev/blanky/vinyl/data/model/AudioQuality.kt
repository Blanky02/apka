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

    companion object {
        val DEFAULT: AudioQuality = HI_RES_LOSSLESS

        /** Lenient: akceptuje np. "hi_res", "Hi-Res", "LOSSLESS". */
        fun fromName(name: String?): AudioQuality {
            val normalized = name?.trim()?.uppercase()?.replace("-", "_")
            return entries.firstOrNull { it.name == normalized } ?: DEFAULT
        }
    }
}
