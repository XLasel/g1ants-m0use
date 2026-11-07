package ru.stenographer.speechbench

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import ru.stenographer.core.PipelineEvent
import ru.stenographer.core.Stage
import ru.stenographer.core.TranscriptJson
import ru.stenographer.core.Utterance
import ru.stenographer.core.Word
import ru.stenographer.pipeline.PipelineConfig
import ru.stenographer.pipeline.TranscriptionPipeline
import ru.stenographer.speech.MediaCodecAudioDecoder
import java.io.File

/**
 * Экран для замеров на телефоне, не часть итогового интерфейса.
 *
 * Гоняет ту же обработку, что и приложение (`TranscriptionPipeline`), и меряет её по событиям: время и пик памяти
 * каждого этапа, когда пришли первые голоса и слова. Файл можно выбрать кнопкой или запустить через adb,
 * см. android/README.md. Результаты сохраняются во внешней папке приложения: `bench_*.json` (замеры и слова),
 * `transcript_*.json` (формат задания) и `summary_*.json` (если задана модель конспекта).
 */
class MainActivity : ComponentActivity() {
    private lateinit var output: TextView
    private lateinit var pick: Button

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) start(uri, uri.lastPathSegment.orEmpty().substringAfterLast('/'), DEFAULT_THREADS, DEFAULT_PARTS, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pick = Button(this).apply {
            text = "Выбрать аудиофайл"
            setOnClickListener { picker.launch(arrayOf("audio/*", "video/*")) }
        }
        output = TextView(this).apply {
            setPadding(32, 32, 32, 32)
            setTextIsSelectable(true)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pick)
            addView(ScrollView(this@MainActivity).apply { addView(output) })
        })

        val file = intent.getStringExtra(EXTRA_FILE)
        if (savedInstanceState == null && file != null) {
            val path = File(getExternalFilesDir(null), file)
            start(
                Uri.fromFile(path), file,
                threads = intent.getIntExtra(EXTRA_THREADS, DEFAULT_THREADS),
                parts = intent.getIntExtra(EXTRA_PARTS, DEFAULT_PARTS),
                verify = intent.getBooleanExtra(EXTRA_VERIFY, false),
            )
        }
    }

    private fun start(uri: Uri, name: String, threads: Int, parts: Int, verify: Boolean) {
        pick.isEnabled = false
        output.text = ""
        lifecycleScope.launch {
            try {
                if (verify) verifyDecode(uri, name, parts) else bench(uri, name, threads, intent.getStringExtra(EXTRA_SUMMARY_MODEL))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Замер не удался", e)
                say("ОШИБКА: $e")
            } finally {
                pick.isEnabled = true
            }
        }
    }

    private suspend fun bench(uri: Uri, name: String, threads: Int, summaryModel: String?) {
        val gguf = summaryModel?.let { File(getExternalFilesDir(null), it) }
        val pipeline = TranscriptionPipeline(this, PipelineConfig(threads = threads, summaryModel = gguf))
        val report = JSONObject().put("file", name).put("threads", threads)
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        val timings = JSONObject()
        val peaks = JSONObject()
        val events = JSONArray()
        val stageStart = HashMap<Stage, Long>()
        val words = ArrayList<Word>()
        var durationMs = 0L
        var utterances: List<Utterance>? = null
        var summary: PipelineEvent.Summary? = null
        var doneAtMs = -1L
        var failure: String? = null

        val started = SystemClock.elapsedRealtime()
        pipeline.process(name, uri).collect { event ->
            val at = SystemClock.elapsedRealtime() - started
            when (event) {
                is PipelineEvent.StageProgress -> {
                    val key = event.stage.name.lowercase()
                    if (event.progress == 0f) {
                        stageStart[event.stage] = at
                        say("$key...")
                    }
                    if (event.progress >= 1f) {
                        val elapsed = at - (stageStart[event.stage] ?: at)
                        timings.put(key, elapsed)
                        peaks.put(key, peakRssMb())
                        say("  $elapsed мс, пик памяти ${peakRssMb()} МБ")
                    }
                }
                is PipelineEvent.Decoded -> {
                    durationMs = event.durationMs
                    say("Длина записи: %.1f с, огибающая %d точек".format(durationMs / 1000.0, event.envelope.size))
                }
                is PipelineEvent.SpeakerFound -> {
                    events.put(JSONObject().put("type", "SpeakerFound").put("at_ms", at)
                        .put("speaker_id", event.speakerId).put("first_at_ms", event.firstAtMs))
                    say("  голос ${event.speakerId} с ${event.firstAtMs / 1000} с записи")
                }
                is PipelineEvent.Segments -> events.put(JSONObject().put("type", "Segments").put("at_ms", at)
                    .put("count", event.segments.size).put("final", event.final))
                is PipelineEvent.Words -> {
                    if (words.isEmpty() && event.words.isNotEmpty()) report.put("first_words_ms", at)
                    words += event.words
                    events.put(JSONObject().put("type", "Words").put("at_ms", at).put("count", event.words.size)
                        .put("processed_until_ms", event.processedUntilMs))
                }
                is PipelineEvent.Done -> {
                    utterances = event.utterances
                    doneAtMs = at
                }
                is PipelineEvent.Summary -> summary = event
                is PipelineEvent.Failed -> failure = event.reason
                is PipelineEvent.SpeakerFeatures -> {
                    events.put(JSONObject().put("type", "SpeakerFeatures").put("at_ms", at).put("speaker_id", event.speakerId)
                        .put("pitch_hz", event.pitchMedianHz.toDouble()).put("pitch_spread", event.pitchSpread.toDouble())
                        .put("loudness", event.loudnessMean.toDouble()))
                    say("  ${event.speakerId}: тон %.0f Гц, интонация %.2f, громкость %.2f"
                        .format(event.pitchMedianHz, event.pitchSpread, event.loudnessMean))
                }
            }
        }

        // Фиксируем конец Flow до экспорта и вывода результата, чтобы граница замера была однозначной.
        val processingMs = SystemClock.elapsedRealtime() - started
        report.put("processing_scope", "until_flow_completion_before_export")
        val safeName = name.replace(Regex("[^\\w.-]"), "_")
        failure?.let {
            report.put("failed", it)
            say("ОШИБКА: $it")
        }
        utterances?.let { result ->
            val transcript = File(getExternalFilesDir(null), "transcript_$safeName.json")
            transcript.writeText(TranscriptJson.encode(result))
            val speakers = result.map { it.speakerId }.distinct().size
            report.put("utterances", result.size).put("speakers", speakers).put("transcript_file", transcript.path)
            say("\nРеплик: ${result.size}, спикеров: $speakers")
            result.take(20).forEach { say("[${it.startMs / 1000}s] ${it.speakerId}: ${it.text}") }
        }
        report.put("summary_status", when {
            gguf == null -> "not_requested"
            summary != null -> "success"
            else -> "failed"
        })
        summary?.let { s ->
            val result = JSONObject().put("title", s.title).put("text", s.text)
                .put("points", JSONArray(s.points.map {
                    JSONObject().put("text", it.text).put("source_start_ms", it.sourceStartMs)
                        .put("kind", it.kind).put("speaker_id", it.speakerId)
                }))
            val summaryFile = File(getExternalFilesDir(null), "summary_$safeName.json")
            summaryFile.writeText(result.toString(2))
            report.put("summary_file", summaryFile.path)
            say("\n${s.title}\n${s.text}")
        }

        // Время до Done — расшифровка целиком, с установкой и загрузкой моделей; processing_ms — вместе с конспектом.
        val seconds = durationMs / 1000.0
        val transcriptionMs = if (doneAtMs >= 0) doneAtMs else processingMs
        val rtf = if (seconds > 0) transcriptionMs / 1000.0 / seconds else 0.0
        report.put("audio_seconds", seconds).put("timings_ms", timings).put("peak_rss_mb", peaks)
            .put("transcription_ms", transcriptionMs).put("rtf", rtf).put("processing_ms", processingMs)
            .put("word_count", words.size).put("events", events)
            .put("words", JSONArray(words.map { JSONObject().put("text", it.text).put("start_ms", it.startMs).put("end_ms", it.endMs) }))

        val file = File(getExternalFilesDir(null), "bench_$safeName.$threads.json")
        file.writeText(report.toString(2))
        say("\nСлов: ${words.size}, RTF: %.3f\nФайл: ${file.path}\n".format(rtf))
        Log.i(TAG, "DONE file=$name threads=$threads seconds=$seconds words=${words.size} rtf=$rtf peakMb=${peakRssMb()} json=${file.path}")
    }

    /**
     * Читает файл одним проходом и параллельно и сравнивает по отсчётам. Распознавание не запускается.
     * Нужно, чтобы проверить стыки на настоящих кодеках телефона.
     */
    private suspend fun verifyDecode(uri: Uri, name: String, parts: Int) {
        suspend fun timed(decoder: MediaCodecAudioDecoder): Pair<FloatArray, Long> {
            val started = SystemClock.elapsedRealtime()
            val audio = decoder.decode(uri)
            return audio to SystemClock.elapsedRealtime() - started
        }
        val parallelDecoder = MediaCodecAudioDecoder(this, parts)
        val (single, singleMs) = timed(MediaCodecAudioDecoder(this, 1))
        val (parallel, parallelMs) = timed(parallelDecoder)
        val common = minOf(single.size, parallel.size)
        var maxDiff = 0f
        var firstMismatch = -1
        var mismatches = 0
        for (i in 0 until common) {
            val diff = kotlin.math.abs(single[i] - parallel[i])
            if (diff > maxDiff) maxDiff = diff
            if (diff > MISMATCH) {
                mismatches++
                if (firstMismatch < 0) firstMismatch = i
            }
        }
        val report = JSONObject().put("file", name).put("parts", parallelDecoder.lastParts)
            .put("single_ms", singleMs).put("parallel_ms", parallelMs)
            .put("single_samples", single.size).put("parallel_samples", parallel.size)
            .put("notes", JSONArray(parallelDecoder.lastNotes))
            .put("max_diff", maxDiff.toDouble()).put("mismatches", mismatches).put("first_mismatch_ms", if (firstMismatch < 0) -1 else firstMismatch / 16)
        val safeName = name.replace(Regex("[^\\w.-]"), "_")
        File(getExternalFilesDir(null), "verify_$safeName.$parts.json").writeText(report.toString(2))
        if (intent.getBooleanExtra(EXTRA_DUMP, false)) {
            // Сырые отсчёты обоих вариантов для разбора стыков на компьютере: float32, little-endian.
            for ((suffix, data) in listOf("single" to single, "parallel" to parallel)) {
                val bytes = java.nio.ByteBuffer.allocate(data.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                bytes.asFloatBuffer().put(data)
                File(getExternalFilesDir(null), "dump_$safeName.$suffix.f32").writeBytes(bytes.array())
            }
        }
        say(report.toString(2))
        Log.i(TAG, "DONE verify $report")
    }

    private fun say(line: String) {
        output.append(line + "\n")
        Log.i(TAG, line.take(300))
    }

    /** Настоящий пик памяти процесса вместе с нативной частью. */
    private fun peakRssMb(): Long = File("/proc/self/status").useLines { lines ->
        lines.first { it.startsWith("VmHWM:") }.filter(Char::isDigit).toLong() / 1024
    }

    private companion object {
        const val TAG = "SpeechBench"
        const val EXTRA_FILE = "file"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_SUMMARY_MODEL = "summary_model"
        const val EXTRA_PARTS = "parts"
        const val EXTRA_VERIFY = "verify"
        const val EXTRA_DUMP = "dump"
        const val DEFAULT_THREADS = 4
        const val DEFAULT_PARTS = 4
        const val MISMATCH = 1e-5f
    }
}
