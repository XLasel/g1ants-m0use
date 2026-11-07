package ru.stenographer.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class StitchTest {

    /** Речь заменяем смесью тонов и шума: важно только, чтобы сигнал был непохож сам на себя со сдвигом. */
    private fun signal(rate: Int, seconds: Double): FloatArray {
        var seed = 12345L
        return FloatArray((rate * seconds).toInt()) { i ->
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val noise = ((seed ushr 40).toInt() / 16777216f - 0.5f) * 0.2f
            val t = i.toDouble() / rate
            (0.4 * sin(2 * PI * 220 * t) + 0.3 * sin(2 * PI * 1375.5 * t)).toFloat() + noise
        }
    }

    /** Подаёт отрезок блоками разной длины через общий буфер, как это делает кодек. */
    private fun resample(x: FloatArray, from: Int, to: Int, rate: Int, label: Long = from.toLong()): ResampledPart {
        val part = PartResampler(rate, label)
        val scratch = FloatArray(4096)
        var i = from
        var block = 0
        while (i < to) {
            val n = minOf(500 + (block++ * 613) % 3000, to - i)
            x.copyInto(scratch, 0, i, i + n)
            part.push(scratch, n)
            i += n
        }
        return part.finish()
    }

    private fun parallel(x: FloatArray, rate: Int, starts: List<Int>, preroll: Int, postroll: Int, shift: Int = 0): FloatArray {
        val parts = starts.indices.map { k ->
            val from = if (k == 0) 0 else starts[k] - preroll
            val to = if (k == starts.lastIndex) x.size else starts[k + 1] + postroll
            // shift имитирует кодек, который неверно сообщил время начала куска
            resample(x, from, to, rate, label = from.toLong() + if (k == 0) 0 else shift)
        }
        return stitchParts(parts, starts.drop(1).map { cutToOutput(it.toLong(), rate) })
    }

    @Test
    fun parallelMatchesSinglePassBitForBit() {
        for (rate in listOf(48_000, 44_100, 22_050, 32_000, 16_000, 8_000)) {
            val x = signal(rate, 6.0)
            val single = resample(x, 0, x.size, rate).samples
            // Стыки специально не на сетке, а запас у кусков разный и не кратный шагу.
            val starts = listOf(0, rate * 2 + 7, rate * 4 + 333)
            val stitched = parallel(x, rate, starts, preroll = rate / 2 + 11, postroll = rate * 3 / 10 + 5)
            assertEquals("длина при $rate Гц", single.size, stitched.size)
            assertArrayEquals("отсчёты при $rate Гц", single, stitched, 0f)
        }
    }

    @Test
    fun singlePartMatchesPlainResampler() {
        val x = signal(48_000, 2.0)
        val resampler = Resampler(48_000, SAMPLE_RATE)
        val plain = resampler.process(x) + resampler.finish()
        assertArrayEquals(plain, resample(x, 0, x.size, 48_000).samples, 0f)
    }

    @Test
    fun shiftedPartIsRejected() {
        for ((rate, shift) in listOf(16_000 to 1, 48_000 to 3, 48_000 to 312, 44_100 to 441)) {
            val x = signal(rate, 6.0)
            try {
                parallel(x, rate, listOf(0, rate * 2, rate * 4), preroll = rate / 2, postroll = rate * 3 / 10, shift = shift)
                throw AssertionError("сдвиг на $shift при $rate Гц не замечен")
            } catch (_: StitchException) {
            }
        }
    }

    @Test
    fun quietSeamIsNotChecked() {
        val rate = 48_000
        val x = FloatArray(rate * 6) // тишина: сравнивать нечего, склейка проходит
        val stitched = parallel(x, rate, listOf(0, rate * 2, rate * 4), preroll = rate / 2, postroll = rate * 3 / 10, shift = 3)
        // Сдвиг на 3 отсчёта 48 кГц — это один отсчёт 16 кГц, на столько и меняется длина.
        assertEquals(resample(x, 0, x.size, rate).samples.size + 1, stitched.size)
    }

    @Test(expected = StitchException::class)
    fun tooShortPrerollIsRejected() {
        val x = signal(48_000, 3.0)
        parallel(x, 48_000, listOf(0, 48_000), preroll = 5, postroll = 48_000 / 10)
    }

    @Test(expected = StitchException::class)
    fun tooShortPostrollIsRejected() {
        val x = signal(48_000, 3.0)
        parallel(x, 48_000, listOf(0, 48_000), preroll = 48_000 / 10, postroll = 5)
    }
}
