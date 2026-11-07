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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.stenographer.diarization.AsrWord
import ru.stenographer.diarization.ChunkPlanner
import ru.stenographer.diarization.DiarizationResult
import ru.stenographer.diarization.NemotronDiarizer
import ru.stenographer.diarization.TranscriptJson
import ru.stenographer.diarization.TranscriptMerger
import ru.stenographer.diarization.withSpeakerIds
import ru.stenographer.speech.MediaCodecAudioDecoder
import ru.stenographer.speech.ModelInstaller
import ru.stenographer.speech.QuietChunker
import ru.stenographer.speech.SAMPLE_RATE
import ru.stenographer.speech.SherpaRecognizer
import ru.stenographer.speech.loudnessEnvelope
import ru.stenographer.summary.SourceUtterance
import ru.stenographer.summary.android.summarizeLocal
import java.io.File

/**
 * Экран для замеров распознавания на телефоне, не часть итогового интерфейса.
 *
 * Файл можно выбрать кнопкой или запустить через adb, см. android/README.md.
 * Результат сохраняется в JSON во внешней папке приложения.
 */
class MainActivity : ComponentActivity() {
    private lateinit var output: TextView
    private lateinit var pick: Button

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) start(uri, uri.lastPathSegment.orEmpty().substringAfterLast('/'), DEFAULT_THREADS, diarize = true)
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
            start(Uri.fromFile(path), file, intent.getIntExtra(EXTRA_THREADS, DEFAULT_THREADS),
                intent.getBooleanExtra(EXTRA_DIARIZE, true))
        }
    }

    private fun start(uri: Uri, name: String, threads: Int, diarize: Boolean) {
        pick.isEnabled = false
        output.text = ""
        lifecycleScope.launch {
            try {
                bench(uri, name, threads, diarize, intent.getStringExtra(EXTRA_SUMMARY_MODEL))
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

    private suspend fun bench(uri: Uri, name: String, threads: Int, diarize: Boolean, summaryModel: String?) {
        val report = JSONObject().put("file", name).put("threads", threads).put("diarize", diarize)
        val timings = JSONObject()
        val peaks = JSONObject()

        suspend fun <T> stage(title: String, block: suspend () -> T): T {
            say("$title...")
            val started = SystemClock.elapsedRealtime()
            try {
                return block()
            } finally {
                val elapsed = SystemClock.elapsedRealtime() - started
                timings.put(title, elapsed)
                peaks.put(title, peakRssMb())
                say("  $elapsed мс, пик памяти ${peakRssMb()} МБ")
            }
        }

        val modelDir = stage("install") { ModelInstaller(this).install() }
        val audio = stage("decode") { MediaCodecAudioDecoder(this).decode(uri) }
        val seconds = audio.size.toDouble() / SAMPLE_RATE
        say("Длина записи: %.1f с".format(seconds))
        val envelope = stage("envelope") { loudnessEnvelope(audio) }
        report.put("envelope_points", envelope.size).put("envelope_mean", envelope.average())

        // Диаризация идёт до распознавания: её вероятности задают куски речи. Модель закрывается до загрузки GigaAM.
        val diarization: DiarizationResult? = if (diarize) {
            val diarDir = stage("diar_install") { NemotronInstaller(this).install() }
            val diarizer = stage("diar_load") { withContext(Dispatchers.Default) { NemotronDiarizer.open(diarDir, threads) } }
            val events = JSONArray()
            val diarStarted = SystemClock.elapsedRealtime()
            val result = diarizer.use { d ->
                stage("diarization") {
                    withContext(Dispatchers.Default) {
                        // Порции для интерфейса: события SpeakerFound и Segments из контракта UI
                        d.diarizeWithSegments(audio) { update ->
                            val atMs = SystemClock.elapsedRealtime() - diarStarted
                            events.put(JSONObject().put("at_ms", atMs).put("processed_until_ms", update.processedUntilMs)
                                .put("new_speakers", update.newSpeakers.size).put("segments", update.segments.size)
                                .put("final", update.final))
                            update.newSpeakers.forEach {
                                Log.i(TAG, "SpeakerFound ${it.speakerId} с ${it.firstAtMs} мс записи, через $atMs мс обработки")
                            }
                        }
                    }
                }
            }
            report.put("diar_speakers", result.speakers.size).put("diar_segments", result.segments.size)
                .put("diar_events", events)
            result
        } else {
            null
        }
        val probs = diarization?.probs
        val chunks = probs?.let { ChunkPlanner.plan(it, audio) } ?: QuietChunker.plan(audio)
        val recognizer = stage("load") { SherpaRecognizer.open(modelDir, threads) }
        val asrStarted = SystemClock.elapsedRealtime()
        var firstWordsMs = -1L
        var chunkEvents = 0
        var lastProcessedMs = 0L
        val words = recognizer.use { asr ->
            stage("asr") {
                asr.transcribe(audio, chunks, onChunk = { chunkWords, processedUntilMs ->
                    chunkEvents++
                    check(processedUntilMs >= lastProcessedMs) { "processedUntilMs уменьшился" }
                    lastProcessedMs = processedUntilMs
                    if (firstWordsMs < 0 && chunkWords.isNotEmpty()) firstWordsMs = SystemClock.elapsedRealtime() - asrStarted
                })
            }
        }
        report.put("chunk_events", chunkEvents).put("first_words_ms", firstWordsMs).put("processed_until_ms", lastProcessedMs)

        val safeName = name.replace(Regex("[^\\w.-]"), "_")
        if (diarization != null) {
            val utterances = stage("merge") {
                TranscriptMerger.merge(words.map { AsrWord(it.text, it.startMs, it.endMs) }, diarization.probs)
                    .withSpeakerIds(diarization.speakers)
            }
            val transcript = File(getExternalFilesDir(null), "transcript_$safeName.json")
            transcript.writeText(TranscriptJson.encode(utterances))
            report.put("utterances", utterances.size).put("speakers", utterances.map { it.speakerId }.distinct().size)
                .put("transcript_file", transcript.path)
            say("\nРеплик: ${utterances.size}, спикеров: ${utterances.map { it.speakerId }.distinct().size}")
            utterances.take(20).forEach { say("[${it.startMs / 1000}s] ${it.speakerId}: ${it.text}") }
            // Готовый JSON уже сохранён; модели диаризации и ASR закрыты. LLM идёт после Done.
            if (summaryModel != null) {
                val gguf = File(getExternalFilesDir(null), summaryModel)
                report.put("summary_model", gguf.name).put("summary_model_bytes", gguf.length())
                    .put("summary_context", 4096)
                try {
                    val summary = stage("summary") {
                        summarizeLocal(gguf, utterances.map {
                            SourceUtterance(it.speakerId, it.startMs, it.endMs, it.text)
                        }, threads = threads) { p -> Log.i(TAG, "SUMMARY progress=$p") }
                    }
                    val result = JSONObject().put("title", summary.title).put("text", summary.text)
                        .put("points", JSONArray(summary.points.map {
                            JSONObject().put("text", it.text).put("source_start_ms", it.sourceStartMs)
                        }))
                    val summaryFile = File(getExternalFilesDir(null), "summary_$safeName.json")
                    summaryFile.writeText(result.toString(2))
                    report.put("summary_status", "success").put("summary_file", summaryFile.path)
                    say("\n${summary.title}\n${summary.text}")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Ошибка конспекта не отменяет уже готовую расшифровку.
                    report.put("summary_status", "failed").put("summary_error", error.toString())
                    Log.e(TAG, "LLM failed; transcript remains available", error)
                    say("Конспект не готов: ${error.message}. Расшифровка сохранена.")
                }
            } else {
                report.put("summary_status", "not_requested")
            }
        } else {
            report.put("summary_status", "requires_diarized_transcript")
        }

        // Полная обработка: чтение, диаризация, распознавание и склейка. Установка моделей (первый запуск) не входит.
        val transcriptionMs = listOf("decode", "diar_load", "diarization", "load", "asr", "merge")
            .sumOf { if (timings.has(it)) timings.getLong(it) else 0L }
        val processingMs = transcriptionMs + timings.optLong("summary", 0)
        val rtf = processingMs / 1000.0 / seconds
        report.put("audio_seconds", seconds).put("chunks", chunks.size).put("timings_ms", timings)
            .put("peak_rss_mb", peaks).put("processing_ms", processingMs).put("rtf", rtf)
            .put("transcription_ms", transcriptionMs).put("transcription_rtf", transcriptionMs / 1000.0 / seconds)
            .put("word_count", words.size).put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("words", JSONArray(words.map { JSONObject().put("text", it.text).put("start_ms", it.startMs).put("end_ms", it.endMs) }))

        val file = File(getExternalFilesDir(null), "bench_$safeName.$threads.json")
        file.writeText(report.toString(2))
        say("\nСлов: ${words.size}, RTF: %.3f\nФайл: ${file.path}\n".format(rtf))
        say(words.joinToString(" ") { it.text })
        Log.i(TAG, "DONE file=$name threads=$threads seconds=$seconds words=${words.size} rtf=$rtf peakMb=${peakRssMb()} json=${file.path}")
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
        const val EXTRA_DIARIZE = "diarize"
        const val EXTRA_SUMMARY_MODEL = "summary_model"
        const val DEFAULT_THREADS = 4
    }
}
