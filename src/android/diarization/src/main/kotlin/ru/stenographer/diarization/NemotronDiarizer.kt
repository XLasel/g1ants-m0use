package ru.stenographer.diarization

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ru.stenographer.diarization.Nemotron.CHUNK_LENGTH
import ru.stenographer.diarization.Nemotron.CHUNK_RIGHT_CONTEXT
import ru.stenographer.diarization.Nemotron.EMBED_DIM
import ru.stenographer.diarization.Nemotron.HOP_LENGTH
import ru.stenographer.diarization.Nemotron.MEL_BINS
import ru.stenographer.diarization.Nemotron.SUBSAMPLING
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.min

/**
 * Вероятности спикеров [frames × 8] с шагом [FRAME_MS], одним массивом по строкам.
 * Спикеры пронумерованы по порядку появления в записи.
 */
class FrameProbs(val data: FloatArray) {
    init {
        require(data.size % NUM_SPEAKERS == 0) { "Длина должна делиться на $NUM_SPEAKERS" }
    }

    val frames: Int get() = data.size / NUM_SPEAKERS

    operator fun get(frame: Int, speaker: Int): Float = data[frame * NUM_SPEAKERS + speaker]

    /** Сколько спикеров хоть раз уверенно говорили (вероятность от [threshold]). */
    fun activeSpeakers(threshold: Float = 0.5f): Int =
        (0 until NUM_SPEAKERS).count { s -> (0 until frames).any { this[it, s] >= threshold } }
}

/**
 * Nemotron 3 Diarization через ONNX Runtime, порт `OnnxDiarizer` из onnx_diarizer.py.
 *
 * Цепочка: лог-мел → embed.onnx → чанки [кэш спикеров, FIFO, чанк, правый контекст] → step.onnx.
 * Объект держит в памяти около 110 МБ модели, после своего шага его нужно закрыть через [close].
 */
