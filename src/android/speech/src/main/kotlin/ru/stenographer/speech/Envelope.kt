package ru.stenographer.speech

import kotlin.math.min
import kotlin.math.sqrt

/** Шаг огибающей для события `Decoded`. */
const val ENVELOPE_WINDOW_MS = 50L

/**
 * Огибающая громкости для дорожек и экрана записи: RMS по окнам [windowMs], поделённая на максимум по всей записи.
 * Значения от 0 до 1, одно на окно. Последнее окно может быть короче остальных. У тишины все значения равны 0.
 */
fun loudnessEnvelope(audio: FloatArray, windowMs: Long = ENVELOPE_WINDOW_MS): FloatArray {
    val window = msToSample(windowMs)
    require(window > 0) { "Окно должно быть не короче одного отсчёта: $windowMs мс" }
    val count = (audio.size + window - 1) / window
    val envelope = FloatArray(count)
    var max = 0f
    for (i in 0 until count) {
        val from = i * window
        val to = min(from + window, audio.size)
        var sum = 0.0
        for (s in from until to) sum += audio[s].toDouble() * audio[s]
        val rms = sqrt(sum / (to - from)).toFloat()
        envelope[i] = rms
        if (rms > max) max = rms
    }
    if (max > 0f) for (i in envelope.indices) envelope[i] /= max
    return envelope
}
