package ru.stenographer.diarization

import ru.stenographer.core.Word
import ru.stenographer.core.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerTimelineTest {
    /** Спикер-столбец [column] говорит в кадрах [range] (по 10 мс). */
    private fun probs(frames: Int, vararg turns: Pair<Int, IntRange>): FloatArray {
        val data = FloatArray(frames * NUM_SPEAKERS)
        for ((column, range) in turns) for (f in range) data[f * NUM_SPEAKERS + column] = 0.9f
        return data
    }

    @Test fun `перекрытия сохраняются, паузы склеиваются, всплески отбрасываются`() {
        val data = probs(
            1000,
            0 to 100..299, // 1,0–3,0 с
            0 to 320..399, // пауза 0,2 с — склеится
            1 to 250..449, // перебивает с 2,5 с
            2 to 700..709, // всплеск 0,1 с — не голос
        )
        val ids = SpeakerIds()
        val segments = SegmentTracker.segmentsOf(FrameProbs(data), ids)
        assertEquals(
            listOf(Segment("speaker_1", 1_000, 4_000), Segment("speaker_2", 2_500, 4_500)),
            segments.sortedBy { it.startMs },
        )
        assertEquals(2, ids.size)
    }

    @Test fun `номера по времени появления, а не по тому, кто раньше замолчал`() {
        // Столбец 3 говорит с 0 до 60 с, столбец 1 вставляет реплику на 10 с
        val data = probs(6000, 3 to 0..5999, 1 to 1000..1100)
        val tracker = SegmentTracker()
        val updates = (0 until 6000 step 500).map { tracker.feed(data, it, it + 500) } + tracker.finish()
        val found = updates.flatMap { it.newSpeakers }
        assertEquals(listOf(FoundSpeaker("speaker_1", 0), FoundSpeaker("speaker_2", 10_000)), found)
        assertTrue(updates.last().final)
        assertEquals(Segment("speaker_1", 0, 60_000), updates.last().segments.single())
    }

    @Test fun `реплики получают те же номера, что голоса`() {
        val data = probs(500, 5 to 0..199, 2 to 250..499)
        val result = SegmentTracker()
        result.feed(data, 0, 500)
        result.finish()
        val words = listOf(Word("раз", 500, 800), Word("два", 3_000, 3_300))
        val utterances = TranscriptMerger.merge(words, FrameProbs(data), result.speakers)
        assertEquals(listOf("speaker_1", "speaker_2"), utterances.map { it.speakerId })
    }

    @Test fun `порции дают тот же результат, что всё сразу`() {
        for (ref in Reference.all()) {
            val probs = FrameProbs(ref.floats("probs"))
            val whole = SegmentTracker.segmentsOf(probs).sortedWith(compareBy({ it.startMs }, { it.speakerId }))
            val tracker = SegmentTracker()
            val chunk = Nemotron.CHUNK_LENGTH * Nemotron.SUBSAMPLING // как в диаризаторе
            var from = 0
            val found = ArrayList<FoundSpeaker>()
            while (from < probs.frames) {
                val to = minOf(from + chunk, probs.frames)
                found += tracker.feed(probs.data, from, to).newSpeakers
                from = to
            }
            tracker.finish()
            val parts = tracker.segments.sortedWith(compareBy({ it.startMs }, { it.speakerId }))
            assertEquals(ref.name, whole, parts)
            assertEquals(ref.name, found.map { it.firstAtMs }.sorted(), found.map { it.firstAtMs })
            println("${ref.name}: голосов ${found.size}, отрезков ${parts.size}")
        }
    }

    @Test fun `диаризатор отдаёт голоса и отрезки по ходу обработки`() {
        val ref = Reference.load("dialog_10min")
        val updates = ArrayList<DiarizationUpdate>()
        val result = NemotronDiarizerTest.diarizer.diarizeWithSegments(ref.floats("audio")) { updates += it }
        assertTrue("порций ${updates.size}", updates.size > 5)
        assertTrue(updates.last().final)
        assertTrue(updates.dropLast(1).none { it.final })
        assertTrue(updates.zipWithNext().all { (a, b) -> a.processedUntilMs <= b.processedUntilMs })
        assertEquals(result.segments.toSet(), updates.flatMap { it.segments }.toSet())
        val found = updates.flatMap { it.newSpeakers }
        assertEquals(List(found.size) { "speaker_${it + 1}" }, found.map { it.speakerId })
        assertEquals(result.speakers.size, found.size)
        // Первый голос появляется задолго до конца обработки
        val firstUpdate = updates.indexOfFirst { it.newSpeakers.isNotEmpty() }
        assertTrue(updates[firstUpdate].processedUntilMs < 60_000)

        val utterances = TranscriptMerger.merge(ref.words(), result.probs, result.speakers)
        assertTrue(utterances.map { it.speakerId }.toSet().all { id -> found.any { it.speakerId == id } })
        println("dialog_10min: порций ${updates.size}, голосов ${found.size} (" +
            found.joinToString { "${it.speakerId} с ${it.firstAtMs / 1000} с" } + "), отрезков ${result.segments.size}, " +
            "перекрытий ${overlaps(result.segments)}")
    }

    private fun overlaps(segments: List<Segment>): Int =
        segments.indices.sumOf { i ->
            (i + 1 until segments.size).count { j ->
                val a = segments[i]
                val b = segments[j]
                a.speakerId != b.speakerId && a.startMs < b.endMs && b.startMs < a.endMs
            }
        }
}
