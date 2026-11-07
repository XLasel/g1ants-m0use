package ru.stenographer.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EnvelopeTest {
    @Test fun `одно значение на 50 мс, хвост округляется вверх`() {
        assertEquals(3, loudnessEnvelope(FloatArray(800 * 2 + 1) { 0.1f }).size)
        assertEquals(0, loudnessEnvelope(FloatArray(0)).size)
    }

    @Test fun `нормировка на максимум записи`() {
        val audio = FloatArray(800 * 3)
        for (i in 0 until 800) audio[i] = if (i % 2 == 0) 0.2f else -0.2f // RMS 0,2
        for (i in 1600 until 2400) audio[i] = if (i % 2 == 0) 0.8f else -0.8f // RMS 0,8
        assertArrayEquals(floatArrayOf(0.25f, 0f, 1f), loudnessEnvelope(audio), 1e-6f)
    }

    @Test fun `тишина даёт нули без деления на ноль`() {
        assertArrayEquals(FloatArray(4), loudnessEnvelope(FloatArray(800 * 4)), 0f)
    }
}
