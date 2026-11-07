package ru.stenographer.speech

/**
 * Временная нарезка без диаризации: режет запись на куски до [maxChunkMs] в самой тихой точке.
 * Тишину она не убирает. Когда роль 3 подключит `plan_chunks`, этот класс останется только для замеров.
 */
object QuietChunker {
    private const val WINDOW_MS = 50L

    fun plan(audio: FloatArray, maxChunkMs: Long = 20_000): List<LongRange> {
        val totalMs = sampleToMs(audio.size)
        val chunks = ArrayList<LongRange>()
        var start = 0L
        while (totalMs - start > maxChunkMs) {
            val cut = quietestPoint(audio, start + maxChunkMs * 6 / 10, start + maxChunkMs)
            chunks += start..cut
            start = cut
        }
        if (totalMs > start) chunks += start..totalMs
        return chunks
    }

    /** Порт `quietest_point` из segmentation.py: середина окна 50 мс с наименьшей энергией. */
    internal fun quietestPoint(audio: FloatArray, startMs: Long, endMs: Long): Long {
        val from = msToSample(startMs).coerceIn(0, audio.size)
        val to = msToSample(endMs).coerceIn(from, audio.size)
        val window = msToSample(WINDOW_MS)
        val windows = (to - from) / window
        if (windows < 2) return (startMs + endMs) / 2
        var best = 0
        var bestEnergy = Double.MAX_VALUE
        for (i in 0 until windows) {
            var energy = 0.0
            for (s in from + i * window until from + (i + 1) * window) energy += audio[s] * audio[s]
            if (energy < bestEnergy) {
                bestEnergy = energy
                best = i
            }
        }
        return startMs + best * WINDOW_MS + WINDOW_MS / 2
    }
}
