package ru.stenographer.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.stenographer.app.export.TranscriptExport
import ru.stenographer.core.SummaryPoint
import ru.stenographer.core.Utterance

/** Логика записи без телефона: объединение голосов, номера в JSON задания, разбор конспекта. */
class RecordingLogicTest {

    private fun rec(vararg u: Utterance, summary: Summary? = null) = Recording(
        id = "r",
        title = "Тест",
        createdAt = 0,
        audioPath = "",
        utterances = u.toList(),
        speakers = u.map { it.speakerId }.distinct().mapIndexed { i, id -> Speaker(id, "Голос ${i + 1}", i) },
        summary = summary,
    )

    @Test
    fun mergeJoinsNeighbourRepliesOfOneVoice() {
        val r = rec(
            Utterance("speaker_1", 0, 1_000, "Привет."),
            Utterance("speaker_2", 1_500, 2_500, "Это я."),
            Utterance("speaker_3", 5_000, 6_000, "Пока."),
        ).mergeSpeaker(from = "speaker_2", into = "speaker_1")

        assertEquals(listOf("speaker_1", "speaker_3"), r.speakers.map { it.id })
        assertEquals(2, r.utterances.size)
        assertEquals(Utterance("speaker_1", 0, 2_500, "Привет. Это я."), r.utterances[0])
    }

    @Test
    fun mergeKeepsLongPauseAsSeparateReplies() {
        val r = rec(
            Utterance("speaker_1", 0, 1_000, "Раз."),
            Utterance("speaker_2", 4_000, 5_000, "Два."),
        ).mergeSpeaker("speaker_2", "speaker_1")
        assertEquals(2, r.utterances.size)
    }

    @Test
    fun exportRenumbersVoicesByFirstAppearance() {
        val r = rec(
            Utterance("speaker_1", 0, 1_000, "А"),
            Utterance("speaker_3", 1_000, 2_000, "Б"),
        )
        val json = TranscriptExport.toJson(r)
        assert("\"speaker_2\"" in json) { json }
        assert("speaker_3" !in json) { json }
        assert("\"start_ms\": 1000" in json) { json }
    }

    @Test
    fun summarySectionsTakeTimeAndVoiceFromTranscript() {
        val summary = Summary(
            title = "План",
            text = "Тема\n• Обсуждаем план.\n\nРешения\n—\n\nЗадачи\n• Сделаю к пятнице.",
            points = listOf(SummaryPoint("Обсуждаем план.", 0), SummaryPoint("Сделаю к пятнице.", 3_000)),
        )
        val r = rec(
            Utterance("speaker_1", 0, 2_000, "Обсуждаем план."),
            Utterance("speaker_2", 3_000, 4_000, "Сделаю к пятнице."),
            summary = summary,
        )
        val sections = summarySections(r)!!
        assertEquals(listOf("О чём разговор", "Кто что сделает"), sections.map { it.title })
        assertEquals(3_000L, sections[1].items.single().atMs)
        assertEquals("speaker_2", sections[1].items.single().speakerId)
    }

    @Test
    fun unknownSummaryFormatFallsBackToPlainText() {
        val r = rec(Utterance("speaker_1", 0, 1_000, "А"), summary = Summary("Т", "Просто пересказ.", emptyList()))
        assertNull(summarySections(r))
    }
}
