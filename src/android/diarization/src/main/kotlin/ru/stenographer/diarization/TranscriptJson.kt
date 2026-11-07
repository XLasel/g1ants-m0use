package ru.stenographer.diarization

/**
 * Перенумеровывает спикеров по порядку первой реплики: speaker_1 — тот, кто заговорил первым, номера без пропусков.
 * Nemotron и так нумерует по появлению, но столбец, не попавший ни в одно слово, оставил бы дыру в номерах.
 */
fun List<Utterance>.renumberSpeakers(): List<Utterance> {
    val order = HashMap<Int, Int>()
    for (u in sortedBy { it.startMs }) order.getOrPut(u.speaker) { order.size }
    return map { u ->
        val speaker = order.getValue(u.speaker)
        u.copy(speaker = speaker, words = u.words.map { it.copy(speaker = speaker) })
    }
}

/** Экспорт в формат задания (слайд 15): `{"transcript": [{"speaker_id", "start_ms", "end_ms", "text"}]}`. */
object TranscriptJson {
    fun encode(utterances: List<Utterance>, indent: Boolean = true): String {
        val nl = if (indent) "\n" else ""
        val pad = if (indent) "    " else ""
        val items = utterances.sortedBy { it.startMs }.joinToString(",$nl") { u ->
            "$pad{\"speaker_id\": ${quote(u.speakerId)}, \"start_ms\": ${u.startMs}, " +
                "\"end_ms\": ${u.endMs}, \"text\": ${quote(u.text)}}"
        }
        return "{$nl${if (indent) "  " else ""}\"transcript\": [$nl$items$nl${if (indent) "  " else ""}]$nl}$nl"
    }

    internal fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' || c == ' ' || c == ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
