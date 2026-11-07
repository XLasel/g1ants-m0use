package ru.stenographer.diarization

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptJsonTest {
    private fun utterance(speaker: Int, start: Long, end: Long, text: String) =
        Utterance(speaker, start, end, text.split(" ").map { SpeakerWord(it, start, end, speaker, 0.9f) })

    @Test fun `спикеры нумеруются по первой реплике без пропусков`() {
        val renumbered = listOf(utterance(3, 5_000, 6_000, "б"), utterance(5, 0, 1_000, "а"), utterance(3, 7_000, 8_000, "в"))
            .renumberSpeakers()
        assertEquals(listOf("speaker_2", "speaker_1", "speaker_2"), renumbered.map { it.speakerId })
        assertEquals(listOf(1), renumbered[0].words.map { it.speaker })
    }

    @Test fun `формат задания и экранирование`() {
        val json = TranscriptJson.encode(listOf(
            utterance(1, 4_000, 5_000, "Второй \"в кавычках\""),
            utterance(0, 1_250, 4_380, "Добрый день!"),
        ))
        val transcript = JSONObject(json).getJSONArray("transcript")
        assertEquals(2, transcript.length())
        val first = transcript.getJSONObject(0)
        assertEquals("speaker_1", first.getString("speaker_id"))
        assertEquals(1_250, first.getInt("start_ms"))
        assertEquals(4_380, first.getInt("end_ms"))
        assertEquals("Добрый день!", first.getString("text"))
        assertEquals("Второй \"в кавычках\"", transcript.getJSONObject(1).getString("text"))
        assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), first.keySet())
    }

    @Test fun `пустая расшифровка`() {
        assertEquals(0, JSONObject(TranscriptJson.encode(emptyList())).getJSONArray("transcript").length())
    }

    @Test fun `управляющие символы экранируются`() {
        assertEquals("\"a\\nb\\u0001\"", TranscriptJson.quote("a\nb\u0001"))
    }
}
