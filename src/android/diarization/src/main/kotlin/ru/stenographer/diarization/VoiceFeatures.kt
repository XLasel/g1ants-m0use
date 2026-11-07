package ru.stenographer.diarization

import ru.stenographer.core.SAMPLE_RATE
import ru.stenographer.core.Segment
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Характеристики голоса для узора в интерфейсе (событие `SpeakerFeatures`).
 *
 * @param pitchMedianHz медиана основного тона, Гц: у мужских голосов обычно 85–155, у женских 165–255
 * @param pitchSpread разброс тона от 0 до 1: межквартильный размах в полутонах, 12 полутонов (октава) = 1
 * @param loudnessMean средняя громкость голоса от 0 до 1 относительно самого громкого места записи
 */
data class VoiceFeatures(
    val speakerId: String,
    val pitchMedianHz: Float,
    val pitchSpread: Float,
    val loudnessMean: Float,
)

/**
 * Считает [VoiceFeatures] по отрезкам каждого голоса после диаризации. Без нейросети: тон — алгоритмом YIN
 * (de Cheveigné, Kawahara, 2002) на звуке, прореженном до 8 кГц, громкость — RMS окнами по 40 мс.
 *
 * Берутся только места, где голос звучит один (перекрытия с другими голосами пропускаются), и не больше
 * [maxFramesPerSpeaker] окон на голос, равномерно по всем его отрезкам: на 3 минуты звука это доли секунды.
 */
object VoiceFeatureExtractor {
    private const val RATE = SAMPLE_RATE / 2 // 8 кГц: для тона 60–400 Гц хватает
    private const val WINDOW = RATE * 40 / 1000 // 40 мс
    private const val MIN_HZ = 60
    private const val MAX_HZ = 400
    private const val YIN_THRESHOLD = 0.15f
    private const val SILENCE_SHARE = 0.05f // окна тише 5% от максимума записи не считаются

    fun extract(audio: FloatArray, segments: List<Segment>, maxFramesPerSpeaker: Int = 400): List<VoiceFeatures> {
        if (audio.isEmpty() || segments.isEmpty()) return emptyList()
        val signal = downsample(audio)
        val peakRms = peakRms(signal)
        if (peakRms <= 0f) return emptyList()
        val solo = soloSpans(segments)
        val order = segments.map { it.speakerId }.distinct()
        return order.mapNotNull { id ->
            val spans = solo[id].orEmpty()
            val starts = windowStarts(spans, signal.size, maxFramesPerSpeaker)
            if (starts.isEmpty()) return@mapNotNull null
            val pitches = ArrayList<Float>()
            var loudness = 0.0
            var voiced = 0
            for (start in starts) {
                val rms = rms(signal, start, WINDOW)
                if (rms < peakRms * SILENCE_SHARE) continue
                loudness += rms / peakRms
                voiced++
                yin(signal, start)?.let { pitches += it }
            }
            if (voiced == 0) return@mapNotNull null
            val median = if (pitches.isEmpty()) 0f else quantile(pitches, 0.5f)
            val spread = if (pitches.size < 4) 0f else {
                val semitones = 12f * (ln(quantile(pitches, 0.75f) / quantile(pitches, 0.25f)) / ln(2f))
                (semitones / 12f).coerceIn(0f, 1f)
            }
            VoiceFeatures(id, median, spread, (loudness / voiced).toFloat().coerceIn(0f, 1f))
        }
    }

    /** Основной тон окна в Гц или null, если окно не звонкое (шум, глухие звуки). */
    internal fun yin(x: FloatArray, start: Int): Float? {
        val minLag = RATE / MAX_HZ
        val maxLag = RATE / MIN_HZ
        if (start + WINDOW + maxLag > x.size) return null
        val d = FloatArray(maxLag + 1)
        for (lag in 1..maxLag) {
            var sum = 0f
            for (j in start until start + WINDOW) {
                val diff = x[j] - x[j + lag]
                sum += diff * diff
            }
            d[lag] = sum
        }
        // Накопленная нормированная разность: где она впервые опускается ниже порога, там период
        var running = 0f
        var best = -1
        for (lag in 1..maxLag) {
            running += d[lag]
            val cmnd = if (running > 0f) d[lag] * lag / running else 1f
            d[lag] = cmnd
        }
        var lag = minLag
        while (lag <= maxLag) {
            if (d[lag] < YIN_THRESHOLD) {
                while (lag + 1 <= maxLag && d[lag + 1] < d[lag]) lag++
                best = lag
                break
            }
            lag++
        }
        if (best < 0) return null
        // Уточнение периода параболой по соседним точкам
        val refined = if (best in 2 until maxLag) {
            val a = d[best - 1]
            val b = d[best]
            val c = d[best + 1]
            val denom = a - 2 * b + c
            if (denom != 0f) best + 0.5f * (a - c) / denom else best.toFloat()
        } else {
            best.toFloat()
        }
        return RATE / refined
    }

    /** Отрезки голоса без перекрытий с другими голосами, в отсчётах 8 кГц. */
    private fun soloSpans(segments: List<Segment>): Map<String, List<IntRange>> {
        val result = HashMap<String, MutableList<IntRange>>()
        for (s in segments) {
            var pieces = listOf(s.startMs to s.endMs)
            for (o in segments) {
                if (o.speakerId == s.speakerId || o.endMs <= s.startMs || o.startMs >= s.endMs) continue
                pieces = pieces.flatMap { (a, b) ->
                    listOfNotNull((a to min(b, o.startMs)).takeIf { it.second > it.first },
                        (max(a, o.endMs) to b).takeIf { it.second > it.first })
                }
            }
            for ((a, b) in pieces) {
                val from = (a * RATE / 1000).toInt()
                val to = (b * RATE / 1000).toInt()
                if (to - from >= WINDOW) result.getOrPut(s.speakerId) { ArrayList() } += from until to
            }
        }
        return result
    }

    /** Начала окон равномерно по всем отрезкам, не больше [limit]. */
    private fun windowStarts(spans: List<IntRange>, size: Int, limit: Int): List<Int> {
        val all = ArrayList<Int>()
        for (span in spans) {
            var start = span.first
            while (start + WINDOW <= min(span.last + 1, size)) {
                all += start
                start += WINDOW
            }
        }
        if (all.size <= limit) return all
        return List(limit) { all[(it.toLong() * all.size / limit).toInt()] }
    }

    private fun downsample(audio: FloatArray): FloatArray =
        FloatArray(audio.size / 2) { (audio[2 * it] + audio[2 * it + 1]) * 0.5f }

    private fun rms(x: FloatArray, start: Int, length: Int): Float {
        var sum = 0.0
        val end = min(start + length, x.size)
        for (j in start until end) sum += x[j].toDouble() * x[j]
        return if (end > start) sqrt(sum / (end - start)).toFloat() else 0f
    }

    private fun peakRms(x: FloatArray): Float {
        var peak = 0f
        var start = 0
        while (start + WINDOW <= x.size) {
            peak = max(peak, rms(x, start, WINDOW))
            start += WINDOW
        }
        return peak
    }

    private fun quantile(values: List<Float>, q: Float): Float {
        val sorted = values.sorted()
        val pos = q * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = min(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }
}
