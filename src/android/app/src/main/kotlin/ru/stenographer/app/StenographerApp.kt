package ru.stenographer.app

import android.app.Application
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.stenographer.app.data.AppSettings
import ru.stenographer.app.data.ProcessingQueue
import ru.stenographer.app.data.Recording
import ru.stenographer.app.data.RecordingsRepository
import ru.stenographer.app.pipeline.LocalModels
import ru.stenographer.app.service.Notifications
import ru.stenographer.app.service.ProcessingService
import ru.stenographer.app.service.RecordingService
import java.text.SimpleDateFormat
import java.util.Locale
import java.io.File

class StenographerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        container = AppContainer(this)
    }
}

/**
 * Все долгоживущие объекты приложения в одном месте.
 * Собираем руками: зависимостей мало, а без Hilt проект проще и быстрее собирается.
 */
class AppContainer(private val context: Context) {
    /** Живёт столько же, сколько процесс: в нём идут обработка и сохранение. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = AppSettings(context)
    val repository = RecordingsRepository(context, appScope)

    /** Модели и обработка из :pipeline; копирование моделей начинается сразу при запуске. */
    private val models = LocalModels(context, appScope)

    val queue = ProcessingQueue(repository, models, settings, appScope)

    init {
        // Процесс только что запустился, значит запись с микрофона сейчас не идёт.
        // Файлы прерванных записей возвращаем в список и расшифровываем.
        if (!RecordingService.state.value.active) {
            repository.orphanRecordings().forEach { file ->
                val title = "Восстановленная запись " + SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(file.lastModified())
                addRecorded(file.nameWithoutExtension, file, title, startService = false)
            }
        }
        // Запуск из фона Android 12+ может запретить; тогда обработка продолжится при открытии экрана.
        if (queue.pending.value > 0) runCatching { ProcessingService.start(context) }
    }

    /** Импортирует файлы и ставит их в очередь. @return id первой записи, чтобы открыть её обработку. */
    suspend fun importAndQueue(uris: List<Uri>): String? {
        var first: String? = null
        for (uri in uris) {
            val rec = runCatching { repository.import(uri) }.getOrNull() ?: continue
            if (first == null) first = rec.id
            queue.enqueue(rec.id)
        }
        if (first != null) ProcessingService.start(context)
        return first
    }

    /** Добавляет записанный с микрофона файл и ставит в очередь. */
    fun addRecorded(id: String, file: File, title: String, startService: Boolean = true) {
        repository.add(Recording(id = id, title = title, createdAt = System.currentTimeMillis(), audioPath = file.absolutePath))
        queue.enqueue(id)
        if (startService) ProcessingService.start(context)
    }

    fun retry(id: String) {
        repository.update(id) { it.copy(status = Recording.Status.QUEUED, error = null) }
        queue.enqueue(id)
        ProcessingService.start(context)
    }
}
