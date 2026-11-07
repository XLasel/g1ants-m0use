package ru.stenographer.pipeline

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Копирует модель диаризации из assets во внутреннюю память: ONNX Runtime открывает модель по пути к файлу.
 * Устроен так же, как ModelInstaller из speech. Модель кладёт scripts/prepare_diarization.sh.
 */
internal class NemotronInstaller(private val context: Context) {

    suspend fun install(): File = withContext(Dispatchers.IO) {
        val target = File(context.filesDir, "models/$VERSION")
        val marker = File(target, ".complete")
        if (marker.isFile) return@withContext target

        target.deleteRecursively()
        if (!target.mkdirs()) throw IOException("Не удалось создать папку $target")
        val buffer = ByteArray(1 shl 20)
        for (name in FILES) {
            val temp = File(target, "$name.tmp")
            context.assets.open("$ASSET_DIR/$name").use { input ->
                temp.outputStream().use { output ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (!temp.renameTo(File(target, name))) throw IOException("Не удалось сохранить $name")
        }
        marker.createNewFile()
        target
    }

    private companion object {
        const val VERSION = "nemotron-3-diarization-int8"
        const val ASSET_DIR = "models/nemotron"
        val FILES = listOf("embed.onnx", "step_int8.onnx", "mel_filters.bin", "silence_embeds.bin")
    }
}
