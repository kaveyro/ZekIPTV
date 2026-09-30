package com.zekikoese

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumePositionsTest {

    @Test
    fun `updated entry moves to the end and oldest entries are dropped`() {
        val current = linkedMapOf("a" to 1L, "b" to 2L, "c" to 3L)
        val updated = updatedResumePositions(current, "a", 10L, limit = 3)
        assertEquals(listOf("b", "c", "a"), updated.keys.toList())

        val trimmed = updatedResumePositions(updated, "d", 4L, limit = 3)
        assertEquals(listOf("c", "a", "d"), trimmed.keys.toList())
    }

    @Test
    fun `zero position removes the entry`() {
        val updated = updatedResumePositions(linkedMapOf("a" to 1L, "b" to 2L), "a", 0L)
        assertEquals(mapOf("b" to 2L), updated)
    }
}
