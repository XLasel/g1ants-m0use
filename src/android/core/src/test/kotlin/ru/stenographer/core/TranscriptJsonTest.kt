package ru.stenographer.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptJsonTest {
    @Test fun `формат задания и экранирование`() {
        val json = TranscriptJson.encode(listOf(
            Utterance("speaker_2", 4_000, 5_000, "Второй \"в кавычках\""),
            Utterance("speaker_1", 1_250, 4_380, "Добрый день!", listOf(Word("Добрый", 1_250, 1_700))),
        ))
        val transcript = JSONObject(json).getJSONArray("transcript")
        assertEquals(2, transcript.length())
        val first = transcript.getJSONObject(0)
        assertEquals("speaker_1", first.getString("speaker_id"))
        assertEquals(1_250, first.getInt("start_ms"))
        assertEquals(4_380, first.getInt("end_ms"))
        assertEquals("Добрый день!", first.getString("text"))
        assertEquals("Второй \"в кавычках\"", transcript.getJSONObject(1).getString("text"))
        // Слова в JSON задания не попадают
        assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), first.keySet())
    }

    @Test fun `пустая расшифровка`() {
        assertEquals(0, JSONObject(TranscriptJson.encode(emptyList())).getJSONArray("transcript").length())
    }

    @Test fun `управляющие символы экранируются`() {
        assertEquals("\"a\\nb\\u0001\"", TranscriptJson.quote("a\nb\u0001"))
    }

    @Test fun `события с огибающей сравниваются по содержимому`() {
        assertEquals(
            PipelineEvent.Decoded("f", 1_000, floatArrayOf(0.1f, 0.5f)),
            PipelineEvent.Decoded("f", 1_000, floatArrayOf(0.1f, 0.5f)),
        )
    }
}
