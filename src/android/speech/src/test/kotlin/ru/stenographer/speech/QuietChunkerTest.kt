package ru.stenographer.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietChunkerTest {
    @Test fun `короткая запись остаётся одним куском`() {
        assertEquals(listOf(0L..5_000L), QuietChunker.plan(FloatArray(SAMPLE_RATE * 5) { 0.1f }))
    }

    @Test fun `разрез попадает в тишину`() {
        val audio = FloatArray(SAMPLE_RATE * 30) { 0.5f }
        for (i in SAMPLE_RATE * 15 until SAMPLE_RATE * 15 + SAMPLE_RATE / 5) audio[i] = 0f
        val chunks = QuietChunker.plan(audio)
        assertEquals(2, chunks.size)
        assertTrue(chunks[0].last in 15_000L..15_200L)
        assertEquals(chunks[0].last, chunks[1].first)
        assertEquals(30_000L, chunks[1].last)
    }

    @Test fun `куски покрывают всю длинную запись и не превышают предел`() {
        val chunks = QuietChunker.plan(FloatArray(SAMPLE_RATE * 125) { 0.2f })
        assertEquals(0L, chunks.first().first)
        assertEquals(125_000L, chunks.last().last)
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.last, b.first) }
        assertTrue(chunks.all { it.last - it.first <= 20_000 })
    }
}
