package dev.blanky.vinyl.player

/**
 * Czyste operacje na kolejce odtwarzania — wyłączone z Androida, żeby dało
 * się je testować na JVM.
 */
object QueueOps {

    /**
     * Po usunięciu elementu o indeksie [removed] z listy [sizeBefore] elementów
     * (gdzie aktualny utwór był na [current]), nowy indeks aktualnego utworu.
     */
    fun currentIndexAfterRemove(current: Int, removed: Int, sizeBefore: Int): Int {
        val newSize = (sizeBefore - 1).coerceAtLeast(0)
        val next = if (current >= sizeBefore) newSize - 1 else current.coerceIn(0, (newSize - 1).coerceAtLeast(0))
        if (removed < current) {
            return (next - 1).coerceAtLeast(-1)
        }
        return next.coerceAtLeast(-1)
    }

    /** Indeks wstawienia „zagraj następny” względem bieżącego. */
    fun insertIndexForPlayNext(current: Int, size: Int): Int =
        (current + 1).coerceAtMost(size)

    /** Index, od którego wznawiamy odtwarzanie po wyczyszczeniu części kolejki. */
    fun resumeIndex(current: Int, size: Int): Int = current.coerceIn(0, (size - 1).coerceAtLeast(0))
}
