package ru.stenographer.diarization

import ru.stenographer.core.SAMPLE_RATE
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlannerTest {
    private fun probsWithSpeech(frames: Int, vararg speech: IntRange): FrameProbs {
        val data = FloatArray(frames * NUM_SPEAKERS)
        for (range in speech) for (f in range) data[f * NUM_SPEAKERS] = 0.9f
        return FrameProbs(data)
    }

    @Test fun `тишина не попадает в куски`() {
        val probs = probsWithSpeech(1000, 100..199, 700..799) // речь 1–2 с и 7–8 с
        val segments = ChunkPlanner.speechSegments(ChunkPlanner.speechActivity(probs))
        assertEquals(2, segments.size)
        assertEquals(0.75, segments[0].start, 1e-9)
        assertEquals(2.25, segments[0].end, 1e-9)
        assertEquals(6.75, segments[1].start, 1e-9)
    }

    @Test fun `близкие отрезки склеиваются, всплески отбрасываются`() {
        val probs = probsWithSpeech(1000, 100..199, 230..299, 600..604)
        val segments = ChunkPlanner.speechSegments(ChunkPlanner.speechActivity(probs))
        assertEquals(listOf(Span(0.75, 3.25)), segments)
    }

    @Test fun `длинная речь режется не длиннее предела`() {
        val seconds = 95
        val probs = probsWithSpeech(seconds * 100, 0 until seconds * 100)
        val audio = FloatArray(seconds * SAMPLE_RATE) { 0.3f }
        val chunks = ChunkPlanner.plan(probs, audio)
        assertTrue(chunks.all { it.last - it.first <= 20_000 })
        assertEquals(0L, chunks.first().first)
        assertEquals(seconds * 1000L, chunks.last().last)
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.last, b.first) }
    }

    @Test fun `куски совпадают с Python`() {
        for (ref in Reference.all()) {
            val probs = FrameProbs(ref.floats("probs"))
            val expected = JSONObject(ref.json("segments"))
            val segments = ChunkPlanner.speechSegments(ChunkPlanner.speechActivity(probs))
            assertSpans("${ref.name}: отрезки речи", expected.getJSONArray("segments"), segments)
            val chunks = ChunkPlanner.planChunks(segments, ref.floats("audio"), maxChunk = 20.0)
            assertSpans("${ref.name}: куски", expected.getJSONArray("chunks"), chunks)
            println("${ref.name}: отрезков ${segments.size}, кусков ${chunks.size}")
        }
    }

    private fun assertSpans(message: String, expected: JSONArray, actual: List<Span>) {
        assertEquals("$message: число", expected.length(), actual.size)
        for (i in actual.indices) {
            val e = expected.getJSONArray(i)
            assertEquals("$message $i: начало", e.getDouble(0), actual[i].start, 1e-9)
            assertEquals("$message $i: конец", e.getDouble(1), actual[i].end, 1e-9)
        }
    }
}
