package ru.stenographer.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    private fun sine(hz: Double, rate: Int, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun run(input: FloatArray, from: Int, to: Int, block: Int = 4096): FloatArray {
        val resampler = Resampler(from, to)
        val out = FloatBuffer()
        var i = 0
        while (i < input.size) {
            val part = input.copyOfRange(i, minOf(i + block, input.size))
            out.add(resampler.process(part))
            i += part.size
        }
        out.add(resampler.finish())
        return out.toArray()
    }

    private fun rms(a: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += a[i] * a[i].toDouble()
        return sqrt(sum / (to - from))
    }

    @Test fun `длина соответствует длительности`() {
        for (rate in listOf(8_000, 11_025, 22_050, 44_100, 48_000)) {
            val input = sine(440.0, rate, 1.37)
            assertEquals("rate=$rate", ceil(input.size * 16_000.0 / rate).toInt(), run(input, rate, 16_000).size)
        }
    }

    @Test fun `речевая частота сохраняется`() {
        val out = run(sine(1_000.0, 44_100, 1.0), 44_100, 16_000)
        assertEquals(0.5 / sqrt(2.0), rms(out, 1_000, out.size - 1_000), 0.005)
    }

    @Test fun `нет сдвига по времени`() {
        val out = run(sine(1_000.0, 48_000, 1.0), 48_000, 16_000)
        val expected = FloatArray(out.size) { (0.5 * sin(2 * PI * 1_000 * it / 16_000)).toFloat() }
        for (i in 500 until out.size - 500) assertTrue("i=$i", abs(out[i] - expected[i]) < 0.01f)
    }

    @Test fun `частоты выше 8 кГц не заворачиваются`() {
        val out = run(sine(12_000.0, 44_100, 1.0), 44_100, 16_000)
        assertTrue(rms(out, 1_000, out.size - 1_000) < 0.002)
    }

    @Test fun `результат не зависит от размера блоков`() {
        val input = sine(700.0, 44_100, 0.5)
        val a = run(input, 44_100, 16_000, 1_000)
        val b = run(input, 44_100, 16_000, 7_777)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 1e-5f)
    }

    @Test fun `та же частота не меняет звук`() {
        val input = sine(300.0, 16_000, 0.2)
        assertTrue(input.contentEquals(run(input, 16_000, 16_000)))
    }

    @Test fun `стерео сводится средним`() {
        assertTrue(floatArrayOf(0.5f, 0f).contentEquals(downmix(floatArrayOf(1f, 0f, -1f, 1f), 4, 2)))
    }
}
