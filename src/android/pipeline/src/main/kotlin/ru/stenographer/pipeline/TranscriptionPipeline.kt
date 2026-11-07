package ru.stenographer.pipeline

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import ru.stenographer.core.PipelineEvent
import ru.stenographer.core.PipelineEvent.Decoded
import ru.stenographer.core.PipelineEvent.Done
import ru.stenographer.core.PipelineEvent.Failed
import ru.stenographer.core.PipelineEvent.Segments
import ru.stenographer.core.PipelineEvent.SpeakerFound
import ru.stenographer.core.PipelineEvent.StageProgress
import ru.stenographer.core.PipelineEvent.Summary
import ru.stenographer.core.PipelineEvent.Words
import ru.stenographer.core.SAMPLE_RATE
import ru.stenographer.core.Stage
import ru.stenographer.core.Utterance
import ru.stenographer.diarization.ChunkPlanner
import ru.stenographer.diarization.NemotronDiarizer
import ru.stenographer.diarization.TranscriptMerger
import ru.stenographer.speech.AudioDecodeException
import ru.stenographer.speech.MediaCodecAudioDecoder
import ru.stenographer.speech.ModelInstaller
import ru.stenographer.speech.SherpaRecognizer
import ru.stenographer.speech.loudnessEnvelope
import ru.stenographer.summary.SourceUtterance
import ru.stenographer.summary.android.summarizeLocal
import java.io.File
import ru.stenographer.core.SummaryPoint as CoreSummaryPoint

/**
 * Настройки обработки.
 *
 * @param threads потоков для моделей; 4 — проверено на Pixel 7 Pro
 * @param summaryModel GGUF-модель для названия и конспекта; null — конспект не делается, событие Summary не придёт
 */
data class PipelineConfig(
    val threads: Int = 4,
    val summaryModel: File? = null,
    val summaryContextSize: Int = 4096,
)

/**
 * Обрабатывает один файл и отдаёт события для интерфейса по контракту из `:core` ([PipelineEvent]).
 *
 * Порядок: чтение → диаризация (голоса и отрезки приходят по ходу) → нарезка речи → распознавание (слова кусками)
 * → реплики ([Done]) → конспект ([Summary], если задана модель). Модели загружаются по очереди и закрываются
 * после своего этапа, чтобы в памяти не лежало всё сразу. Файлы обрабатываются по одному: очередь держит вызывающий.
 *
 * Поток холодный: обработка начинается при подписке и прерывается её отменой (между этапами и кусками).
 * Ошибка приходит событием [Failed], исключение наружу не выбрасывается. Ошибка конспекта не портит
 * готовую расшифровку: [Done] уже отдан, [Summary] просто не придёт.
 *
 * ```kotlin
 * TranscriptionPipeline(context).process(fileId, uri).collect { event -> /* обновить экран */ }
 * ```
 */
class TranscriptionPipeline(context: Context, private val config: PipelineConfig = PipelineConfig()) {
    private val context = context.applicationContext

    fun process(fileId: String, uri: Uri): Flow<PipelineEvent> = channelFlow {
        val progress = ProgressThrottle { stage, value -> trySend(StageProgress(fileId, stage, value)) }
        try {
            // 1. Чтение файла
            progress.start(Stage.DECODE)
            val audio = MediaCodecAudioDecoder(context).decode(uri) { progress.report(Stage.DECODE, it) }
            progress.finish(Stage.DECODE)
            send(Decoded(fileId, audio.size * 1000L / SAMPLE_RATE, loudnessEnvelope(audio)))

            // 2. Диаризация: голоса и отрезки отдаются после каждого куска 27 с
            progress.start(Stage.DIARIZE)
            val diarizerDir = NemotronInstaller(context).install()
            val diarization = withContext(Dispatchers.Default) {
                NemotronDiarizer.open(diarizerDir, config.threads).use { diarizer ->
                    diarizer.diarizeWithSegments(audio, onProgress = { p, _ -> progress.report(Stage.DIARIZE, p) }) { update ->
                        update.newSpeakers.forEach { trySend(SpeakerFound(fileId, it.speakerId, it.firstAtMs)) }
                        if (update.segments.isNotEmpty() || update.final) {
                            trySend(Segments(fileId, update.segments, update.final))
                        }
                    }
                }
            }
            progress.finish(Stage.DIARIZE)

            // 3. Распознавание по кускам речи из диаризации; Nemotron уже закрыт
            progress.start(Stage.RECOGNIZE)
            val chunks = ChunkPlanner.plan(diarization.probs, audio)
            val asrDir = ModelInstaller(context).install()
            val words = SherpaRecognizer.open(asrDir, config.threads).use { asr ->
                asr.transcribe(
                    audio,
                    chunks,
                    onProgress = { progress.report(Stage.RECOGNIZE, it) },
                    onChunk = { chunkWords, processedUntilMs -> trySend(Words(fileId, chunkWords, processedUntilMs)) },
                )
            }
            progress.finish(Stage.RECOGNIZE)

            // 4. Реплики: спикер каждому слову, номера те же, что у найденных голосов
            progress.start(Stage.MERGE)
            val utterances = withContext(Dispatchers.Default) {
                TranscriptMerger.merge(words, diarization.probs, diarization.speakers)
            }
            progress.finish(Stage.MERGE)
            send(Done(fileId, utterances))

            // 5. Название и конспект. Расшифровка уже отдана, ошибка здесь её не отменяет.
            config.summaryModel?.let { model -> summarize(fileId, model, utterances, progress)?.let { send(it) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Обработка $fileId не удалась", e)
            send(Failed(fileId, reasonOf(e)))
        }
    }.buffer(Channel.UNLIMITED) // события из колбэков моделей не теряются, даже если интерфейс занят

    /** То же для файла, например записи из приложения. */
    fun process(fileId: String, file: File): Flow<PipelineEvent> = process(fileId, Uri.fromFile(file))

    private suspend fun summarize(
        fileId: String,
        model: File,
        utterances: List<Utterance>,
        progress: ProgressThrottle,
    ): Summary? {
        if (utterances.isEmpty()) return null
        return try {
            progress.start(Stage.SUMMARY)
            val source = utterances.map { SourceUtterance(it.speakerId, it.startMs, it.endMs, it.text) }
            val result = summarizeLocal(model, source, config.summaryContextSize, config.threads) {
                progress.report(Stage.SUMMARY, it)
            }
            progress.finish(Stage.SUMMARY)
            Summary(fileId, result.title, result.text, result.points.map {
                CoreSummaryPoint(it.text, it.sourceStartMs, it.kind, it.speakerId)
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Конспект для $fileId не готов, расшифровка сохранена", e)
            null
        }
    }

    private fun reasonOf(e: Throwable): String = when (e) {
        is AudioDecodeException -> e.message ?: "Не удалось прочитать файл"
        is OutOfMemoryError -> "Не хватило памяти: запись слишком длинная для этого телефона"
        else -> "Не удалось обработать файл: ${e.message ?: e.javaClass.simpleName}"
    }

    private companion object {
        const val TAG = "TranscriptionPipeline"
    }
}
