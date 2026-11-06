package ru.stenographer.speech

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal val MODEL_FILES = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")

/**
 * Модели лежат в assets внутри APK, а нативному коду нужны обычные файлы. При первом запуске они копируются
 * во внутреннюю память приложения. Интернет для этого не нужен, но на устройстве занимается ещё около 232 МБ.
 */
class ModelInstaller(private val context: Context) {

    /** Возвращает папку с готовой моделью. Прерванное копирование при следующем запуске начнётся заново. */
    suspend fun install(onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val target = File(context.filesDir, "models/$MODEL_VERSION")
        val marker = File(target, ".complete")
        if (marker.isFile) return@withContext target

        target.deleteRecursively()
        if (!target.mkdirs()) throw IOException("Не удалось создать папку $target")
        val sizes = MODEL_FILES.associateWith { name -> assetSize("$ASSET_DIR/$name") }
        val total = sizes.values.sum()
        if (target.usableSpace < total + SPACE_RESERVE) {
            throw IOException("Для модели нужно ${total / 1_000_000} МБ свободного места")
        }

        var copied = 0L
        val buffer = ByteArray(1 shl 20)
        for (name in MODEL_FILES) {
            val temp = File(target, "$name.tmp")
            context.assets.open("$ASSET_DIR/$name").use { input ->
                temp.outputStream().use { output ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) onProgress(copied.toFloat() / total)
                    }
                }
            }
            if (temp.length() != sizes[name]) throw IOException("Файл $name скопирован не полностью")
            if (!temp.renameTo(File(target, name))) throw IOException("Не удалось сохранить $name")
        }
        marker.createNewFile()
        target
    }

    /** Размер в assets. Если файл сжат и длина через дескриптор недоступна, считается чтением. */
    private fun assetSize(path: String): Long = try {
        context.assets.openFd(path).use { it.length }
    } catch (_: IOException) {
        context.assets.open(path).use { input ->
            val buffer = ByteArray(1 shl 16)
            var size = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                size += read
            }
            size
        }
    }

    private companion object {
        const val MODEL_VERSION = "gigaam-v3-punct-int8"
        const val ASSET_DIR = "models/gigaam"
        const val SPACE_RESERVE = 50_000_000L
    }
}
