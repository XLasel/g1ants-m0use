package ru.stenographer.diarization

import ru.stenographer.core.Utterance
import ru.stenographer.core.Word
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Слово со спикером. [speaker] — номер столбца диаризации с нуля, [confidence] — его средняя вероятность. */
data class SpeakerWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val speaker: Int,
    val confidence: Float,
)

/** Реплика внутри модуля: подряд идущие слова одного столбца Nemotron. Наружу уходит [Utterance] из `:core`. */
data class Turn(val speaker: Int, val startMs: Long, val endMs: Long, val words: List<SpeakerWord>) {
    val text: String get() = words.joinToString(" ") { it.text }
}

/**
 * Совмещение слов ASR с диаризацией, порт merge.py: кто какое слово сказал и сборка реплик.
 * Эхо микрофона (`remove_echo`) нужно только для живой записи с двух источников и не перенесено.
 */
object TranscriptMerger {
    private const val FRAME_SEC = FRAME_MS / 1000.0
    private const val WIDE_FRAMES = 50
    private val SENTENCE_END = charArrayOf('.', '?', '!', '…')
    private const val SOFT_END = ",:;—-"

    /**
     * Вся цепочка: спикеры словам, затем реплики. Номера спикеров берутся из [ids] диаризации, поэтому совпадают
     * с найденными голосами и отрезками; столбец без отрезков получает следующий свободный номер.
     */
    fun merge(words: List<Word>, probs: FrameProbs, ids: SpeakerIds, maxGapMs: Long = 2_000): List<Utterance> =
        turns(words, probs, maxGapMs).map { turn ->
            Utterance(
                speakerId = ids.idOf(turn.speaker),
                startMs = turn.startMs,
                endMs = turn.endMs,
                text = turn.text,
                words = turn.words.map { Word(it.text, it.startMs, it.endMs) },
            )
        }

    /** Реплики с номерами столбцов Nemotron, как в merge.py прототипа. */
    fun turns(words: List<Word>, probs: FrameProbs, maxGapMs: Long = 2_000): List<Turn> =
        mergeAdjacent(buildUtterances(assignSpeakers(words, probs), maxGapMs), maxGapMs)

    /** Спикер каждого слова по средней вероятности на интервале слова, затем сглаживание ошибок на границах. */
    fun assignSpeakers(words: List<Word>, probs: FrameProbs, smooth: Boolean = true): List<SpeakerWord> {
        if (words.isEmpty()) return emptyList()
        val idx = IntArray(words.size) { -1 }
        val scores = Array(words.size) { FloatArray(NUM_SPEAKERS) }

        for ((i, w) in words.withIndex()) {
            val a = frameIndex(w.startMs)
            val b = max(a + 1, frameIndex(w.endMs))
            var score = windowMean(probs, a, b)
            if (score == null || score.max() < 0.2f) {
                // Слово попало в «тишину» диаризации: смотрим шире
                val wide = windowMean(probs, a - WIDE_FRAMES, b + WIDE_FRAMES)
                if (wide != null && (score == null || wide.max() > score.max())) score = wide
            }
            if (score == null || score.max() < 0.05f) continue
            idx[i] = argmax(score)
            scores[i] = score
        }

        // Слова без активности — к ближайшему соседу, при равенстве к более раннему
        val known = idx.indices.filter { idx[it] >= 0 }
        if (known.isEmpty()) {
            idx.fill(0)
        } else {
            for (i in idx.indices) {
                if (idx[i] >= 0) continue
                idx[i] = idx[known.minBy { abs(it - i) }]
            }
        }

        if (smooth && words.size > 2) {
            smoothIsolated(words, idx, scores)
            sentenceMajority(words, idx, scores)
        }
        return words.mapIndexed { i, w -> SpeakerWord(w.text, w.startMs, w.endMs, idx[i], scores[i][idx[i]]) }
    }

    /** Группирует подряд идущие слова одного спикера в реплики. */
    fun buildUtterances(words: List<SpeakerWord>, maxGapMs: Long = 2_000): List<Turn> {
        val result = ArrayList<Turn>()
        for (w in words.sortedBy { it.startMs }) {
            val last = result.lastOrNull()
            if (last != null && last.speaker == w.speaker && sec(w.startMs) - sec(last.endMs) <= sec(maxGapMs)) {
                result[result.lastIndex] = last.copy(endMs = max(last.endMs, w.endMs), words = last.words + w)
            } else {
                result += Turn(w.speaker, w.startMs, w.endMs, listOf(w))
            }
        }
        return result
    }

