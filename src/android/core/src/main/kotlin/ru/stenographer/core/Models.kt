package ru.stenographer.core

/** Частота звука, которую ждут модели: 16 кГц, моно. */
const val SAMPLE_RATE = 16_000

/** Распознанное слово. Время в миллисекундах от начала записи. */
data class Word(val text: String, val startMs: Long, val endMs: Long)

/**
 * Отрезок, где говорит спикер. Отрезки разных спикеров могут пересекаться:
 * на перекрытиях работают перебивания и соло голоса.
 */
data class Segment(val speakerId: String, val startMs: Long, val endMs: Long)

/**
 * Реплика: подряд идущие слова одного спикера. [speakerId] — `speaker_1`, `speaker_2`... в порядке первого появления,
 * эти же id уходят в JSON задания. [words] нужны интерфейсу для подсветки текущего слова, в JSON не попадают.
 */
data class Utterance(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<Word> = emptyList(),
)

/** Пункт конспекта: раздел topic/decision/task, время и голос исходной реплики. */
data class SummaryPoint(
    val text: String,
    val sourceStartMs: Long,
    val kind: String = "topic",
    val speakerId: String? = null,
)

/** Этапы обработки одного файла, по порядку. */
enum class Stage { DECODE, DIARIZE, RECOGNIZE, MERGE, SUMMARY }