class NemotronDiarizer private constructor(
    private val env: OrtEnvironment,
    private val embed: OrtSession,
    private val step: OrtSession,
    private val logMel: LogMel,
    private val silence: FloatArray,
) : Closeable {

    /**
     * @param audio звук 16 кГц, моно, от -1 до 1
     * @param onProgress доля от 0 до 1 и число спикеров, найденных к этому моменту
     */
    fun diarize(audio: FloatArray, onProgress: (Float, Int) -> Unit = { _, _ -> }): FrameProbs {
        if (audio.size < HOP_LENGTH * SUBSAMPLING) return FrameProbs(FloatArray(0))
        val featureFrames = logMel.frameCount(audio.size)
        val embeds = embed(logMel.compute(audio), featureFrames)
        return diarizeEmbeds(embeds, featureFrames, onProgress)
    }

    /** Цикл по чанкам с кэшем спикеров. [onChunk] получает состояние кэша после каждого чанка (для тестов). */
    internal fun diarizeEmbeds(
        embeds: List<FloatArray>,
        featureFrames: Int,
        onProgress: (Float, Int) -> Unit = { _, _ -> },
        onChunk: (Int, SpeakerCache) -> Unit = { _, _ -> },
    ): FrameProbs {
        val cache = SpeakerCache(silence)
        var chunkIndex = 0
        val output = FloatArray(featureFrames * NUM_SPEAKERS)
        var written = 0 // строк в output
        val seen = BooleanArray(NUM_SPEAKERS)
        var start = 0
        while (start < embeds.size) {
            val end = min(start + CHUNK_LENGTH, embeds.size)
            val chunkEnd = min(end + CHUNK_RIGHT_CONTEXT, embeds.size)
            val context = cache.context()
            val stepEmbeds = context + embeds.subList(start, chunkEnd)
            val logits = runStep(stepEmbeds)
            val logitRows = logits.size / NUM_SPEAKERS

            // Вероятности для кэша: sigmoid, усреднённый по 8 кадрам по 10 мс внутри каждого кадра 80 мс.
            val pooled = List(stepEmbeds.size) { row ->
                FloatArray(NUM_SPEAKERS).also { p ->
                    for (k in 0 until SUBSAMPLING) {
                        val r = row * SUBSAMPLING + k
                        if (r < logitRows) for (s in 0 until NUM_SPEAKERS) p[s] += sigmoid(logits[r * NUM_SPEAKERS + s])
                    }
                    for (s in 0 until NUM_SPEAKERS) p[s] /= SUBSAMPLING.toFloat()
                }
            }
            cache.update(stepEmbeds, pooled, end - start)
            onChunk(chunkIndex++, cache)

            val first = context.size * SUBSAMPLING
            val last = min(first + (end - start) * SUBSAMPLING, logitRows)
            for (r in first until last) {
                if (written >= featureFrames) break
                for (s in 0 until NUM_SPEAKERS) {
                    val p = sigmoid(logits[r * NUM_SPEAKERS + s])
                    output[written * NUM_SPEAKERS + s] = p
                    if (p >= 0.5f) seen[s] = true
                }
                written++
            }
            start = end
            onProgress(start.toFloat() / embeds.size, seen.count { it })
        }
        return FrameProbs(if (written == featureFrames) output else output.copyOf(written * NUM_SPEAKERS))
    }

    /** Признаки [кадры, 128] → эмбеддинги по 80 мс. */
    internal fun embed(features: FloatArray, frames: Int): List<FloatArray> {
        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, frames.toLong(), MEL_BINS.toLong()))
        val flat = input.use { tensor -> embed.run(mapOf("features" to tensor)).use { it.floats(0) } }
        return List(flat.size / EMBED_DIM) { flat.copyOfRange(it * EMBED_DIM, (it + 1) * EMBED_DIM) }
    }

    /** Один шаг модели: строки эмбеддингов → логиты [строки × 8, 8]. */
    internal fun runStep(rows: List<FloatArray>): FloatArray {
        val buffer = FloatBuffer.allocate(rows.size * EMBED_DIM)
        rows.forEach { buffer.put(it) }
        buffer.rewind()
        val input = OnnxTensor.createTensor(env, buffer, longArrayOf(1, rows.size.toLong(), EMBED_DIM.toLong()))
        return input.use { tensor -> step.run(mapOf("embeds" to tensor)).use { it.floats(0) } }
    }

    override fun close() {
        step.close()
        embed.close()
    }

    companion object {
        /**
         * Загружает модель из папки с файлами [Nemotron.MODEL_FILES].
         * @param stepFile можно указать step.onnx (fp32) для сравнения, по умолчанию int8
         */
        fun open(modelDir: File, threads: Int = 4, stepFile: String = Nemotron.STEP_FILE): NemotronDiarizer {
            val missing = (Nemotron.MODEL_FILES - Nemotron.STEP_FILE + stepFile).filterNot { File(modelDir, it).isFile }
            require(missing.isEmpty()) { "В $modelDir нет файлов модели: $missing" }
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setInterOpNumThreads(1)
            }
            val embed = env.createSession(File(modelDir, Nemotron.EMBED_FILE).path, options)
            val step = try {
                env.createSession(File(modelDir, stepFile).path, options)
            } catch (e: Exception) {
                embed.close()
                throw e
            }
            val filters = readFloats(File(modelDir, Nemotron.MEL_FILTERS_FILE))
            val silence = readFloats(File(modelDir, Nemotron.SILENCE_FILE))
            require(silence.size == EMBED_DIM) { "Неверный размер silence_embeds: ${silence.size}" }
            return NemotronDiarizer(env, embed, step, LogMel(filters), silence)
        }
    }
}

internal fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

/** Числа float32 little-endian из файла, как np.fromfile(dtype="<f4"). */
internal fun readFloats(file: File): FloatArray {
    val bytes = file.readBytes()
    val result = FloatArray(bytes.size / 4)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(result)
    return result
}

private fun OrtSession.Result.floats(index: Int): FloatArray {
    val buffer = (get(index) as OnnxTensor).floatBuffer
    return FloatArray(buffer.remaining()).also { buffer.get(it) }
}