    /** Сортирует реплики и склеивает соседние реплики одного спикера. */
    fun mergeAdjacent(utterances: List<Turn>, maxGapMs: Long = 2_000): List<Turn> {
        val result = ArrayList<Turn>()
        for (u in utterances.sortedBy { it.startMs }) {
            val last = result.lastOrNull()
            if (last != null && last.speaker == u.speaker && sec(u.startMs) - sec(last.endMs) <= sec(maxGapMs)) {
                result[result.lastIndex] = last.copy(endMs = max(last.endMs, u.endMs), words = last.words + u.words)
            } else {
                result += u
            }
        }
        return result
    }

    /** Одиночное слово «чужого» спикера посреди плотной речи другого — обычно дрожание границы. */
    private fun smoothIsolated(words: List<Word>, idx: IntArray, scores: Array<FloatArray>) {
        for (i in 1 until words.size - 1) {
            val prev = idx[i - 1]
            if (prev != idx[i + 1] || idx[i] == prev) continue
            val tight = sec(words[i].startMs) - sec(words[i - 1].endMs) < 0.4 &&
                sec(words[i + 1].startMs) - sec(words[i].endMs) < 0.4
            if (tight && scores[i][prev] >= 0.3f) idx[i] = prev
        }
    }

    /**
     * Смена спикера посреди предложения без паузы — обычно ошибка границы: предложение отдаётся спикеру большинства.
     * Уверенные решения диаризации (свой спикер от 0,6, спикер большинства меньше 0,25) не перезаписываются.
     */
    private fun sentenceMajority(
        words: List<Word>,
        idx: IntArray,
        scores: Array<FloatArray>,
        pause: Double = 0.5,
        minShare: Double = 0.6,
    ) {
        var start = 0
        for (i in words.indices) {
            if (i < words.size - 1 && !isBoundary(words[i], words[i + 1], pause)) continue
            val sentence = start..i
            start = i + 1
            val speakers = sentence.map { idx[it] } // копия: решения внутри предложения не влияют друг на друга
            val distinct = speakers.toSortedSet()
            if (distinct.size < 2) continue
            // Вес слова — число букв: стабильнее, чем длительность коротких слов
            val weights = sentence.map { max(normalize(words[it].text).length, 1) }
            val totals = distinct.associateWith { s -> speakers.indices.filter { speakers[it] == s }.sumOf { weights[it] } }
            var best = distinct.first()
            for (s in distinct) if (totals.getValue(s) > totals.getValue(best)) best = s
            if (totals.getValue(best).toDouble() / weights.sum() < minShare) continue
            for (s in distinct) {
                if (s == best) continue
                val minority = sentence.filterIndexed { k, _ -> speakers[k] == s }
                val own = mean(minority.map { scores[it][s] })
                val major = mean(minority.map { scores[it][best] })
                if (!(own >= 0.6f && major < 0.25f)) minority.forEach { idx[it] = best }
            }
        }
    }

    private fun isBoundary(cur: Word, next: Word, pause: Double): Boolean {
        if (cur.text.isNotEmpty() && cur.text.last() in SENTENCE_END) return true
        if (sec(next.startMs) - sec(cur.endMs) >= pause) return true
        // «каждого: Это точно» — знак препинания и заглавная буква тоже похожи на начало новой фразы
        val softEnd = cur.text.isEmpty() || cur.text.last() in SOFT_END
        return softEnd && next.text.isNotEmpty() && next.text.first().isUpperCase()
    }

    private fun normalize(text: String): String =
        text.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() || it == '_' }

    /** Средняя вероятность спикеров на кадрах [a, b) или null, если интервал вне записи. Сумма во float32, как в NumPy. */
    private fun windowMean(probs: FrameProbs, from: Int, to: Int): FloatArray? {
        val a = max(0, from)
        val b = min(probs.frames, to)
        if (b <= a) return null
        val sum = FloatArray(NUM_SPEAKERS)
        for (f in a until b) for (s in 0 until NUM_SPEAKERS) sum[s] += probs[f, s]
        val count = (b - a).toFloat()
        for (s in 0 until NUM_SPEAKERS) sum[s] /= count
        return sum
    }

    private fun mean(values: List<Float>): Float {
        var sum = 0f
        for (v in values) sum += v
        return sum / values.size
    }

    private fun argmax(values: FloatArray): Int {
        var best = 0
        for (i in 1 until values.size) if (values[i] > values[best]) best = i
        return best
    }

    /** Индекс кадра как `int(round(start / 0.01))` в Python: округление к чётному. */
    private fun frameIndex(ms: Long): Int = round(sec(ms) / FRAME_SEC).toInt()

    private fun sec(ms: Long): Double = ms / 1000.0
}
