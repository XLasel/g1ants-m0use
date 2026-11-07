package ru.stenographer.summary

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class LocalSummarizerTest {
    private val input = listOf(
        SourceUtterance("speaker_1", 1250, 4000, "Обсудим выпуск проекта."),
        SourceUtterance("speaker_2", 8000, 12000, "Я проверю сборку к пятнице."),
    )

    private class Engine(
        override val contextSize: Int = 4096,
        val tokenCount: (String) -> Int = { 200 + it.length / 4 },
        val reply: (GenerationRequest) -> String,
    ) : TextGenerator {
        val requests = mutableListOf<GenerationRequest>()
        override fun countTokens(system: String, user: String): Int = tokenCount(user)
        override fun generate(request: GenerationRequest): String {
            requests += request
            return reply(request)
        }
    }

    private fun answer(id: Int = 1, title: String = "Проверка сборки к пятнице") =
        """{"title":"$title","topic":{"source_id":0},"decisions":[],"tasks":[{"source_id":$id}]}"""

    private fun expectFailure(response: String) {
        val engine = Engine { response }
        assertThrows(SummaryException::class.java) { LocalSummarizer(engine).summarize(input) }
    }

    @Test fun linksComeFromOriginalTimestamps() {
        val engine = Engine { answer() }
        val result = LocalSummarizer(engine).summarize(input)
        assertEquals("Проверка сборки к пятнице", result.title)
        assertEquals(listOf(1250L, 8000L), result.points.map { it.sourceStartMs })
        assertTrue(result.text.contains("Задачи\n• Я проверю сборку к пятнице."))
        assertEquals(1, engine.requests.size)
    }

    @Test fun transcriptIsNotChanged() {
        val snapshot = input.toList()
        LocalSummarizer(Engine { answer() }).summarize(input)
        assertEquals(snapshot, input)
    }

    @Test fun titleQuotesAndFinalPeriodAreRemoved() {
        val result = LocalSummarizer(Engine { answer(title = "«Проверка сборки к пятнице». ") }).summarize(input)
        assertEquals("Проверка сборки к пятнице", result.title)
    }

    @Test fun titleMustHaveThreeToSixWords() {
        expectFailure(answer(title = "Проект"))
        expectFailure(answer(title = "Раз два три четыре пять шесть семь"))
    }

    @Test fun inventedSourceIdsAreRejected() { expectFailure(answer(id = 500)) }
    @Test fun modelCannotReplaceSourceText() {
        expectFailure(answer().replace("\"source_id\":1", "\"source_id\":1,\"text\":\"Выдуманный срок\""))
    }
    @Test fun identicalTextFromDifferentTurnsKeepsItsOwnTime() {
        val sameText = input.map { it.copy(text = "Проверим сборку.") }
        val result = LocalSummarizer(Engine { answer() }).summarize(sameText)
        assertEquals(listOf(1250L, 8000L), result.points.map { it.sourceStartMs })
        assertTrue(result.points.all { it.text == "Проверим сборку." })
    }
    @Test fun fractionalSourceIdIsRejected() { expectFailure(answer().replace("\"source_id\":1", "\"source_id\":1.0")) }
    @Test fun generatedTimestampsAreRejected() { expectFailure(answer().replace("\"source_id\":1", "\"source_id\":1,\"sourceStartMs\":999999")) }
    @Test fun trailingTextIsRejected() { expectFailure(answer() + " explanation") }
    @Test fun truncatedJsonIsRejected() { expectFailure(answer().dropLast(1)) }
    @Test fun absentDecisionsAndTasksAreNotInvented() {
        val response = """{"title":"Обсуждение выпуска нового проекта","topic":{"source_id":0},"decisions":[],"tasks":[]}"""
        val result = LocalSummarizer(Engine { response }).summarize(input)
        assertEquals(1, result.points.size)
        assertTrue(result.text.endsWith("Задачи\n—"))
    }

    @Test fun noSpeechDoesNotRunModel() {
        val engine = Engine { answer() }
        assertThrows(SummaryException::class.java) { LocalSummarizer(engine).summarize(emptyList()) }
        assertTrue(engine.requests.isEmpty())
    }

    @Test fun cancellationStopsBeforeGeneration() {
        val engine = Engine { answer() }
        assertThrows(CancellationException::class.java) { LocalSummarizer(engine).summarize(input, isCancelled = { true }) }
        assertTrue(engine.requests.isEmpty())
    }

    @Test fun cancellationAfterGenerationDoesNotPublish() {
        var cancelled = false
        val engine = Engine { cancelled = true; answer() }
        assertThrows(CancellationException::class.java) { LocalSummarizer(engine).summarize(input, isCancelled = { cancelled }) }
    }

    @Test fun progressIsMonotoneAndFinishesAtOne() {
        val progress = mutableListOf<Float>()
        LocalSummarizer(Engine { answer() }).summarize(input, onProgress = { progress += it })
        assertEquals(0f, progress.first())
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test fun malformedInputIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalSummarizer(Engine { answer() }).summarize(listOf(input[0].copy(startMs = -1)))
        }
    }

    @Test fun longInputIsReducedWithoutLosingSourceIdentity() {
        val engine = Engine(contextSize = 900, tokenCount = { 200 + it.length / 2 }) { request ->
            val rows = JSONObject(request.user).getJSONArray("source_fragments")
            val id = rows.getJSONObject(0).getInt("source_id")
            """{"title":"Обсуждение выпуска нового проекта","topic":{"source_id":$id},"decisions":[],"tasks":[]}"""
        }
        val longInput = (0 until 5).map { id -> SourceUtterance("speaker_1", id * 1000L, id * 1000L + 900, "Содержательная реплика о проекте. ".repeat(8)) }
        val progress = mutableListOf<Float>()
        val result = LocalSummarizer(engine, maxOutputTokens = 128).summarize(longInput, onProgress = { progress += it })
        assertTrue(engine.requests.size > 1)
        assertTrue(result.points.all { point -> longInput.any { it.startMs == point.sourceStartMs } })
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(engine.requests.all { engine.countTokens(it.system, it.user) + it.maxTokens + 32 <= engine.contextSize })
    }

    @Test fun oversizedSingleUtteranceIsSplitInsteadOfTruncated() {
        val seen = mutableListOf<String>()
        val engine = Engine(contextSize = 900, tokenCount = { 200 + it.length / 2 }) { request ->
            val rows = JSONObject(request.user).getJSONArray("source_fragments")
            for (i in 0 until rows.length()) seen += rows.getJSONObject(i).getString("text")
            val id = rows.getJSONObject(0).getInt("source_id")
            """{"title":"Обсуждение выпуска нового проекта","topic":{"source_id":$id},"decisions":[],"tasks":[]}"""
        }
        val text = "Начало " + "проект ".repeat(500) + "Конец"
        LocalSummarizer(engine, maxOutputTokens = 128).summarize(listOf(input[0].copy(text = text)))
        assertTrue(seen.any { it.contains("Начало") })
        assertTrue(seen.any { it.contains("Конец") })
    }

    @Test fun generationBudgetDoesNotSilentlyReturnPartialSummary() {
        val engine = Engine(contextSize = 900, tokenCount = { 200 + it.length / 2 }) { request ->
            val id = JSONObject(request.user).getJSONArray("source_fragments").getJSONObject(0).getInt("source_id")
            """{"title":"Обсуждение выпуска нового проекта","topic":{"source_id":$id},"decisions":[],"tasks":[]}"""
        }
        val text = "Начало " + "проект ".repeat(500) + "Конец"
        assertThrows(SummaryException::class.java) {
            LocalSummarizer(engine, maxOutputTokens = 128, maxCalls = 1).summarize(listOf(input[0].copy(text = text)))
        }
    }
}
