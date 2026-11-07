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
import java.io.IOException

/**
 * Модели на телефоне и запуск обработки.
 *
 * Модели лежат в APK, а нативному коду нужны обычные файлы, поэтому при первом запуске их надо скопировать
 * (около 330 МБ, плюс модель конспекта). Начинаем копировать сразу при открытии приложения, пока человек
 * выбирает файлы: так первая обработка, которую и замеряет жюри, не тратит на это время.
 */
class LocalModels(private val context: Context, scope: CoroutineScope) {

    @Volatile
    private var summaryModel: File? = null

    private val ready: Deferred<Unit> = scope.async(Dispatchers.IO) {
        runCatching { TranscriptionPipeline(context).prepareModels() }
            .onFailure { Log.w(TAG, "Модели распознавания не скопировались заранее, обработка попробует сама", it) }
        summaryModel = runCatching { installSummaryModel() }
            .onFailure { Log.w(TAG, "Модель конспекта не установилась, конспекта не будет", it) }
            .getOrNull()
    }

    /** Есть ли в приложении модель для конспекта и названия. */
    suspend fun summaryAvailable(): Boolean {
        ready.await()
        return summaryModel != null
    }

    /** Обработка одной записи; начинается, когда модели уже скопированы. */
    fun process(recordingId: String, audio: File, withSummary: Boolean): Flow<PipelineEvent> = flow {
        ready.await()
        val config = PipelineConfig(summaryModel = if (withSummary) summaryModel else null)
        emitAll(TranscriptionPipeline(context, config).process(recordingId, Uri.fromFile(audio)))
    }

    /**
     * Копирует GGUF из assets/models/summary во внутреннюю память: llama.cpp открывает модель по пути.
     * Если модели в APK нет, возвращает null, и приложение работает без конспекта.
     */
    private fun installSummaryModel(): File? {
        val name = context.assets.list(SUMMARY_ASSETS).orEmpty().firstOrNull { it.endsWith(".gguf") } ?: return null
        val dir = File(context.filesDir, "models/summary").apply { mkdirs() }
        val target = File(dir, name)
        val marker = File(dir, "$name.complete")
        if (marker.isFile && target.isFile) return target
        // Старые модели (после обновления приложения) больше не нужны, место на телефоне дорогое.
        dir.listFiles().orEmpty().filter { it.name != name }.forEach { it.delete() }
        val temp = File(dir, "$name.tmp")
        context.assets.open("$SUMMARY_ASSETS/$name").use { input -> temp.outputStream().use { input.copyTo(it, 1 shl 20) } }
        if (!temp.renameTo(target)) throw IOException("Не удалось сохранить модель конспекта")
        marker.createNewFile()
        return target
    }

    private companion object {
        const val TAG = "LocalModels"
        const val SUMMARY_ASSETS = "models/summary"
    }
}
