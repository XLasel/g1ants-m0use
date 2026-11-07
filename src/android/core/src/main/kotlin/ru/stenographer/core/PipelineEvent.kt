package ru.stenographer.core

/**
 * События обработки одного файла для интерфейса, контракт из чекпоинта команды.
 *
 * `:pipeline` отдаёт их потоком (`Flow<PipelineEvent>`), интерфейс подписывается и рисует. Порядок:
 * [Decoded] → [SpeakerFound] и [Segments] по ходу диаризации → [Words] по кускам распознавания → [Done] →
 * [Summary], если включён конспект. [StageProgress] идёт всё время, не чаще 10 раз в секунду на этап.
 * При ошибке приходит [Failed], после него событий по этому файлу нет.
 */
sealed interface PipelineEvent {
    val fileId: String

    /** Файл прочитан. [envelope] — громкость от 0 до 1, одно значение на 50 мс. */
    data class Decoded(override val fileId: String, val durationMs: Long, val envelope: FloatArray) : PipelineEvent {
        override fun equals(other: Any?): Boolean = other is Decoded && other.fileId == fileId &&
            other.durationMs == durationMs && other.envelope.contentEquals(envelope)

        override fun hashCode(): Int = (fileId.hashCode() * 31 + durationMs.hashCode()) * 31 + envelope.contentHashCode()
    }

    /** Прогресс этапа, [progress] от 0 до 1 внутри этапа. */
    data class StageProgress(override val fileId: String, val stage: Stage, val progress: Float) : PipelineEvent

    /** Диаризация нашла новый голос, он впервые прозвучал в [firstAtMs]. */
    data class SpeakerFound(override val fileId: String, val speakerId: String, val firstAtMs: Long) : PipelineEvent

    /** Порция отрезков голосов. Последняя порция — с [final] = true. */
    data class Segments(override val fileId: String, val segments: List<Segment>, val final: Boolean) : PipelineEvent

    /** Характеристики голоса для узора (по желанию, пока не отдаётся). */
    data class SpeakerFeatures(
        override val fileId: String,
        val speakerId: String,
        val pitchMedianHz: Float,
        val pitchSpread: Float,
        val loudnessMean: Float,
    ) : PipelineEvent

    /** Слова очередного распознанного куска; запись распознана до [processedUntilMs]. */
    data class Words(override val fileId: String, val words: List<Word>, val processedUntilMs: Long) : PipelineEvent

    /** Название записи (3–6 слов), конспект и пункты со ссылками на моменты записи. JSON задания не меняет. */
    data class Summary(
        override val fileId: String,
        val title: String,
        val text: String,
        val points: List<SummaryPoint>,
    ) : PipelineEvent

    /** Расшифровка готова: реплики со спикерами. Их же экспортирует [TranscriptJson]. */
    data class Done(override val fileId: String, val utterances: List<Utterance>) : PipelineEvent

    /** Обработка файла не удалась, [reason] можно показать пользователю. */
    data class Failed(override val fileId: String, val reason: String) : PipelineEvent
}
