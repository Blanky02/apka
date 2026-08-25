package dev.blanky.vinyl.data.source

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ApiEntry(
    val time: String,
    val source: String,
    val op: String,
    val url: String,
    val code: Int,
    val summary: String,
    val ok: Boolean,
)

/**
 * Prosty, w-pamięci dziennik wywołań API — pokazywany w Ustawieniach → Diagnostyka.
 * Bardzo pomaga debugować niedokumentowane API (np. Octave) na telefonie.
 */
object ApiLog {

    private val lock = Any()
    private val entries = ArrayDeque<ApiEntry>()
    private const val MAX = 200
    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    fun record(source: String, op: String, url: String, code: Int, summary: String, ok: Boolean) {
        val time = timeFormat.format(Instant.now())
        synchronized(lock) {
            entries.addFirst(ApiEntry(time, source, op, url, code, summary, ok))
            while (entries.size > MAX) entries.removeLast()
        }
    }

    fun snapshot(): List<ApiEntry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }
}
