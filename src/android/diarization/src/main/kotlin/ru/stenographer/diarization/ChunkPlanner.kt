package ru.stenographer.diarization

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Отрезок времени в секундах. Внутри модуля время в секундах, как в прототипе: так пороги совпадают точно. */
data class Span(val start: Double, val end: Double) {
    val duration: Double get() = end - start
    fun toMs(): LongRange = (start * 1000).roundToLong()..(end * 1000).roundToLong()
}

/**
 * Куски речи для распознавания по выходу диаризации, порт segmentation.py.
 * Тишина выбрасывается, длинная речь режется в самой тихой точке.
 */
object ChunkPlanner {
    private const val FRAME_SEC = FRAME_MS / 1000.0

    /** Куски до [maxChunk] секунд в мс от начала записи — то, что ждёт `Recognizer.transcribe`. */
    fun plan(probs: FrameProbs, audio: FloatArray, maxChunk: Double = 20.0): List<LongRange> =
        planChunks(speechSegments(speechActivity(probs)), audio, maxChunk).map { it.toMs() }

    /** Вероятность «кто-то говорит» в каждом кадре 10 мс. */
    fun speechActivity(probs: FrameProbs): FloatArray = FloatArray(probs.frames) { f ->
        var best = probs[f, 0]
        for (s in 1 until NUM_SPEAKERS) best = max(best, probs[f, s])
        best
    }

    /** Отрезки речи по покадровой активности. Всплески короче [minDuration] отбрасываются. */
    fun speechSegments(
        activity: FloatArray,
        threshold: Float = 0.4f,
        pad: Double = 0.25,
        mergeGap: Double = 0.5,
        minDuration: Double = 0.1,
    ): List<Span> {
        val total = activity.size * FRAME_SEC
        val segments = ArrayList<Span>()
        var f = 0
        while (f < activity.size) {
            if (activity[f] < threshold) {
                f++
                continue
            }
            val s = f
            while (f < activity.size && activity[f] >= threshold) f++
            val e = f
            if ((e - s) * FRAME_SEC < minDuration) continue
            val start = max(0.0, s * FRAME_SEC - pad)
            val end = min(total, e * FRAME_SEC + pad)
            if (segments.isNotEmpty() && start - segments.last().end <= mergeGap) {
                segments[segments.lastIndex] = Span(segments.last().start, end)
            } else {
                segments += Span(start, end)
            }
        }
        return segments
    }

    /** Момент с наименьшей энергией в [start, end] — место для разреза без потери слов. */
    fun quietestPoint(audio: FloatArray, start: Double, end: Double, window: Double = 0.05): Double {
        val a = (start * SAMPLE_RATE).toInt().coerceIn(0, audio.size)
        val b = (end * SAMPLE_RATE).toInt().coerceIn(a, audio.size)
        val win = (window * SAMPLE_RATE).toInt()
        if (b - a < win * 2) return (start + end) / 2
        val n = (b - a) / win
        var best = 0
        var bestEnergy = Double.MAX_VALUE
        for (i in 0 until n) {
            var energy = 0.0
            for (j in a + i * win until a + (i + 1) * win) energy += audio[j].toDouble() * audio[j]
            if (energy < bestEnergy) {
                bestEnergy = energy
                best = i
            }
        }
        return start + (best + 0.5) * window
    }

    /** Склеивает отрезки речи в куски до [maxChunk] секунд, слишком длинные режет в самой тихой точке. */
    fun planChunks(
        segments: List<Span>,
        audio: FloatArray,
        maxChunk: Double = 20.0,
        maxJoinGap: Double = 1.5,
    ): List<Span> {
        val pieces = ArrayList<Span>()
        for (segment in segments) {
            var start = segment.start
            val end = segment.end
            while (end - start > maxChunk) {
                val cut = quietestPoint(audio, start + maxChunk * 0.6, start + maxChunk)
                pieces += Span(start, cut)
                start = cut
            }
            pieces += Span(start, end)
        }
        val chunks = ArrayList<Span>()
        for (piece in pieces) {
            val last = chunks.lastOrNull()
            if (last != null && piece.start - last.end <= maxJoinGap && piece.end - last.start <= maxChunk) {
                chunks[chunks.lastIndex] = Span(last.start, piece.end)
            } else {
                chunks += piece
            }
        }
        return chunks
    }
}
