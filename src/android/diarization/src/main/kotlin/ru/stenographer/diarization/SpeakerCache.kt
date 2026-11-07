package ru.stenographer.diarization

import ru.stenographer.diarization.Nemotron.CACHE_LENGTH
import ru.stenographer.diarization.Nemotron.CACHE_UPDATE_PERIOD
import ru.stenographer.diarization.Nemotron.FIFO_LENGTH
import ru.stenographer.diarization.Nemotron.LATEST_BOOST
import ru.stenographer.diarization.Nemotron.MIN_POSITIVE
import ru.stenographer.diarization.Nemotron.SCORE_THRESHOLD
import ru.stenographer.diarization.Nemotron.SILENCE_FRAMES
import ru.stenographer.diarization.Nemotron.STRONG_BOOSTED
import ru.stenographer.diarization.Nemotron.WEAK_BOOSTED
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Кэш спикеров и FIFO, порт `SpeakerCache` из onnx_diarizer.py.
 *
 * Перед каждым чанком модель видит [context]: несколько сотен «характерных» кадров уже найденных спикеров
 * и последние кадры. Так спикер 1 в начале записи остаётся спикером 1 и через час.
 *
 * Строки — эмбеддинги [512] и вероятности [8], массивы не копируются и не изменяются.
 */
internal class SpeakerCache(private val silence: FloatArray) {
    var embeds: List<FloatArray> = emptyList()
        private set
    var probs: List<FloatArray> = emptyList()
        private set
    var fifo: List<FloatArray> = emptyList()
        private set
    var compressed = false
        private set

    fun context(): List<FloatArray> = embeds + fifo

    /**
     * @param stepEmbeds вход шага модели: [кэш, FIFO, чанк, правый контекст]
     * @param stepProbs вероятности для каждой строки [stepEmbeds], усреднённые до 80 мс
     * @param numChunk длина чанка без правого контекста
     */
    fun update(stepEmbeds: List<FloatArray>, stepProbs: List<FloatArray>, numChunk: Int) {
        val cacheLen = embeds.size
        val chunkStart = cacheLen + fifo.size
        var newFifo = fifo + stepEmbeds.subList(chunkStart, chunkStart + numChunk)

        if (newFifo.size > FIFO_LENGTH) {
            val popped = min(max(CACHE_UPDATE_PERIOD, newFifo.size - FIFO_LENGTH), newFifo.size)
            val fifoProbs = stepProbs.subList(cacheLen, chunkStart + numChunk)
            val stored = if (compressed) probs else stepProbs.subList(0, cacheLen)
            var cacheEmbeds = embeds + newFifo.subList(0, popped)
            var cacheProbs = stored + fifoProbs.subList(0, popped)
            newFifo = newFifo.subList(popped, newFifo.size).toList()
            if (cacheEmbeds.size > CACHE_LENGTH) {
                val kept = compress(cacheEmbeds, cacheProbs)
                cacheEmbeds = kept.first
                cacheProbs = kept.second
                compressed = true
            }
            embeds = cacheEmbeds.toList()
            probs = cacheProbs.toList()
        }
        fifo = newFifo.toList()
    }

    /** Оставляет [CACHE_LENGTH] строк: лучшие кадры каждого спикера плюс эмбеддинг тишины. */
    private fun compress(
        embeds: List<FloatArray>,
        probs: List<FloatArray>,
    ): Pair<List<FloatArray>, List<FloatArray>> {
        val numFrames = probs.size
        val scores = frameScores(probs)
        for (f in CACHE_LENGTH until numFrames) for (s in 0 until NUM_SPEAKERS) scores[f][s] += LATEST_BOOST
        boost(scores, STRONG_BOOSTED, (-2.0 * ln(0.5)).toFloat())
        boost(scores, WEAK_BOOSTED, (-ln(0.5)).toFloat())

        // Индекс = спикер * numScored + кадр, последний кадр каждого спикера — тишина с оценкой +inf.
        val numScored = numFrames + SILENCE_FRAMES
        val flat = FloatArray(numScored * NUM_SPEAKERS)
        for (s in 0 until NUM_SPEAKERS) {
            for (f in 0 until numFrames) flat[s * numScored + f] = scores[f][s]
            for (f in numFrames until numScored) flat[s * numScored + f] = Float.POSITIVE_INFINITY
        }
        val sentinel = numScored * NUM_SPEAKERS
        val top = topIndices(flat, CACHE_LENGTH)
            .map { if (flat[it] == Float.NEGATIVE_INFINITY) sentinel else it }
            .sorted()
        val zeros = FloatArray(NUM_SPEAKERS)
        val keptEmbeds = ArrayList<FloatArray>(top.size)
        val keptProbs = ArrayList<FloatArray>(top.size)
        for (index in top) {
            val frame = if (index == sentinel) numFrames else min(index % numScored, numFrames)
            keptEmbeds += if (frame == numFrames) silence else embeds[frame]
            keptProbs += if (frame == numFrames) zeros else probs[frame]
        }
        return keptEmbeds to keptProbs
    }

    companion object {
        private val LN_HALF = ln(0.5).toFloat()

        /** Насколько кадр характерен для каждого спикера, порт `_frame_scores`. Не речь — минус бесконечность. */
        internal fun frameScores(probs: List<FloatArray>): Array<FloatArray> {
            val scores = Array(probs.size) { FloatArray(NUM_SPEAKERS) }
            val logP = FloatArray(NUM_SPEAKERS)
            val logC = FloatArray(NUM_SPEAKERS)
            val positive = IntArray(NUM_SPEAKERS)
            for ((f, p) in probs.withIndex()) {
                for (s in 0 until NUM_SPEAKERS) {
                    logP[s] = ln(max(p[s], SCORE_THRESHOLD))
                    logC[s] = ln(max(1f - p[s], SCORE_THRESHOLD))
                }
                // NumPy складывает 8 чисел попарно в таком порядке
                val sumC = ((logC[0] + logC[1]) + (logC[2] + logC[3])) + ((logC[4] + logC[5]) + (logC[6] + logC[7]))
                for (s in 0 until NUM_SPEAKERS) {
                    val score = if (p[s] > 0.5f) logP[s] - logC[s] + sumC - LN_HALF else Float.NEGATIVE_INFINITY
                    scores[f][s] = score
                    if (score > 0f) positive[s]++
                }
            }
            for ((f, p) in probs.withIndex()) {
                for (s in 0 until NUM_SPEAKERS) {
                    if (!(scores[f][s] > 0f) && p[s] > 0.5f && positive[s] >= MIN_POSITIVE) {
                        scores[f][s] = Float.NEGATIVE_INFINITY
                    }
                }
            }
            return scores
        }

        /** Прибавляет [boost] к [count] лучшим кадрам каждого спикера. */
        private fun boost(scores: Array<FloatArray>, count: Int, boost: Float) {
            val column = FloatArray(scores.size)
            for (s in 0 until NUM_SPEAKERS) {
                for (f in scores.indices) column[f] = scores[f][s]
                for (f in topIndices(column, min(count, scores.size))) scores[f][s] += boost
            }
        }

        /** Индексы [count] наибольших значений; при равенстве выигрывает меньший индекс. */
        internal fun topIndices(values: FloatArray, count: Int): List<Int> =
            values.indices.sortedWith { a, b ->
                val byValue = values[b].compareTo(values[a])
                if (byValue != 0) byValue else a.compareTo(b)
            }.take(count)
    }
}
