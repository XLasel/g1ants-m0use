package ru.stenographer.diarization

import ru.stenographer.core.Word
import ru.stenographer.core.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptMergerTest {
    /** Спикер [speaker] говорит в кадрах [range] (по 10 мс). */
    private fun probs(frames: Int, vararg turns: Pair<Int, IntRange>): FrameProbs {
        val data = FloatArray(frames * NUM_SPEAKERS)
        for ((speaker, range) in turns) for (f in range) data[f * NUM_SPEAKERS + speaker] = 0.9f
        return FrameProbs(data)
    }

    private fun word(text: String, startMs: Long, endMs: Long = startMs + 300) = Word(text, startMs, endMs)

    @Test fun `слова получают спикера по времени и собираются в реплики`() {
        val p = probs(600, 0 to 0..249, 1 to 300..599)
        val words = listOf(word("Привет,", 100), word("как", 1_200), word("дела?", 1_600), word("Хорошо.", 3_500))
        val utterances = TranscriptMerger.merge(words, p, SpeakerIds())
        assertEquals(2, utterances.size)
        assertEquals("speaker_1", utterances[0].speakerId)
        assertEquals("Привет, как дела?", utterances[0].text)
        assertEquals(100L, utterances[0].startMs)
        assertEquals(1_900L, utterances[0].endMs)
        assertEquals("speaker_2", utterances[1].speakerId)
        assertEquals("Хорошо.", utterances[1].text)
    }

    @Test fun `одиночное чужое слово внутри плотной речи сглаживается`() {
        // Слово «нет» попадает на короткий всплеск спикера 2, но вокруг плотная речь спикера 1
        val data = FloatArray(500 * NUM_SPEAKERS)
        for (f in 0 until 500) data[f * NUM_SPEAKERS] = if (f in 150..179) 0.4f else 0.9f
        for (f in 150..179) data[f * NUM_SPEAKERS + 1] = 0.8f
        val words = listOf(word("я", 1_000, 1_400), word("нет", 1_500, 1_800), word("знаю", 1_900, 2_300))
        val raw = TranscriptMerger.assignSpeakers(words, FrameProbs(data), smooth = false)
        assertEquals(1, raw[1].speaker)
        val smooth = TranscriptMerger.assignSpeakers(words, FrameProbs(data))
        assertTrue(smooth.all { it.speaker == 0 })
    }

    @Test fun `слова в тишине берут спикера соседей`() {
        val p = probs(1000, 1 to 0..299)
        val words = listOf(word("раз", 500), word("два", 900), word("три", 9_000))
        assertTrue(TranscriptMerger.assignSpeakers(words, p).all { it.speaker == 1 })
    }

    @Test fun `пустой список слов`() {
        assertEquals(emptyList<Utterance>(), TranscriptMerger.merge(emptyList(), probs(10), SpeakerIds()))
    }

    @Test fun `реплики совпадают с Python на вероятностях Python`() {
        for (ref in Reference.all().filter { it.hasWords() }) {
            val actual = TranscriptMerger.turns(ref.words(), FrameProbs(ref.floats("probs")))
            val expected = ref.utterances()
            assertEquals("${ref.name}: число реплик", expected.length(), actual.size)
            for (i in actual.indices) {
                val e = expected.getJSONObject(i)
                val u = actual[i]
                val where = "${ref.name}, реплика $i"
                assertEquals("$where: спикер", e.getInt("speaker") - 1, u.speaker)
                assertEquals("$where: начало", Reference.toMs(e.getDouble("start")), u.startMs)
                assertEquals("$where: конец", Reference.toMs(e.getDouble("end")), u.endMs)
                assertEquals("$where: слов", e.getJSONArray("words").length(), u.words.size)
            }
            println("${ref.name}: ${actual.size} реплик совпали с Python")
        }
    }

    @Test fun `вся цепочка на Kotlin даёт тех же спикеров у слов`() {
        val ref = Reference.load("dialog_10min")
        val words = ref.words()
        val probs = NemotronDiarizerTest.diarizer.diarize(ref.floats("audio"))
        val actual = TranscriptMerger.turns(words, probs).flatMap { it.words }
        val expected = ref.utterances().let { a ->
            (0 until a.length()).flatMap { i ->
                val ws = a.getJSONObject(i).getJSONArray("words")
                (0 until ws.length()).map { ws.getJSONObject(it).getInt("speaker") - 1 }
            }
        }
        assertEquals(expected.size, actual.size)
        val same = actual.indices.count { actual[it].speaker == expected[it] }
        println("dialog_10min: слов с тем же спикером $same из ${actual.size}")
        assertTrue("$same из ${actual.size}", same >= actual.size * 0.995)
    }
}
