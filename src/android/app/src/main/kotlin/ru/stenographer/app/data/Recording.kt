package ru.stenographer.app.data

import kotlinx.serialization.Serializable
import ru.stenographer.core.SummaryPoint
import ru.stenographer.core.Utterance

/** Запись разговора и всё, что из неё получилось. Хранится в recordings.json. */
@Serializable
data class Recording(
    val id: String,
    /** Название: сначала имя файла или дата, после конспекта его предлагает модель. */
    val title: String,
    /** true, если название меняли руками: тогда модель его не трогает. */
    val titleEdited: Boolean = false,
    /** Имя исходного файла без расширения: по нему называем экспорт, чтобы JSON легко сопоставить с аудио. */
    val sourceName: String? = null,
    val createdAt: Long,
    /** Файл звука внутри приложения (копия импортированного или записанного). */
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
 * Голос в записи. index задаёт цвет и узор по умолчанию,
 * petals считается из высоты голоса, когда обработка её сообщит.
 */
@Serializable
data class Speaker(
    val id: String,
    val name: String,
    val index: Int,
    val petals: Int = 0,
)

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
    return copy(utterances = joined, speakers = speakers.filter { it.id != from })
}
