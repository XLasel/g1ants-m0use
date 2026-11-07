package ru.stenographer.summary.android

import kotlinx.coroutines.suspendCancellableCoroutine
import ru.stenographer.summary.LocalSummarizer
import ru.stenographer.summary.SourceUtterance
import ru.stenographer.summary.SummaryResult
import ru.stenographer.summary.TextGenerator
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val worker = Executors.newSingleThreadExecutor { task ->
    Thread(task, "LocalSummary").apply { isDaemon = true }
}

/** Загружает локальный GGUF, генерирует конспект и освобождает модель. Отмена coroutine прерывает decode. */
suspend fun summarizeLocal(
    model: File,
    utterances: List<SourceUtterance>,
    contextSize: Int = 4096,
    threads: Int = 4,
    onProgress: (Float) -> Unit = {},
): SummaryResult = summarizeOnWorker(utterances, onProgress) {
    LlamaTextGenerator.open(model, contextSize, threads)
}

internal suspend fun summarizeOnWorker(
    utterances: List<SourceUtterance>,
    onProgress: (Float) -> Unit = {},
    factory: () -> TextGenerator,
): SummaryResult = suspendCancellableCoroutine { continuation ->
    val source = utterances.toList()
    val cancelled = AtomicBoolean(false)
    val running = AtomicReference<TextGenerator?>(null)
    val scheduled = AtomicReference<Future<*>?>(null)
    continuation.invokeOnCancellation {
        cancelled.set(true)
        running.get()?.cancel()
        scheduled.get()?.cancel(true)
    }
    val future = worker.submit {
        try {
            if (cancelled.get()) return@submit
            val result = factory().use { generator ->
                running.set(generator)
                try {
                    LocalSummarizer(generator).summarize(source, onProgress) { cancelled.get() }
                } finally {
                    running.set(null)
                }
            } // Освободить модель до возобновления pipeline и загрузки моделей следующего файла.
            if (continuation.isActive) continuation.resume(result)
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
    scheduled.set(future)
    if (cancelled.get()) future.cancel(true)
}
