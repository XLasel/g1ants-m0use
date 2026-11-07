package ru.stenographer.diarization

/** Отрезок, где говорит спикер. Отрезки разных спикеров могут пересекаться (перебивания). */
data class Segment(val speakerId: String, val startMs: Long, val endMs: Long)

/** Голос впервые уверенно прозвучал в момент [firstAtMs]. */
data class FoundSpeaker(val speakerId: String, val firstAtMs: Long)

/**
 * Порция результата диаризации для интерфейса (события `SpeakerFound` и `Segments` из контракта UI).
 * Отрезки в порции закрыты: дальше они не удлиняются. Последняя порция приходит с [final] = true.
 */
data class DiarizationUpdate(
    val newSpeakers: List<FoundSpeaker>,
    val segments: List<Segment>,
    val processedUntilMs: Long,
    val final: Boolean,
) {
    /** Есть что показать: новый голос или отрезок. Финальную порцию стоит отдавать всегда. */
    val hasNews: Boolean get() = newSpeakers.isNotEmpty() || segments.isNotEmpty() || final
}

/** Итог диаризации: вероятности для нарезки и совмещения, все отрезки и номера спикеров. */
class DiarizationResult(val probs: FrameProbs, val speakers: SpeakerIds, val segments: List<Segment>)

/**
 * Номера спикеров `speaker_1`, `speaker_2`, ... по порядку первого появления, стабильные до конца файла.
 * Одни и те же номера получают найденные голоса, отрезки и реплики в JSON.
 */
class SpeakerIds {
    private val byColumn = IntArray(NUM_SPEAKERS) { -1 }
    private var count = 0

    /** Номер столбца Nemotron; новый столбец получает следующий номер. */
    fun idOf(column: Int): String {
        if (byColumn[column] < 0) byColumn[column] = count++
        return "speaker_${byColumn[column] + 1}"
    }

    /** Порядковый номер с нуля для [Utterance.speaker]. */
    internal fun indexOf(column: Int): Int {
        idOf(column)
        return byColumn[column]
    }

    fun isKnown(column: Int): Boolean = byColumn[column] >= 0
    val size: Int get() = count
}

/**
 * Превращает вероятности в отрезки голосов по мере их поступления.
 *
 * Спикер говорит в кадре, если его вероятность не ниже [threshold]. Паузы одного спикера короче [joinGapMs]
 * склеиваются, отрезки короче [minSegmentMs] отбрасываются (всплески). Голос считается найденным в кадре,
 * где его первый отрезок дорос до [minSegmentMs], поэтому одиночные всплески не дают ложных голосов, а номера
 * идут строго по времени появления. Результат не зависит от того, какими порциями приходили кадры.
 */
class SegmentTracker(
    private val ids: SpeakerIds = SpeakerIds(),
    private val threshold: Float = 0.5f,
    private val minSegmentMs: Long = 200,
    private val joinGapMs: Long = 300,
) {
    private val openStart = LongArray(NUM_SPEAKERS) { -1 } // начало текущего отрезка, мс
    private val lastActive = LongArray(NUM_SPEAKERS) { -1 } // конец последнего активного кадра, мс
    private var frame = 0
    private var finished = false
    private val all = ArrayList<Segment>()

    val speakers: SpeakerIds get() = ids
    val segments: List<Segment> get() = all

    /** Новые кадры вероятностей (по строкам, [NUM_SPEAKERS] на кадр) по порядку. */
    fun feed(probs: FloatArray, fromFrame: Int, toFrame: Int): DiarizationUpdate {
        check(!finished) { "Диаризация уже завершена" }
        require(fromFrame == frame) { "Кадры должны идти подряд: ждали $frame, пришёл $fromFrame" }
        val found = ArrayList<FoundSpeaker>()
        val closed = ArrayList<Segment>()
        for (f in fromFrame until toFrame) {
            val startMs = f * FRAME_MS
            for (s in 0 until NUM_SPEAKERS) {
                val active = probs[f * NUM_SPEAKERS + s] >= threshold
                if (openStart[s] >= 0 && startMs - lastActive[s] > joinGapMs) close(s, closed)
                if (!active) continue
                if (openStart[s] < 0) openStart[s] = startMs
                lastActive[s] = startMs + FRAME_MS
                if (!ids.isKnown(s) && lastActive[s] - openStart[s] >= minSegmentMs) {
                    found += FoundSpeaker(ids.idOf(s), openStart[s])
                }
            }
        }
        frame = toFrame
        return emit(found, closed, final = false)
    }

    /** Закрывает все открытые отрезки. После этого кадры больше не принимаются. */
    fun finish(): DiarizationUpdate {
        check(!finished) { "Диаризация уже завершена" }
        finished = true
        val closed = ArrayList<Segment>()
        for (s in 0 until NUM_SPEAKERS) if (openStart[s] >= 0) close(s, closed)
        return emit(emptyList(), closed, final = true)
    }

    private fun close(column: Int, out: MutableList<Segment>) {
        val start = openStart[column]
        val end = lastActive[column]
        openStart[column] = -1
        // Короткий всплеск не попадает в отрезки; голос с таким всплеском и так не зарегистрирован
        if (end - start >= minSegmentMs) out += Segment(ids.idOf(column), start, end)
    }

    private fun emit(found: List<FoundSpeaker>, closed: List<Segment>, final: Boolean): DiarizationUpdate {
        val segments = closed.sortedWith(compareBy({ it.startMs }, { it.speakerId }))
        all += segments
        return DiarizationUpdate(found, segments, frame * FRAME_MS, final)
    }

    companion object {
        /** Все отрезки сразу по готовым вероятностям. */
        fun segmentsOf(probs: FrameProbs, ids: SpeakerIds = SpeakerIds()): List<Segment> {
            val tracker = SegmentTracker(ids)
            tracker.feed(probs.data, 0, probs.frames)
            tracker.finish()
            return tracker.segments
        }
    }
}

/** Реплики с номерами спикеров из [ids], согласованными с найденными голосами и отрезками. */
fun List<Utterance>.withSpeakerIds(ids: SpeakerIds): List<Utterance> = map { u ->
    // Столбец, у которого не было ни одного отрезка, получает следующий свободный номер.
    val speaker = ids.indexOf(u.speaker)
    u.copy(speaker = speaker, words = u.words.map { it.copy(speaker = speaker) })
}
