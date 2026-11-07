package ru.stenographer.diarization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class LogMelTest {
    @Test fun `БПФ совпадает с прямым вычислением ДПФ`() {
        val n = 64
        val random = Random(1)
        val x = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
        val re = x.copyOf()
        val im = DoubleArray(n)
        Fft(n).transform(re, im)
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                sr += x[t] * cos(2 * PI * k * t / n)
                si -= x[t] * sin(2 * PI * k * t / n)
            }
            assertEquals(sr, re[k], 1e-9)
            assertEquals(si, im[k], 1e-9)
        }
    }

    @Test fun `число кадров как в прототипе`() {
        val mel = LogMel(FloatArray(Nemotron.MEL_BINS * Nemotron.SPECTRUM_BINS))
        assertEquals(100, mel.frameCount(SAMPLE_RATE))
        assertEquals(0, mel.frameCount(159))
    }

    @Test fun `признаки совпадают с Python на всех записях`() {
        Reference.requireModels()
        val logMel = LogMel(readFloats(File(Reference.modelsDir, Nemotron.MEL_FILTERS_FILE)))
        for (ref in Reference.all()) {
            val expected = ref.floats("mel")
            val actual = logMel.compute(ref.floats("audio"))
            val diff = Diff.of(expected, actual)
            println("${ref.name}: мел $diff")
            // Признаки — логарифм мощности, разброс от -17 до +10. Расхождение — шум округления float32.
            assertTrue("${ref.name}: $diff", diff.max < 1e-3f && diff.mean < 1e-5)
        }
    }
}
