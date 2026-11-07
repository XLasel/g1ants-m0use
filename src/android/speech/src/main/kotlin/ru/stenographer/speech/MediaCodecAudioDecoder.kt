package ru.stenographer.speech

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Читает m4a, mp3, wav, ogg, flac и звук из видео теми кодеками, что есть на устройстве. */
class MediaCodecAudioDecoder(private val context: Context) : AudioDecoder {

    override suspend fun decode(uri: Uri, onProgress: (Float) -> Unit): FloatArray = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                throw AudioDecodeException("Не удалось открыть файл: формат не поддерживается или файл повреждён", e)
            }
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioDecodeException("В файле не найден звук: файл повреждён или в нём нет звуковой дорожки")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val codec = try {
                MediaCodec.createDecoderByType(mime).apply {
                    configure(format, null, null, 0)
                    start()
                }
            } catch (e: Exception) {
                throw AudioDecodeException("Устройство не умеет декодировать формат $mime", e)
            }
            try {
                val capacity = if (durationUs > 0) (durationUs * SAMPLE_RATE / 1_000_000L + SAMPLE_RATE).coerceAtMost(Int.MAX_VALUE - 8L).toInt() else 1 shl 20
                val result = FloatBuffer(capacity)
                pump(extractor, codec, durationUs, result, onProgress)
                if (result.size == 0) throw AudioDecodeException("Из файла не удалось получить звук")
                onProgress(1f)
                result.toArray()
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private suspend fun pump(
        extractor: MediaExtractor,
        codec: MediaCodec,
        durationUs: Long,
        result: FloatBuffer,
        onProgress: (Float) -> Unit,
    ) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var idleSince = System.nanoTime()
        // Частота и каналы берутся только из формата на выходе: у HE-AAC они отличаются от формата дорожки.
        var pcm: PcmLayout? = null
        var resampler: Resampler? = null

        fun applyFormat(next: PcmLayout) {
            if (next.sampleRate != pcm?.sampleRate) {
                resampler?.let { result.add(it.finish()) }
                resampler = Resampler(next.sampleRate, SAMPLE_RATE)
            }
            pcm = next
        }

        while (true) {
            currentCoroutineContext().ensureActive()
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val timeUs = extractor.sampleTime
                        codec.queueInputBuffer(index, 0, size, timeUs, 0)
                        extractor.advance()
                        if (durationUs > 0) onProgress((timeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                    idleSince = System.nanoTime()
                }
            }

            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> applyFormat(PcmLayout.from(codec.outputFormat))
                index >= 0 -> {
                    idleSince = System.nanoTime()
                    if (info.size > 0) {
                        if (pcm == null) applyFormat(PcmLayout.from(codec.outputFormat))
                        // Позиция и предел буфера уже указывают на готовые данные.
                        val samples = readSamples(codec.getOutputBuffer(index)!!, pcm!!.encoding)
                        result.add(resampler!!.process(downmix(samples, samples.size, pcm!!.channels)))
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        resampler?.let { result.add(it.finish()) }
                        return
                    }
                }
            }
            if (System.nanoTime() - idleSince > STALL_NS) throw AudioDecodeException("Кодек завис при чтении файла")
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

    private fun readSamples(source: ByteBuffer, encoding: Int): FloatArray {
        val bytes = source.slice().order(ByteOrder.LITTLE_ENDIAN)
        return when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> FloatArray(bytes.remaining() / 2) { bytes.getShort() / 32768f }
            AudioFormat.ENCODING_PCM_FLOAT -> FloatArray(bytes.remaining() / 4) { bytes.getFloat() }
            AudioFormat.ENCODING_PCM_8BIT -> FloatArray(bytes.remaining()) { ((bytes.get().toInt() and 0xFF) - 128) / 128f }
            ENCODING_PCM_24BIT_PACKED -> FloatArray(bytes.remaining() / 3) {
                val low = bytes.get().toInt() and 0xFF
                val middle = bytes.get().toInt() and 0xFF
                val high = bytes.get().toInt()
                ((high shl 16) or (middle shl 8) or low) / 8388608f
            }
            ENCODING_PCM_32BIT -> FloatArray(bytes.remaining() / 4) { (bytes.getInt() / 2147483648.0).toFloat() }
            else -> throw AudioDecodeException("Неизвестный формат PCM: $encoding")
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val STALL_NS = 15_000_000_000L
        // Константы AudioFormat из API 31, значения фиксированы платформой.
        const val ENCODING_PCM_24BIT_PACKED = 21
        const val ENCODING_PCM_32BIT = 22
    }
}
