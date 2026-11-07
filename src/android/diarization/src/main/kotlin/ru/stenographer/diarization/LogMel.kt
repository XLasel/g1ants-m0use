package ru.stenographer.diarization

import ru.stenographer.diarization.Nemotron.HOP_LENGTH
import ru.stenographer.diarization.Nemotron.LOG_GUARD
import ru.stenographer.diarization.Nemotron.MEL_BINS
import ru.stenographer.diarization.Nemotron.N_FFT
import ru.stenographer.diarization.Nemotron.PREEMPHASIS
import ru.stenographer.diarization.Nemotron.SPECTRUM_BINS
import ru.stenographer.diarization.Nemotron.WIN_LENGTH
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln

/**
 * Лог-мел признаки для Nemotron, порт `log_mel` из onnx_diarizer.py.
 *
 * Порядок операций и точность (float32 там, где NumPy считает во float32) повторяют прототип,
 * чтобы признаки совпадали с Python до шума округления.
 *
 * @param melFilters матрица [128, 257] из mel_filters.bin, по строкам
 */
internal class LogMel(private val melFilters: FloatArray) {
    init {
        require(melFilters.size == MEL_BINS * SPECTRUM_BINS) { "Неверный размер mel_filters: ${melFilters.size}" }
    }

    private val window = FloatArray(N_FFT).also { w ->
        // Как np.hanning(400): 0.5 + 0.5 cos(pi n / (M - 1)), n = 1 - M, 3 - M, ..., M - 1
        val left = (N_FFT - WIN_LENGTH) / 2
        for (k in 0 until WIN_LENGTH) {
            val n = 1 - WIN_LENGTH + 2 * k
            w[left + k] = (0.5 + 0.5 * cos(PI * n / (WIN_LENGTH - 1))).toFloat()
        }
    }
    private val fft = Fft(N_FFT)

    /** Число кадров признаков для записи из [samples] отсчётов. */
    fun frameCount(samples: Int): Int = samples / HOP_LENGTH

    /** Звук 16 кГц → [кадры, 128] по строкам. */
    fun compute(audio: FloatArray): FloatArray {
        val frames = frameCount(audio.size)
        val result = FloatArray(frames * MEL_BINS)
        val re = DoubleArray(N_FFT)
        val im = DoubleArray(N_FFT)
        val power = FloatArray(SPECTRUM_BINS)
        val pad = N_FFT / 2
        for (frame in 0 until frames) {
            val offset = frame * HOP_LENGTH - pad // начало окна в исходном звуке
            for (j in 0 until N_FFT) {
                re[j] = (emphasized(audio, offset + j) * window[j]).toDouble()
                im[j] = 0.0
            }
            fft.transform(re, im)
            for (k in 0 until SPECTRUM_BINS) power[k] = (re[k] * re[k] + im[k] * im[k]).toFloat()
            val out = frame * MEL_BINS
            for (m in 0 until MEL_BINS) {
                val row = m * SPECTRUM_BINS
                var sum = 0.0
                for (k in 0 until SPECTRUM_BINS) sum += power[k] * melFilters[row + k].toDouble()
                result[out + m] = ln(sum.toFloat() + LOG_GUARD)
            }
        }
        return result
    }

    /** Звук после преэмфазиса, нули за краями (np.pad). */
    private fun emphasized(audio: FloatArray, i: Int): Float = when {
        i < 0 || i >= audio.size -> 0f
        i == 0 -> audio[0]
        else -> audio[i] - PREEMPHASIS * audio[i - 1]
    }
}

/** БПФ по основанию 2, прямое (e^-i). Размер — степень двойки. */
internal class Fft(private val n: Int) {
    init {
        require(n > 1 && n and (n - 1) == 0) { "Размер БПФ должен быть степенью двойки" }
    }

    private val cosTable = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinTable = DoubleArray(n / 2) { kotlin.math.sin(2 * PI * it / n) }
    private val reversed = IntArray(n).also { rev ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = reversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val l = j + half
                    val c = cosTable[k]
                    val s = sinTable[k]
                    val tRe = re[l] * c + im[l] * s
                    val tIm = im[l] * c - re[l] * s
                    re[l] = re[j] - tRe
                    im[l] = im[j] - tIm
                    re[j] += tRe
                    im[j] += tIm
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }
}
