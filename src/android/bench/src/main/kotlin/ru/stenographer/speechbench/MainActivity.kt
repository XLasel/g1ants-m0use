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
import ru.stenographer.speech.MediaCodecAudioDecoder
import ru.stenographer.speech.ModelInstaller
import ru.stenographer.speech.QuietChunker
import ru.stenographer.speech.SAMPLE_RATE
import ru.stenographer.speech.SherpaRecognizer
import ru.stenographer.speech.loudnessEnvelope
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
        if (uri != null) start(uri, uri.lastPathSegment.orEmpty().substringAfterLast('/'), DEFAULT_THREADS)
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
            start(Uri.fromFile(path), file, intent.getIntExtra(EXTRA_THREADS, DEFAULT_THREADS))
        }
    }

    private fun start(uri: Uri, name: String, threads: Int) {
        pick.isEnabled = false
        output.text = ""
        lifecycleScope.launch {
            try {
                bench(uri, name, threads)
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

    private suspend fun bench(uri: Uri, name: String, threads: Int) {
        val report = JSONObject().put("file", name).put("threads", threads)
        val timings = JSONObject()
        val peaks = JSONObject()

        suspend fun <T> stage(title: String, block: suspend () -> T): T {
            say("$title...")
            val started = SystemClock.elapsedRealtime()
            val value = block()
            val elapsed = SystemClock.elapsedRealtime() - started
            timings.put(title, elapsed)
            peaks.put(title, peakRssMb())
            say("  $elapsed мс, пик памяти ${peakRssMb()} МБ")
            return value
        }

        val modelDir = stage("install") { ModelInstaller(this).install() }
        val audio = stage("decode") { MediaCodecAudioDecoder(this).decode(uri) }
        val seconds = audio.size.toDouble() / SAMPLE_RATE
        say("Длина записи: %.1f с".format(seconds))
        val envelope = stage("envelope") { loudnessEnvelope(audio) }
        report.put("envelope_points", envelope.size).put("envelope_mean", envelope.average())
        val chunks = QuietChunker.plan(audio)
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

        val processingMs = timings.getLong("decode") + timings.getLong("load") + timings.getLong("asr")
        val rtf = processingMs / 1000.0 / seconds
        report.put("audio_seconds", seconds).put("chunks", chunks.size).put("timings_ms", timings)
            .put("peak_rss_mb", peaks).put("processing_ms", processingMs).put("rtf", rtf)
            .put("word_count", words.size).put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("words", JSONArray(words.map { JSONObject().put("text", it.text).put("start_ms", it.startMs).put("end_ms", it.endMs) }))

        val safeName = name.replace(Regex("[^\\w.-]"), "_")
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
        const val DEFAULT_THREADS = 4
    }
}
