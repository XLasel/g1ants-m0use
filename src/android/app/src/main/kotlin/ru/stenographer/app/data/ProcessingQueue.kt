package ru.stenographer.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.stenographer.app.pipeline.LocalModels
import ru.stenographer.core.PipelineEvent
import ru.stenographer.core.Segment
import ru.stenographer.core.Stage
import ru.stenographer.core.Word
import java.io.File

/** То, что видно на экране обработки, пока запись ещё не готова. */
data class LiveProgress(
    val recordingId: String,
    val stage: Stage = Stage.DECODE,
    val stageProgress: Float = 0f,
    val durationMs: Long = 0,
    val envelope: FloatArray = FloatArray(0),
    val speakers: List<String> = emptyList(),
    val segments: List<Segment> = emptyList(),
    val words: List<Word> = emptyList(),
    val processedUntilMs: Long = 0,
) {
    /**
     * Прогресс расшифровки 0..1. Доли этапов — по замеру на Pixel 7 Pro (3 минуты разговора):
     * чтение 14 с, голоса 9,5 с, распознавание 13 с, склейка мгновенно. Конспект идёт после того,
     * как расшифровка открылась, и в эту полосу не входит.
     */
    val overall: Float
        get() {
            val weights = mapOf(Stage.DECODE to .38f, Stage.DIARIZE to .26f, Stage.RECOGNIZE to .35f, Stage.MERGE to .01f, Stage.SUMMARY to 0f)
            val done = Stage.entries.takeWhile { it != stage }.sumOf { weights.getValue(it).toDouble() }.toFloat()
            return (done + weights.getValue(stage) * stageProgress).coerceIn(0f, 1f)
        }
}

/**
 * Очередь обработки: записи обрабатываются по одной, в порядке добавления.
 * Живёт в процессе приложения, а не на экране, поэтому обработка
 * продолжается, когда пользователь ушёл на главную или свернул приложение
 * (процесс при этом держит ProcessingService).
 */
class ProcessingQueue(
    private val repository: RecordingsRepository,
    private val models: LocalModels,
    private val settings: AppSettings,
    scope: CoroutineScope,
) {
    private val channel = Channel<String>(Channel.UNLIMITED)

    private val _live = MutableStateFlow<LiveProgress?>(null)
    val live: StateFlow<LiveProgress?> = _live.asStateFlow()

    /** Сколько записей ждёт или обрабатывается; сервис живёт, пока здесь не ноль. */
    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending.asStateFlow()

    init {
        scope.launch {
            for (id in channel) {
                runCatching { process(id) }.onFailure { e ->
                    repository.update(id) { it.copy(status = Recording.Status.FAILED, error = e.message ?: "Не удалось обработать") }
                }
                _live.value = null
                _pending.update { it - 1 }
            }
        }
        // Записи, которые не успели обработаться в прошлый раз.
        repository.recordings.value.filter { it.status == Recording.Status.QUEUED }.reversed().forEach { enqueue(it.id) }
    }

    fun enqueue(id: String) {
        _pending.update { it + 1 }
        channel.trySend(id)
    }

    private suspend fun process(id: String) {
        val rec = repository.get(id) ?: return
        repository.update(id) { it.copy(status = Recording.Status.PROCESSING, error = null) }
        _live.value = LiveProgress(id)
        // Конспект делаем, только если он включён и модель для него есть в приложении.
        val withSummary = settings.summaryEnabled && models.summaryAvailable()

        models.process(id, File(rec.audioPath), withSummary).collect { e ->
            when (e) {
                is PipelineEvent.StageProgress -> _live.update { it?.copy(stage = e.stage, stageProgress = e.progress) }
                is PipelineEvent.Decoded -> {
                    _live.update { it?.copy(durationMs = e.durationMs, envelope = e.envelope) }
                    repository.update(id) { r -> r.copy(durationMs = e.durationMs, envelope = downsample(e.envelope)) }
                }
                is PipelineEvent.SpeakerFound -> {
                    _live.update { it?.copy(speakers = it.speakers + e.speakerId) }
                    repository.update(id) { r ->
                        if (r.speaker(e.speakerId) != null) r
                        else r.copy(speakers = r.speakers + Speaker(e.speakerId, "Голос ${r.speakers.size + 1}", r.speakers.size))
                    }
                }
                // Отрезки приходят порциями: в каждой только новые, закрытые отрезки.
                is PipelineEvent.Segments -> _live.update { it?.copy(segments = it.segments + e.segments) }
                // Высота голоса пока не используется: узор выбирается по номеру голоса.
                is PipelineEvent.SpeakerFeatures -> Unit
                is PipelineEvent.Words -> _live.update { it?.copy(words = it.words + e.words, processedUntilMs = e.processedUntilMs) }
                is PipelineEvent.Summary -> repository.update(id) { r ->
                    r.copy(
                        summary = Summary(e.title, e.text, e.points),
                        summaryPending = false,
                        title = if (r.titleEdited) r.title else e.title,
                    )
                }
                is PipelineEvent.Done -> repository.update(id) { r ->
                    r.copy(status = Recording.Status.DONE, utterances = e.utterances, summaryPending = withSummary)
                }
                is PipelineEvent.Failed -> repository.update(id) { r ->
                    r.copy(status = Recording.Status.FAILED, error = e.reason)
                }
            }
        }
        // Конспект мог не получиться: расшифровка при этом остаётся, ждать больше нечего.
        repository.update(id) { if (it.summaryPending) it.copy(summaryPending = false) else it }
    }

    /** Для хранения хватает 600 точек огибающей: дорожка на экране примерно такой ширины. */
    private fun downsample(env: FloatArray, n: Int = 600): List<Float> {
        if (env.size <= n) return env.toList()
        val step = env.size / n.toFloat()
        return List(n) { i ->
            val from = (i * step).toInt()
            val to = ((i + 1) * step).toInt().coerceAtMost(env.size)
            var m = 0f
            for (j in from until to) m = maxOf(m, env[j])
            m
        }
    }
}
