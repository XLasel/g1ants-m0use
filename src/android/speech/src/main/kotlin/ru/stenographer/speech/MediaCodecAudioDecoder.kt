package ru.stenographer.speech

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Читает m4a, mp3, wav, ogg, flac и звук из видео теми кодеками, что есть на устройстве.
 *
 * Кодек Android работает в отдельном системном процессе, и почти всё время чтения уходит на передачу ему
 * тысяч маленьких кадров по одному. Поэтому:
 * - кодек работает в асинхронном режиме: он сам сообщает, когда готов, и не нужно ждать его с паузами;
 * - перевод в float, сведение в моно и пересчёт в 16 кГц идут сразу, в потоке этого кодека;
 * - длинная сжатая запись делится на [maxParts] кусков по времени, и каждый кусок читает свой кодек.
 *   Кусок начинается на [PREROLL_US] раньше своего стыка, чтобы кодек успел войти в нормальный режим,
 *   а лишнее обрезается по номеру отсчёта (см. Stitch.kt). Результат совпадает с чтением одним проходом.
 *   Если стыки не сошлись или устройство не дало несколько кодеков сразу, запись читается одним проходом.
 */
class MediaCodecAudioDecoder(
    private val context: Context,
    private val maxParts: Int = DEFAULT_PARTS,
) : AudioDecoder {

    /** Сколько кусков читалось параллельно при последнем вызове: 1 — одним проходом. Нужно для замеров. */
    @Volatile
    var lastParts: Int = 0
        private set

    @Volatile
    private var fallbackNote: String? = null

    /** Служебные строки о последнем чтении: как кодек начал каждый кусок. Нужны только для замеров. */
    @Volatile
    var lastNotes: List<String> = emptyList()
        private set

    override suspend fun decode(uri: Uri, onProgress: (Float) -> Unit): FloatArray = withContext(Dispatchers.Default) {
        val track = probe(uri)
        val starts = planParts(track)
        val parallel = if (starts.size > 1) {
            try {
                decodeParallel(uri, track, starts, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Одним проходом файл всё равно прочитается, а настоящая ошибка повторится там с понятным текстом.
                Log.w(TAG, "Параллельное чтение не получилось, читаю одним проходом: $e")
                fallbackNote = "параллельно не вышло: $e"
                null
            }
        } else {
            null
        }
        val audio = parallel ?: decodeSequential(uri, track, onProgress)
        if (audio.isEmpty()) throw AudioDecodeException("Из файла не удалось получить звук")
        onProgress(1f)
        audio
    }

    private class Track(val index: Int, val format: MediaFormat, val mime: String, val durationUs: Long, val firstUs: Long)

    /** Куски, которые нужно прочитать. [untilUs] — после какого времени перестать подавать кадры. */
    private class PartSpec(val index: Int, val seekUs: Long, val untilUs: Long, val progressFromUs: Long, val progressToUs: Long)

    /** Что прочитал один кодек: обычно один отрезок, несколько — если частота менялась по ходу записи. */
    private class PartResult(val segments: List<ResampledPart>, val rates: List<Int>, val notes: List<String>)

    /** Параллельное чтение не подходит для этого файла или устройства. */
    private class ParallelFallback(message: String, cause: Throwable? = null) : Exception(message, cause)

    private fun probe(uri: Uri): Track {
        val extractor = openExtractor(uri)
        try {
            val index = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioDecodeException("В файле не найден звук: файл повреждён или в нём нет звуковой дорожки")
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            return Track(index, format, format.getString(MediaFormat.KEY_MIME)!!, duration, extractor.sampleTime.coerceAtLeast(0L))
        } finally {
            extractor.release()
        }
    }

    private fun openExtractor(uri: Uri): MediaExtractor {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            extractor.release()
            throw AudioDecodeException("Не удалось открыть файл: формат не поддерживается или файл повреждён", e)
        }
        return extractor
    }

    /**
     * Начала кусков в микросекундах. Параллельно читаются только AAC и Opus: у них на телефоне проверено, что после
     * перемотки время кадров точное. У MP3 и Vorbis оно после перемотки оценочное, куски съезжают на десятки
     * миллисекунд. WAV и FLAC и так читаются быстро.
     */
    private fun planParts(track: Track): List<Long> {
        if (maxParts <= 1 || track.mime !in PARALLEL_MIMES || track.durationUs < MIN_PARALLEL_US) return listOf(0L)
        val count = minOf(maxParts.toLong(), track.durationUs / MIN_PART_US).toInt().coerceAtLeast(1)
        return List(count) { track.firstUs + (track.durationUs - track.firstUs) * it / count }
    }

    private suspend fun decodeSequential(uri: Uri, track: Track, onProgress: (Float) -> Unit): FloatArray {
        lastParts = 1
        val progress = Progress(1, onProgress)
        val spec = PartSpec(0, -1L, Long.MAX_VALUE, track.firstUs, track.durationUs)
        val result = readPart(uri, track, spec, origin = null, progress)
        lastNotes = listOfNotNull(fallbackNote) + result.notes
        fallbackNote = null
        // Как и раньше: если частота менялась, отрезки просто идут друг за другом.
        val total = result.segments.sumOf { it.samples.size.toLong() }
        check(total <= Int.MAX_VALUE - 8) { "Запись слишком длинная для одного массива" }
        val out = FloatArray(total.toInt())
        var offset = 0
        for (segment in result.segments) {
            segment.samples.copyInto(out, offset)
            offset += segment.samples.size
        }
        return out
    }

    private suspend fun decodeParallel(uri: Uri, track: Track, starts: List<Long>, onProgress: (Float) -> Unit): FloatArray {
        val progress = Progress(starts.size, onProgress)
        val specs = starts.indices.map { k ->
            val last = k == starts.lastIndex
            PartSpec(
                index = k,
                seekUs = if (k == 0) -1L else (starts[k] - PREROLL_US).coerceAtLeast(0L),
                untilUs = if (last) Long.MAX_VALUE else starts[k + 1] + POSTROLL_US,
                progressFromUs = starts[k],
                progressToUs = if (last) track.durationUs else starts[k + 1],
            )
        }
        val origin = Origin()
        val results = coroutineScope {
            specs.map { spec -> async { readPart(uri, track, spec, origin, progress) } }.awaitAll()
        }
        lastNotes = results.flatMap { it.notes }
        val rate = results.first().rates.singleOrNull() ?: throw ParallelFallback("частота менялась по ходу записи")
        if (results.any { it.rates != listOf(rate) }) throw ParallelFallback("у кусков разная частота")
        val base = origin.sample ?: throw ParallelFallback("не удалось найти начало записи")
        val cuts = starts.drop(1).map { startUs ->
            cutToOutput(Math.round((startUs - track.firstUs) * rate / 1_000_000.0) - base, rate)
        }
        val audio = try {
            stitchParts(results.map { it.segments.single() }, cuts)
        } catch (e: StitchException) {
            throw ParallelFallback(e.message.orEmpty(), e)
        }
        lastParts = starts.size
        return audio
    }

    /** Читает один кусок своим кодеком в асинхронном режиме. Колбэки кодека приходят в отдельный поток. */
    private suspend fun readPart(uri: Uri, track: Track, spec: PartSpec, origin: Origin?, progress: Progress): PartResult {
        val parallel = origin != null
        val extractor = openExtractor(uri)
        val thread = HandlerThread("audio-decode-${spec.index}").apply { start() }
        var codec: MediaCodec? = null
        val sink = PartSink(track.firstUs, spec.index, origin)
        val done = CompletableDeferred<Unit>()
        try {
            extractor.selectTrack(track.index)
            if (spec.seekUs >= 0) extractor.seekTo(spec.seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val callback = object : MediaCodec.Callback() {
                var inputDone = false

                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) = guard {
                    if (inputDone) return@guard
                    val buffer = mc.getInputBuffer(index) ?: return@guard
                    val size = extractor.readSampleData(buffer, 0)
                    val timeUs = extractor.sampleTime
                    if (size < 0 || timeUs > spec.untilUs) {
                        mc.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        mc.queueInputBuffer(index, 0, size, timeUs, 0)
                        extractor.advance()
                        val span = spec.progressToUs - spec.progressFromUs
                        if (span > 0) progress.update(spec.index, (timeUs - spec.progressFromUs).toFloat() / span)
                    }
                }

                override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) = guard {
                    if (info.size > 0) {
                        val buffer = mc.getOutputBuffer(index)!!
                        buffer.position(info.offset).limit(info.offset + info.size)
                        sink.add(buffer, info.presentationTimeUs, mc)
                    }
                    mc.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done.complete(Unit)
                }

                override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) = guard {
                    sink.layout = PcmLayout.from(format)
                }

                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    done.completeExceptionally(
                        if (parallel) ParallelFallback("ошибка кодека: ${e.diagnosticInfo}", e)
                        else AudioDecodeException("Ошибка кодека при чтении файла", e),
                    )
                }

                private fun guard(block: () -> Unit) {
                    if (done.isCompleted) return
                    sink.touch()
                    try {
                        block()
                    } catch (e: Throwable) {
                        done.completeExceptionally(e)
                    }
                }
            }

            try {
                codec = MediaCodec.createDecoderByType(track.mime)
                codec.setCallback(callback, Handler(thread.looper))
                codec.configure(track.format, null, null, 0)
                codec.start()
            } catch (e: Exception) {
                if (parallel) throw ParallelFallback("не удалось запустить ещё один кодек", e)
                throw AudioDecodeException("Устройство не умеет декодировать формат ${track.mime}", e)
            }

            try {
                while (withTimeoutOrNull(STALL_CHECK_MS) { done.await() } == null) {
                    if (sink.idleNs() > STALL_NS) throw AudioDecodeException("Кодек завис при чтении файла")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AudioDecodeException) {
                throw e
            } catch (e: ParallelFallback) {
                throw e
            } catch (e: Exception) {
                if (parallel) throw ParallelFallback("кусок ${spec.index} не прочитался: $e", e)
                throw AudioDecodeException("Не удалось дочитать файл: он повреждён или обрезан", e)
            }
            return sink.finish()
        } finally {
            done.cancel()
            codec?.let { runCatching { it.stop() }; it.release() }
            thread.quitSafely()
            thread.join(THREAD_JOIN_MS)
            extractor.release()
        }
    }

    /** Общее начало отсчёта для параллельных кусков: его задаёт первый кусок, остальные ждут его, копя звук. */
    private class Origin {
        @Volatile
        var sample: Long? = null
    }

    /**
     * Собирает выход одного кодека: переводит в float, сводит в моно и сразу пересчитывает в 16 кГц.
     *
     * При параллельном чтении нужно знать, какому отсчёту всей записи соответствует начало куска. Время первого
     * кадра после перемотки ненадёжно: кодек часто отдаёт его не целиком. Поэтому начало считается по второму
     * кадру: `его время × частота − длина первого кадра`. На телефоне это проверено для AAC и Opus.
     */
    private class PartSink(private val firstUs: Long, private val part: Int, private val origin: Origin?) {
        val notes = ArrayList<String>()
        var layout: PcmLayout? = null
        private val segments = ArrayList<PartResampler>()
        private var shorts = ShortArray(0)
        private var floats = FloatArray(0)

        // Пока начало куска или общее начало неизвестно, звук копится здесь.
        private var pending: FloatBuffer? = null
        private var pendingRate = 0
        private var firstFrames = 0
        private var firstPtsUs = 0L
        private var anchor: Long? = null

        @Volatile
        private var lastActivity = System.nanoTime()

        fun touch() {
            lastActivity = System.nanoTime()
        }

        fun idleNs(): Long = System.nanoTime() - lastActivity

        fun add(buffer: ByteBuffer, ptsUs: Long, codec: MediaCodec) {
            // Частота и каналы берутся только из формата на выходе: у HE-AAC они отличаются от формата дорожки.
            val pcm = layout ?: PcmLayout.from(codec.outputFormat).also { layout = it }
            val count = toFloats(buffer, pcm.encoding)
            val frames = downmixInto(floats, count, pcm.channels, floats)
            if (frames == 0) return
            val current = segments.lastOrNull()
            when {
                current != null && current.inRate == pcm.sampleRate -> current.push(floats, frames)
                current != null || origin == null -> {
                    // Одним проходом время не нужно: отрезки идут друг за другом, как и раньше.
                    segments += PartResampler(pcm.sampleRate, 0L)
                    segments.last().push(floats, frames)
                }
                else -> collect(ptsUs, frames, pcm.sampleRate)
            }
        }

        /** Параллельный кусок до того, как стал известен его отсчёт: копим звук и ищем привязку. */
        private fun collect(ptsUs: Long, frames: Int, rate: Int) {
            val buffered = pending ?: FloatBuffer().also {
                pending = it
                pendingRate = rate
                firstFrames = frames
                firstPtsUs = ptsUs
            }
            if (rate != pendingRate) throw ParallelFallback("частота сменилась в начале куска $part")
            if (anchor == null && buffered.size > 0) {
                anchor = toSample(ptsUs, rate) - firstFrames
                notes += "кусок $part: первый кадр $firstPtsUs мкс ($firstFrames отсч.), второй $ptsUs мкс, начало ${anchor}"
                if (part == 0) origin!!.sample = anchor
            }
            buffered.add(floats, frames)
            tryStart()
        }

        private fun toSample(ptsUs: Long, rate: Int) = Math.round((ptsUs - firstUs) * rate / 1_000_000.0)

        private fun tryStart() {
            val start = anchor ?: return
            val base = origin!!.sample ?: return
            val buffered = pending!!
            segments += PartResampler(pendingRate, start - base)
            segments.last().push(buffered.toArray(), buffered.size)
            pending = null
        }

        fun finish(): PartResult {
            if (pending != null) {
                if (anchor == null) {
                    // Во всём куске один кадр: привязать можно только по нему.
                    anchor = toSample(firstPtsUs, pendingRate)
                    if (part == 0) origin!!.sample = anchor
                }
                tryStart()
                if (pending != null) throw ParallelFallback("кусок $part закончился раньше, чем стало известно начало записи")
            }
            return PartResult(segments.map { it.finish() }, segments.map { it.inRate }, notes)
        }

        /** Переводит буфер в float в [floats] пачкой и возвращает число значений (все каналы вместе). */
        private fun toFloats(source: ByteBuffer, encoding: Int): Int {
            val bytes = source.slice().order(ByteOrder.LITTLE_ENDIAN)
            return when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> {
                    val n = bytes.remaining() / 2
                    if (shorts.size < n) shorts = ShortArray(n)
                    val out = floats(n)
                    bytes.asShortBuffer().get(shorts, 0, n)
                    for (i in 0 until n) out[i] = shorts[i] / 32768f
                    n
                }
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val n = bytes.remaining() / 4
                    bytes.asFloatBuffer().get(floats(n), 0, n)
                    n
                }
                AudioFormat.ENCODING_PCM_8BIT -> {
                    val n = bytes.remaining()
                    val out = floats(n)
                    for (i in 0 until n) out[i] = ((bytes.get().toInt() and 0xFF) - 128) / 128f
                    n
                }
                ENCODING_PCM_24BIT_PACKED -> {
                    val n = bytes.remaining() / 3
                    val out = floats(n)
                    for (i in 0 until n) {
                        val low = bytes.get().toInt() and 0xFF
                        val middle = bytes.get().toInt() and 0xFF
                        val high = bytes.get().toInt()
                        out[i] = ((high shl 16) or (middle shl 8) or low) / 8388608f
                    }
                    n
                }
                ENCODING_PCM_32BIT -> {
                    val n = bytes.remaining() / 4
                    val out = floats(n)
                    for (i in 0 until n) out[i] = (bytes.getInt() / 2147483648.0).toFloat()
                    n
                }
                else -> throw AudioDecodeException("Неизвестный формат PCM: $encoding")
            }
        }

        private fun floats(n: Int): FloatArray {
            if (floats.size < n) floats = FloatArray(n)
            return floats
        }
    }

    private class PcmLayout(val sampleRate: Int, val channels: Int, val encoding: Int) {
        companion object {
            fun from(format: MediaFormat) = PcmLayout(
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                },
            )
        }
    }

    /** Общий прогресс по всем кускам. Колбэки приходят из разных потоков, а наружу уходит не чаще чем на 1 %. */
    private class Progress(parts: Int, private val onProgress: (Float) -> Unit) {
        private val fractions = FloatArray(parts)
        private var reported = 0f

        fun update(part: Int, fraction: Float) {
            val total = synchronized(this) {
                fractions[part] = fraction.coerceIn(0f, 1f)
                val average = fractions.average().toFloat()
                if (average < reported + 0.01f) return
                reported = average
                average
            }
            onProgress(total)
        }
    }

    private companion object {
        const val TAG = "AudioDecoder"
        const val DEFAULT_PARTS = 4
        val PARALLEL_MIMES = setOf(MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_OPUS)
        const val MIN_PARALLEL_US = 20_000_000L
        const val MIN_PART_US = 10_000_000L
        const val PREROLL_US = 500_000L
        const val POSTROLL_US = 300_000L
        const val STALL_CHECK_MS = 500L
        const val STALL_NS = 15_000_000_000L
        const val THREAD_JOIN_MS = 1_000L

        // Константы AudioFormat из API 31, значения фиксированы платформой.
        const val ENCODING_PCM_24BIT_PACKED = 21
        const val ENCODING_PCM_32BIT = 22
    }
}
