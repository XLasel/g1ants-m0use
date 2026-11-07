package ru.stenographer.app.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.stenographer.app.data.Recording
import ru.stenographer.app.data.summaryText
import java.io.File

/** Формат из задания хакатона: по нему считают WER и качество разметки голосов. */
@Serializable
private data class ExportFile(val transcript: List<ExportLine>)

@Serializable
private data class ExportLine(
    @SerialName("speaker_id") val speakerId: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val text: String,
)

object TranscriptExport {
    private val json = Json { prettyPrint = true }

    /**
     * Голоса нумеруются заново по первому появлению: после «Объединить» номера идут без пропусков.
     * Без объединения нумерация совпадает с той, что дала обработка.
     */
    fun toJson(rec: Recording): String {
        val ids = rec.utterances.map { it.speakerId }.distinct().withIndex().associate { (i, id) -> id to "speaker_${i + 1}" }
        return json.encodeToString(
            ExportFile(rec.utterances.map { ExportLine(ids.getValue(it.speakerId), it.startMs, it.endMs, it.text) }),
        )
    }

    /** Обычный текст для мессенджеров: конспект, если есть, потом реплики с именем голоса и временем. */
    fun toText(rec: Recording): String = buildString {
        val summary = summaryText(rec)
        if (summary != null) {
            appendLine(summary)
            appendLine()
            appendLine("Расшифровка")
        } else {
            appendLine(rec.title)
        }
        appendLine()
        rec.utterances.forEach { u ->
            val name = rec.speaker(u.speakerId)?.name ?: u.speakerId
            appendLine("$name, ${formatTime(u.startMs)}")
            appendLine(u.text)
            appendLine()
        }
    }.trimEnd()

    /** Имя файла как у исходного аудио (для записей с микрофона — название), без символов, которые не любят файловые системы. */
    fun fileName(rec: Recording, ext: String): String {
        val base = (rec.sourceName ?: rec.title).replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(60).ifEmpty { "transcript" }
        return "$base.$ext"
    }

    /**
     * Сохраняет в общую папку «Загрузки». На Android 10+ через MediaStore без разрешений,
     * на Android 8–9 напрямую в папку (там для этого хватает разрешения из манифеста).
     * @return ссылка на сохранённый файл
     */
    fun saveToDownloads(context: Context, name: String, mime: String, content: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Не удалось создать файл в «Загрузках»")
            resolver.openOutputStream(uri)!!.use { it.write(content.toByteArray()) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
            val file = uniqueFile(dir, name)
            file.writeText(content)
            Uri.fromFile(file)
        }
    }

    /** Временный файл для «Поделиться»: отдаём его другим приложениям через FileProvider. */
    fun shareFile(context: Context, name: String, content: String): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        return File(dir, name).apply { writeText(content) }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 2
        while (f.exists()) {
            f = File(dir, "${name.substringBeforeLast('.')} ($i).${name.substringAfterLast('.')}")
            i++
        }
        return f
    }
}

/** 75_000 → «1:15», 3_725_000 → «1:02:05». */
fun formatTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
