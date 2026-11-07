package ru.stenographer.summary.android

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException

/** Установка выбранного пользователем локального GGUF через SAF, без сети. */
object GgufModelStore {
    /** Вызов вне Main. Существующий mmap-файл модели никогда не перезаписывается. */
    fun importModel(context: Context, uri: Uri, isCancelled: () -> Boolean = { false }): File {
        val directory = File(context.filesDir, "summary-models")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create model directory" }
        val target = File(directory, "summary-${UUID.randomUUID()}.gguf")
        val partial = File(directory, target.name + ".part")
        try {
            val source = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open model file")
            source.buffered().use { input ->
                partial.outputStream().buffered().use { output ->
                    val magic = ByteArray(4)
                    var count = 0
                    while (count < magic.size) {
                        val read = input.read(magic, count, magic.size - count)
                        if (read < 0) throw IOException("GGUF file is too short")
                        count += read
                    }
                    if (!magic.contentEquals(byteArrayOf(71, 71, 85, 70))) throw IOException("File is not GGUF")
                    output.write(magic)
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        if (isCancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Model import cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (!partial.renameTo(target)) throw IOException("Cannot finalize model import")
            return target
        } finally {
            partial.delete()
        }
    }
}
