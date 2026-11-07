package ru.stenographer.pipeline

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Копирует модель из assets во внутреннюю память: ONNX Runtime и llama.cpp открывают модель по пути к файлу.
 * Устроен так же, как ModelInstaller из speech: копия сохраняется один раз, прерванное копирование при следующем
 * запуске начинается заново. Интернет не нужен.
 */
internal class AssetModelInstaller(
    private val context: Context,
    private val assetDir: String,
    private val version: String,
    private val files: List<String>,
) {
    /** Есть ли модель в APK. Модели конспекта может не быть: тогда конспект не делается. */
    fun isBundled(): Boolean = try {
        context.assets.list(assetDir)?.toSet()?.containsAll(files) == true
    } catch (_: IOException) {
        false
    }

    suspend fun install(onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val target = File(context.filesDir, "models/$version")
        val marker = File(target, ".complete")
        if (marker.isFile) return@withContext target

        target.deleteRecursively()
        if (!target.mkdirs()) throw IOException("Не удалось создать папку $target")
        val sizes = files.associateWith { assetSize("$assetDir/$it") }
        val total = sizes.values.sum()
        if (target.usableSpace < total + SPACE_RESERVE) {
            throw IOException("Для моделей нужно ${total / 1_000_000} МБ свободного места")
        }
        var copied = 0L
        val buffer = ByteArray(1 shl 20)
        for (name in files) {
            val temp = File(target, "$name.tmp")
            context.assets.open("$assetDir/$name").use { input ->
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
            if (!temp.renameTo(File(target, name))) throw IOException("Не удалось сохранить $name")
        }
        marker.createNewFile()
        target
    }

    /** Размер без чтения, если файл в APK не сжат (noCompress), иначе — чтением. */
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

    companion object {
        private const val SPACE_RESERVE = 50_000_000L

        /** Nemotron для диаризации, кладёт scripts/prepare_diarization.sh. */
        fun nemotron(context: Context) = AssetModelInstaller(
            context, "models/nemotron", "nemotron-3-diarization-int8",
            listOf("embed.onnx", "step_int8.onnx", "mel_filters.bin", "silence_embeds.bin"),
        )

        /**
         * Модель названия и конспекта, кладёт scripts/prepare_summary.sh. В APK может лежать любая GGUF под именем
         * [SUMMARY_FILE], поэтому папка копии зависит от её размера: другая модель скопируется заново, а не
         * возьмётся старая копия.
         */
        fun summary(context: Context) = AssetModelInstaller(
            context, "models/summary", "summary-${bundledSize(context, "models/summary/$SUMMARY_FILE")}", listOf(SUMMARY_FILE),
        )

        private fun bundledSize(context: Context, path: String): Long = try {
            context.assets.openFd(path).use { it.length }
        } catch (_: IOException) {
            0L
        }

        const val SUMMARY_FILE = "summary.gguf"
    }
}
