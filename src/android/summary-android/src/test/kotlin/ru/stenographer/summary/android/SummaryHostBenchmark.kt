package ru.stenographer.summary.android

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import ru.stenographer.summary.GenerationRequest
import ru.stenographer.summary.SourceUtterance
import ru.stenographer.summary.TextGenerator
import java.io.File
import java.security.MessageDigest

/** Real GGUF inference on a computer. Input is a transcript, not an audio/ASR benchmark. */
object SummaryHostBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 3..5) { "model.gguf transcript.json report.json [context] [threads]" }
        val model = File(args[0])
        val input = File(args[1])
        val output = File(args[2])
        val context = args.getOrNull(3)?.toInt() ?: 4096
        val threads = args.getOrNull(4)?.toInt() ?: 4
        val original = input.readBytes()
        val transcript = JSONObject(original.toString(Charsets.UTF_8)).getJSONArray("transcript")
        val source = (0 until transcript.length()).map { i ->
            val row = transcript.getJSONObject(i)
            SourceUtterance(row.getString("speaker_id"), row.getLong("start_ms"), row.getLong("end_ms"), row.getString("text"))
        }
        fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
        val modelDigest = MessageDigest.getInstance("SHA-256")
        model.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                modelDigest.update(buffer, 0, count)
            }
        }
        fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000
        val calls = JSONArray()
        val report = JSONObject().put("kind", "summary_host_inference")
            .put("input_kind", "transcript_not_audio").put("transcript_sha256", sha256(original))
            .put("model_file", model.name).put("model_bytes", model.length())
            .put("model_sha256", modelDigest.digest().joinToString("") { "%02x".format(it) })
            .put("validation_scope", "JSON, source excerpts, timestamps and lifecycle; not a semantic quality score")
            .put("os", System.getProperty("os.name")).put("arch", System.getProperty("os.arch"))
            .put("java", System.getProperty("java.version")).put("threads", threads)
            .put("context_size", context).put("utterances", source.size).put("calls", calls)
        val cpu = File("/proc/cpuinfo").takeIf { it.isFile }?.useLines { lines ->
            lines.firstOrNull { it.startsWith("model name") }?.substringAfter(':')?.trim()
        }
        if (cpu != null) report.put("cpu", cpu)
        var closed = false
        val started = System.nanoTime()
        try {
            val result = runBlocking {
                summarizeOnWorker(source) {
                    val loadStarted = System.nanoTime()
                    val engine = LlamaTextGenerator.open(model, context, threads)
                    report.put("load_ms", elapsed(loadStarted))
                    object : TextGenerator {
                        override val contextSize = engine.contextSize
                        override fun countTokens(system: String, user: String) = engine.countTokens(system, user)
                        override fun generate(request: GenerationRequest): String {
                            val promptTokens = engine.countTokens(request.system, request.user)
                            val generationStarted = System.nanoTime()
                            val text = engine.generate(request)
                            calls.put(JSONObject().put("prompt_tokens", promptTokens)
                                .put("generation_ms", elapsed(generationStarted)).put("output_chars", text.length)
                                .put("raw_json", text))
                            return text
                        }
                        override fun cancel() = engine.cancel()
                        override fun close() { engine.close(); closed = true }
                    }
                }
            }
            check(closed) { "Worker resumed before closing the model" }
            check(input.readBytes().contentEquals(original)) { "Transcript changed" }
            check(result.points.all { point -> source.any {
                it.startMs == point.sourceStartMs && it.speakerId == point.speakerId && it.text.contains(point.text)
            } }) { "Summary point text must be an excerpt of its cited source" }
            report.put("success", true).put("model_closed", closed).put("transcript_unchanged", true)
                .put("point_text_is_source_excerpt", true)
                .put("point_speaker_is_source_speaker", true)
                .put("summary", JSONObject().put("title", result.title).put("text", result.text)
                    .put("points", JSONArray(result.points.map {
                        JSONObject().put("text", it.text).put("source_start_ms", it.sourceStartMs)
                            .put("kind", it.kind).put("speaker_id", it.speakerId)
                    })))
            println("Real-model host inference passed: ${result.title}")
        } catch (error: Throwable) {
            report.put("success", false).put("model_closed", closed).put("error", error.toString())
            throw error
        } finally {
            report.put("total_ms", elapsed(started))
            File("/proc/self/status").takeIf { it.isFile }?.useLines { lines ->
                lines.firstOrNull { it.startsWith("VmHWM:") }?.filter(Char::isDigit)?.toLongOrNull()
            }?.let { report.put("peak_rss_mb", it / 1024) }
            output.parentFile?.mkdirs()
            output.writeText(report.toString(2))
            println("Host report: ${output.absolutePath}")
        }
    }
}
