package ru.stenographer.core

/**
 * Экспорт в формат задания (слайд 15): `{"transcript": [{"speaker_id", "start_ms", "end_ms", "text"}]}`.
 * По этому файлу эксперты считают точность, поэтому лишних полей в нём нет.
 */
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
