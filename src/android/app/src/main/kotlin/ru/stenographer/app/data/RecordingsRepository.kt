package ru.stenographer.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Список записей. Источник правды для всех экранов:
 * держит записи в памяти как StateFlow и после каждого изменения
 * сохраняет их в один JSON-файл. Записей единицы и десятки, базе данных тут делать нечего.
 */
class RecordingsRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(context.filesDir, "recordings.json")
    private val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
    private val saveLock = Mutex()

    private val _recordings = MutableStateFlow(load())
    /** Все записи, свежие сверху. */
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    fun get(id: String): Recording? = _recordings.value.firstOrNull { it.id == id }

    /**
     * Копирует выбранный пользователем файл к себе: доступ по ссылке из системного
     * выбора файлов может пропасть, а звук нужен для плеера и повторной обработки.
     */
    suspend fun import(uri: Uri): Recording = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "Запись"
        val ext = name.substringAfterLast('.', "").takeIf { it.length in 2..4 } ?: "audio"
        val id = UUID.randomUUID().toString()
        val target = File(audioDir, "$id.$ext")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        val rec = Recording(
            id = id,
            title = name.substringBeforeLast('.'),
            sourceName = name.substringBeforeLast('.'),
            createdAt = System.currentTimeMillis(),
            audioPath = target.absolutePath,
        )
        add(rec)
        rec
    }

    /** Новый файл для записи с микрофона. */
    fun newRecordingFile(id: String): File = File(audioDir, "$id.wav")

    fun add(rec: Recording) {
        _recordings.update { listOf(rec) + it }
        persist()
    }

    fun update(id: String, change: (Recording) -> Recording) {
        _recordings.update { list -> list.map { if (it.id == id) change(it) else it } }
        persist()
    }

    fun delete(id: String) {
        get(id)?.let { File(it.audioPath).delete() }
        _recordings.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    /**
     * Записи с микрофона, которые остались без карточки: приложение закрыли или телефон выключился
     * посреди записи. Короче секунды не берём — это пустые файлы от отменённой записи.
     */
    fun orphanRecordings(): List<File> {
        val known = _recordings.value.map { File(it.audioPath).name }.toSet()
        return audioDir.listFiles().orEmpty()
            .filter { it.extension == "wav" && it.name !in known && it.length() > 44 + 32_000 }
    }

    /** Удаляет звук готовых записей, чтобы освободить место; расшифровки остаются. */
    fun clearAudio() {
        _recordings.value.filter { it.status == Recording.Status.DONE }.forEach { File(it.audioPath).delete() }
        _recordings.value = _recordings.value.toList() // экраны пересчитают размеры
    }

    private fun load(): List<Recording> = runCatching {
        if (file.exists()) json.decodeFromString<List<Recording>>(file.readText()) else emptyList()
    }.getOrDefault(emptyList()).map {
        // Если приложение закрыли посреди обработки, ставим запись в очередь заново.
        // Конспект, который не успел дописаться, уже не придёт.
        when {
            it.status == Recording.Status.PROCESSING -> it.copy(status = Recording.Status.QUEUED)
            it.summaryPending -> it.copy(summaryPending = false)
            else -> it
        }
    }

    private fun persist() {
        scope.launch(Dispatchers.IO) {
            saveLock.withLock {
                // Пишем самое свежее состояние: так порядок запусков сохранения не важен.
                val tmp = File(file.parentFile, "recordings.tmp")
                tmp.writeText(json.encodeToString(_recordings.value))
                tmp.renameTo(file)
            }
        }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}
