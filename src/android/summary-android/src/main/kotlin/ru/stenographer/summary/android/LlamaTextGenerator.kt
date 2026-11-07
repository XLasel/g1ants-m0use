package ru.stenographer.summary.android

import ru.stenographer.summary.GenerationRequest
import ru.stenographer.summary.TextGenerator
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** CPU llama.cpp, локальный GGUF. open/count/generate вызывать вне Main. */
class LlamaTextGenerator private constructor(id: Long, override val contextSize: Int) : TextGenerator, AutoCloseable {
    private val handle = AtomicLong(id)
    private fun current(): Long = handle.get().also { check(it != 0L) { "LLM is closed" } }

    @Synchronized
    override fun countTokens(system: String, user: String): Int = NativeLlama.countTokens(
        current(), system.toByteArray(Charsets.UTF_8), user.toByteArray(Charsets.UTF_8),
    )

    @Synchronized
    override fun generate(request: GenerationRequest): String = NativeLlama.generate(
        current(), request.system.toByteArray(Charsets.UTF_8), request.user.toByteArray(Charsets.UTF_8),
        request.grammar.toByteArray(Charsets.UTF_8), request.maxTokens,
    ).toString(Charsets.UTF_8)

    // Без monitor: отмена должна работать, пока generate держит свой monitor.
    override fun cancel() { handle.get().takeIf { it != 0L }?.let { NativeLlama.cancel(it) } }

    override fun close() {
        val id = handle.getAndSet(0)
        if (id != 0L) NativeLlama.close(id)
    }

    companion object {
        fun open(model: File, contextSize: Int = 4096, threads: Int = 4): LlamaTextGenerator {
            require(model.isFile) { "GGUF model is missing" }
            require(contextSize in 1024..32768)
            require(threads in 1..16)
            val id = NativeLlama.open(model.absolutePath.toByteArray(Charsets.UTF_8), contextSize, threads)
            check(id != 0L) { "Cannot load local GGUF" }
            try {
                return LlamaTextGenerator(id, NativeLlama.contextSize(id))
            } catch (e: Throwable) {
                NativeLlama.close(id)
                throw e
            }
        }
    }
}

/** UTF-8 передаётся байтами: JNI NewStringUTF/GetStringUTFChars используют другой формат для emoji. */
internal object NativeLlama {
    init { System.loadLibrary("stenographer_summary") }
    external fun open(path: ByteArray, context: Int, threads: Int): Long
    external fun contextSize(handle: Long): Int
    external fun countTokens(handle: Long, system: ByteArray, user: ByteArray): Int
    external fun generate(handle: Long, system: ByteArray, user: ByteArray, grammar: ByteArray, maxTokens: Int): ByteArray
    external fun cancel(handle: Long)
    external fun close(handle: Long)
}
