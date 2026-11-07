package ru.stenographer.app.pipeline

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import ru.stenographer.core.PipelineEvent
import ru.stenographer.pipeline.PipelineConfig
import ru.stenographer.pipeline.TranscriptionPipeline
import java.io.File

/**
 * Модели на телефоне и запуск обработки.
 *
 * Модели лежат в APK, а нативному коду нужны обычные файлы, поэтому при первом запуске их надо скопировать.
 * Начинаем сразу при открытии приложения, пока человек выбирает файлы: так первая обработка, которую
 * и замеряет жюри, не тратит на это время. Обработка ждёт окончания копирования, чтобы не копировать дважды.
 */
class LocalModels(private val context: Context, scope: CoroutineScope) {

    private val ready: Deferred<Unit> = scope.async(Dispatchers.IO) {
        runCatching { TranscriptionPipeline(context).installModels() }
            .onFailure { Log.w(TAG, "Модели не скопировались заранее, обработка попробует сама", it) }
        Unit
    }

    /** Положена ли в APK модель для названия и конспекта (scripts/prepare_summary.sh). */
    val summaryBundled: Boolean by lazy {
        runCatching { context.assets.list("models/summary").orEmpty().any { it.endsWith(".gguf") } }.getOrDefault(false)
    }

    /** Обработка одной записи; начинается, когда модели уже скопированы. */
    fun process(recordingId: String, audio: File, withSummary: Boolean): Flow<PipelineEvent> = flow {
        ready.await()
        val config = PipelineConfig(summaryEnabled = withSummary)
        emitAll(TranscriptionPipeline(context, config).process(recordingId, Uri.fromFile(audio)))
    }

    private companion object {
        const val TAG = "LocalModels"
    }
}
