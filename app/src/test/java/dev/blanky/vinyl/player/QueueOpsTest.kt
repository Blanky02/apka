package dev.blanky.vinyl.player

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueOpsTest {

    @Test
    fun `remove before current shifts index down`() {
        // [A, B, C, D], current=2 (C), usuwamy 0 (A) -> current=1 (C)
        assertEquals(1, QueueOps.currentIndexAfterRemove(current = 2, removed = 0, sizeBefore = 4))
    }

    @Test
    fun `remove after current keeps index`() {
        // [A, B, C, D], current=1 (B), usuwamy 3 (D) -> current=1 (B)
        assertEquals(1, QueueOps.currentIndexAfterRemove(current = 1, removed = 3, sizeBefore = 4))
    }

    @Test
    fun `remove current clamps to bounds`() {
        // [A, B], current=1 (B), usuwamy 1 -> zostaje [A]
        val next = QueueOps.currentIndexAfterRemove(current = 1, removed = 1, sizeBefore = 2)
        assertEquals(0, next)
    }

    @Test
    fun `play next inserts after current`() {
        assertEquals(2, QueueOps.insertIndexForPlayNext(current = 1, size = 4))
        assertEquals(4, QueueOps.insertIndexForPlayNext(current = 3, size = 4))
        assertEquals(0, QueueOps.insertIndexForPlayNext(current = -1, size = 3))
    }

    @Test
    fun `resume index clamps`() {
        assertEquals(0, QueueOps.resumeIndex(current = -1, size = 5))
        assertEquals(4, QueueOps.resumeIndex(current = 99, size = 5))
        assertEquals(2, QueueOps.resumeIndex(current = 2, size = 5))
    }
}
