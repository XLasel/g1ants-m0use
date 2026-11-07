package ru.stenographer.summary.android

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import ru.stenographer.summary.GenerationRequest
import ru.stenographer.summary.SourceUtterance
import ru.stenographer.summary.TextGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SummaryWorkerTest {
    private val source = listOf(SourceUtterance("speaker_1", 1000, 2000, "Обсудим сборку проекта."))
    private val answer = """{"title":"Обсуждение сборки нашего проекта","topic":{"text":"Сборка проекта","source_id":0},"decisions":[],"tasks":[]}"""

    private class Generator(val action: () -> String) : TextGenerator {
        override val contextSize = 4096
        val closed = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        override fun countTokens(system: String, user: String) = 200
        override fun generate(request: GenerationRequest) = action()
        override fun cancel() { cancelled.set(true) }
        override fun close() { closed.countDown() }
    }

    @Test fun modelIsClosedAfterSuccess() = runBlocking {
        val generator = Generator { answer }
        val result = summarizeOnWorker(source) { generator }
        assertEquals("Обсуждение сборки нашего проекта", result.title)
        assertTrue(generator.closed.await(2, TimeUnit.SECONDS))
    }

    @Test fun modelIsClosedAfterFailure() {
        val generator = Generator { throw IllegalStateException("inference failed") }
        assertThrows(IllegalStateException::class.java) { runBlocking { summarizeOnWorker(source) { generator } } }
        assertTrue(generator.closed.await(2, TimeUnit.SECONDS))
    }

    @Test fun cancellationSignalsRunningInferenceAndClosesModel() = runBlocking {
        val started = CountDownLatch(1)
        val generator = Generator {
            started.countDown()
            Thread.sleep(5000)
            answer
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { summarizeOnWorker(source) { generator } }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        withTimeout(2000) { job.cancelAndJoin() }
        assertTrue(generator.cancelled.get())
        assertTrue(generator.closed.await(2, TimeUnit.SECONDS))
    }

    @Test fun cancellingQueuedRequestDoesNotCancelAnotherFile() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = Generator { started.countDown(); release.await(3, TimeUnit.SECONDS); answer }
        val secondWasOpened = AtomicBoolean(false)
        val a = launch(start = CoroutineStart.UNDISPATCHED) { summarizeOnWorker(source) { first } }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val b = launch(start = CoroutineStart.UNDISPATCHED) {
            summarizeOnWorker(source) { secondWasOpened.set(true); Generator { answer } }
        }
        withTimeout(2000) { b.cancelAndJoin() }
        assertFalse(first.cancelled.get())
        release.countDown()
        withTimeout(2000) { a.join() }
        assertTrue(first.closed.await(2, TimeUnit.SECONDS))
        assertFalse(secondWasOpened.get())
    }
}
