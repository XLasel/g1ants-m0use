package ru.stenographer.summary.android

import java.io.File

/** Отдельный host smoke, не Android-инференс и не часть APK. Запуск через tools/test_summary.py. */
object NativeBridgeHostSmoke {
    @JvmStatic fun main(args: Array<String>) {
        fun rejected(block: () -> Unit) {
            var threw = false
            try { block() } catch (_: IllegalStateException) { threw = true }
            check(threw) { "Expected an exception from JNI" }
        }
        rejected { NativeLlama.open(byteArrayOf(), 0, 4) }
        rejected { NativeLlama.contextSize(0) }
        rejected { NativeLlama.countTokens(0, byteArrayOf(), byteArrayOf()) }
        rejected { NativeLlama.generate(0, byteArrayOf(), byteArrayOf(), byteArrayOf(), 128) }
        NativeLlama.cancel(0)
        NativeLlama.close(0)
        val invalid = File.createTempFile("summary-native-", ".gguf")
        try {
            invalid.writeBytes(byteArrayOf(71, 71, 85, 70, 3, 0, 0, 0))
            rejected { NativeLlama.open(invalid.absolutePath.toByteArray(Charsets.UTF_8), 4096, 4) }
        } finally { invalid.delete() }
        println("JNI host smoke passed: all entry points and invalid-model error path")
    }
}
