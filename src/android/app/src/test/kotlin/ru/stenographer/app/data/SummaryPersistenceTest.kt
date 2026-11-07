package ru.stenographer.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenographer.core.SummaryPoint
import ru.stenographer.core.Utterance

/** Проверяет сохранение новых ссылок конспекта и чтение записей прежнего формата. */
class SummaryPersistenceTest {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private fun recording(points: List<SummaryPoint>, text: String = "Конспект") = Recording(
        id = "r", title = "Проверка плана выпуска", createdAt = 0, audioPath = "recording.m4a",
        utterances = listOf(
            Utterance("speaker_1", 0, 2_000, "Обсуждаем план."),
            Utterance("speaker_2", 1_000, 3_000, "Я проверю сборку."),
        ),
        speakers = listOf(Speaker("speaker_1", "Анна", 0), Speaker("speaker_2", "Игорь", 1)),
        summary = Summary("Проверка плана выпуска", text, points),
    )

    private fun reload(recording: Recording): Recording = json.decodeFromString(
        Recording.serializer(), json.encodeToString(Recording.serializer(), recording),
    )

    @Test fun recordingRoundTripPreservesSummaryMetadata() {
        val source = recording(listOf(
            SummaryPoint("Обсуждаем план.", 0, "topic", "speaker_1"),
            SummaryPoint("Я проверю сборку.", 1_000, "task", "speaker_2"),
        ))
        assertEquals(source, reload(source))
    }

    @Test fun oldPointWithoutMetadataStillLoads() {
        val point = json.decodeFromString(
            SummaryPointSerializer, """{"text":"Старая фраза.","sourceStartMs":1500}""",
        )
        assertEquals("topic", point.kind)
        assertNull(point.speakerId)
        assertEquals(1_500L, point.sourceStartMs)
    }

    @Test fun oldRecordingStillBuildsSectionsAfterReload() {
        val source = recording(
            listOf(SummaryPoint("Я проверю сборку.", 1_000)),
            "Тема\n—\n\nРешения\n—\n\nЗадачи\n• Я проверю сборку.",
        )
        assertEquals(summarySections(source), summarySections(reload(source)))
        assertEquals("Кто что сделает", summarySections(reload(source))!!.single().title)
        assertEquals("speaker_2", summarySections(reload(source))!!.single().items.single().speakerId)
    }

    @Test fun repeatedTextKeepsItsSectionTimeAndVoiceAfterReload() {
        val source = recording(listOf(
            SummaryPoint("Согласен.", 0, "topic", "speaker_1"),
            SummaryPoint("Согласен.", 1_000, "task", "speaker_2"),
            SummaryPoint("Согласен.", 2_000, "decision", "speaker_1"),
        )).copy(utterances = listOf(
            Utterance("speaker_1", 0, 900, "Согласен."),
            Utterance("speaker_2", 1_000, 1_900, "Согласен."),
            Utterance("speaker_1", 2_000, 2_900, "Согласен."),
        ))
        val sections = summarySections(reload(source))!!
        assertEquals(listOf("О чём разговор", "Договорились", "Кто что сделает"), sections.map { it.title })
        assertEquals(listOf(0L, 2_000L, 1_000L), sections.map { it.items.single().atMs })
        assertEquals(listOf("speaker_1", "speaker_1", "speaker_2"), sections.map { it.items.single().speakerId })
    }

    @Test fun explicitVoiceWinsWhenUtterancesOverlap() {
        val source = recording(listOf(SummaryPoint("Я проверю сборку.", 1_000, "task", "speaker_2")))
        // В этот момент звучит и speaker_1: поиск только по времени выбрал бы его.
        assertEquals("speaker_2", summarySections(reload(source))!!.single().items.single().speakerId)
    }

    @Test fun speakerMergeUpdatesSummaryAttribution() {
        val source = recording(listOf(SummaryPoint("Я проверю сборку.", 1_000, "task", "speaker_2")))
        val merged = reload(source.mergeSpeaker("speaker_2", "speaker_1"))
        assertEquals(SummaryPoint("Я проверю сборку.", 1_000, "task", "speaker_1"), merged.summary!!.points.single())
        assertEquals("speaker_1", summarySections(merged)!!.single().items.single().speakerId)
        assertTrue(summaryText(merged)!!.contains("Анна: Я проверю сборку."))
    }

    @Test fun editedSpeakerNameSurvivesReloadInSummaryText() {
        val source = recording(listOf(SummaryPoint("Я проверю сборку.", 1_000, "task", "speaker_2")))
        val renamed = source.copy(speakers = source.speakers.map {
            if (it.id == "speaker_2") it.copy(name = "Проверяющий") else it
        })
        assertTrue(summaryText(reload(renamed))!!.contains("Проверяющий: Я проверю сборку."))
    }

    @Test fun lateSummaryUsesAlreadyMergedVoice() {
        val source = recording(emptyList()).mergeSpeaker("speaker_2", "speaker_1")
        val late = source.copy(summary = Summary("Проверка плана выпуска", "Конспект",
            listOf(SummaryPoint("Я проверю сборку.", 1_000, "task", "speaker_2"))))
        assertEquals("speaker_1", summarySections(reload(late))!!.single().items.single().speakerId)
        assertTrue(summaryText(reload(late))!!.contains("Анна: Я проверю сборку."))
    }
}
