package ru.stenographer.app.data

import kotlinx.serialization.Serializable
import ru.stenographer.core.SummaryPoint
import ru.stenographer.core.Utterance

/** Запись разговора и всё, что из неё получилось. Хранится в recordings.json. */
@Serializable
data class Recording(
    val id: String,
    val title: String,
    /** true, если название меняли руками: тогда модель его не трогает. */
    val titleEdited: Boolean = false,
    /** Имя исходного файла без расширения: по нему называем экспорт, чтобы JSON легко сопоставить с аудио. */
    val sourceName: String? = null,
    val createdAt: Long,
    val audioPath: String,
    val durationMs: Long = 0,
    val status: Status = Status.QUEUED,
    val error: String? = null,
    val utterances: List<@Serializable(UtteranceSerializer::class) Utterance> = emptyList(),
    val speakers: List<Speaker> = emptyList(),
    val summary: Summary? = null,
    /** Расшифровка готова, а модель ещё пишет конспект. */
    val summaryPending: Boolean = false,
    /** Огибающая громкости с шагом 50 мс, для дорожек и обложки. */
    val envelope: List<Float> = emptyList(),
) {
    enum class Status { QUEUED, PROCESSING, DONE, FAILED }

    fun speaker(id: String): Speaker? = speakers.firstOrNull { it.id == id }
}

/**
 * Голос в записи. index — порядок появления, задаёт цвет.
 * Узор зависит от высоты голоса: [shape] — место голоса среди остальных от самого низкого к самому высокому.
 * Пока обработка не посчитала высоту, узор выбирается по index.
 */
@Serializable
data class Speaker(
    val id: String,
    val name: String,
    val index: Int,
    val pitchHz: Float? = null,
    val shape: Int? = null,
)

/** Раздаёт узоры по высоте голосов: низкий голос — меньше лепестков, высокий — больше. */
fun List<Speaker>.withShapesByPitch(): List<Speaker> {
    val ranks = filter { it.pitchHz != null }.sortedBy { it.pitchHz }.mapIndexed { rank, s -> s.id to rank }.toMap()
    return map { s -> ranks[s.id]?.let { s.copy(shape = it) } ?: s }
}

@Serializable
data class Summary(val title: String, val text: String, val points: List<@Serializable(SummaryPointSerializer::class) SummaryPoint>)

/** Если две соседние реплики одного голоса разделяет пауза короче этой, это одна реплика. */
private const val JOIN_GAP_MS = 2_000L

/**
 * Объединяет голос [from] с голосом [into]: модель иногда делит одного человека на два голоса.
 * Соседние реплики, которые теперь принадлежат одному голосу, склеиваются в одну.
 */
fun Recording.mergeSpeaker(from: String, into: String): Recording {
    if (from == into || speaker(into) == null) return this
    val joined = mutableListOf<Utterance>()
    for (u in utterances.map { if (it.speakerId == from) it.copy(speakerId = into) else it }) {
        val last = joined.lastOrNull()
        if (last != null && last.speakerId == u.speakerId && u.startMs - last.endMs <= JOIN_GAP_MS) {
            joined[joined.lastIndex] = last.copy(
                endMs = maxOf(last.endMs, u.endMs),
                text = last.text + " " + u.text,
                words = last.words + u.words,
            )
        } else {
            joined += u
        }
    }
    val updatedSummary = summary?.copy(points = summary.points.map {
        if (it.speakerId == from) it.copy(speakerId = into) else it
    })
    return copy(utterances = joined, speakers = speakers.filter { it.id != from }, summary = updatedSummary)
}
