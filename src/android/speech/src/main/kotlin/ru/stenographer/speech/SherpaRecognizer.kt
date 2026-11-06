package ru.stenographer.speech

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * GigaAM v3 через sherpa-onnx. После своего шага закрывайте объект через [close],
 * чтобы освободить память для следующей модели.
 */
class SherpaRecognizer private constructor(private val recognizer: OfflineRecognizer) : Recognizer, Closeable {
    private val mutex = Mutex() // один распознаватель обрабатывает один кусок за раз

    override suspend fun transcribe(
        audio: FloatArray,
        chunks: List<LongRange>,
        onProgress: (Float) -> Unit,
    ): List<Word> = withContext(Dispatchers.Default) {
        mutex.withLock {
            val words = ArrayList<Word>()
            chunks.forEachIndexed { index, chunk ->
                coroutineContext.ensureActive()
                words += transcribeChunk(audio, chunk)
                onProgress((index + 1f) / chunks.size)
            }
            words
        }
    }

    private fun transcribeChunk(audio: FloatArray, chunk: LongRange): List<Word> {
        val from = msToSample(chunk.first).coerceIn(0, audio.size)
        val to = msToSample(chunk.last).coerceIn(from, audio.size)
        if (to - from < MIN_CHUNK_SAMPLES) return emptyList()
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(audio.copyOfRange(from, to), SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return tokensToWords(result.tokens, result.timestamps, chunk.first, sampleToMs(to))
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    companion object {
        /** Короче одного окна признаков (25 мс) распознавать нечего. */
        private const val MIN_CHUNK_SAMPLES = 400

        /** Загружает модель из папки, которую вернул [ModelInstaller]. Занимает заметное время, поэтому вызывайте с прогрессом на экране. */
        suspend fun open(modelDir: File, threads: Int = 4): SherpaRecognizer = withContext(Dispatchers.Default) {
            // Без этой проверки нативный код при отсутствии файла завершает весь процесс.
            val missing = MODEL_FILES.filterNot { File(modelDir, it).isFile }
            require(missing.isEmpty()) { "В $modelDir нет файлов модели: $missing" }
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(modelDir, "encoder.int8.onnx").path,
                        decoder = File(modelDir, "decoder.onnx").path,
                        joiner = File(modelDir, "joiner.onnx").path,
                    ),
                    tokens = File(modelDir, "tokens.txt").path,
                    numThreads = threads,
                    modelType = "nemo_transducer",
                ),
            )
            SherpaRecognizer(OfflineRecognizer(null, config))
        }
    }
}

internal fun msToSample(ms: Long): Int = (ms * SAMPLE_RATE / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

internal fun sampleToMs(sample: Int): Long = sample * 1000L / SAMPLE_RATE
