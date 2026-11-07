package ru.stenographer.app.data

import ru.stenographer.app.export.formatTime

/** Пункт конспекта: текст из расшифровки, момент записи и голос, который это сказал. */
data class SummaryItem(val text: String, val atMs: Long?, val speakerId: String?)

data class SummarySection(val title: String, val items: List<SummaryItem>, val withSpeaker: Boolean)

/**
 * Заголовки разделов, которые пишет модель конспекта, и как их показываем.
 * Слова попроще, чем «решения и задачи»: запись бывает и не рабочей. Пустые разделы не показываются.
 */
private val SECTION_TITLES = mapOf("Тема" to "О чём разговор", "Решения" to "Договорились", "Задачи" to "Кто что сделает")
private val SECTION_KINDS = mapOf("Тема" to "topic", "Решения" to "decision", "Задачи" to "task")

/**
 * Новые пункты уже содержат раздел и точную привязку к голосу. Одинаковые фразы
 * разных участников поэтому не путаются. Для сохранённых записей старого формата
 * разбираем текст «Тема / Решения / Задачи» и восстанавливаем голос по времени.
 * Если старый формат не распознан, возвращаем null и показываем текст как есть.
 */
fun summarySections(rec: Recording): List<SummarySection>? {
    val s = rec.summary ?: return null
    if (s.points.isNotEmpty() && s.points.all { it.kind in SECTION_KINDS.values && !it.speakerId.isNullOrBlank() }) {
        return SECTION_TITLES.mapNotNull { (header, title) ->
            val items = s.points.filter { it.kind == SECTION_KINDS.getValue(header) }
                .map { SummaryItem(it.text, it.sourceStartMs, summarySpeaker(rec, it.text, it.sourceStartMs, it.speakerId)) }
            SummarySection(title, items, withSpeaker = header == "Задачи").takeIf { items.isNotEmpty() }
        }.ifEmpty { null }
    }
    val blocks = s.text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
    val sections = blocks.mapNotNull { block ->
        val lines = block.lines()
        val header = lines.first().trim()
        val title = SECTION_TITLES[header] ?: return null
        val items = lines.drop(1)
            .map { it.trim().removePrefix("•").trim() }
            .filter { it.isNotEmpty() && it != "—" }
            .map { text ->
                val point = s.points.firstOrNull { it.text == text }
                val at = point?.sourceStartMs
                SummaryItem(text, at, summarySpeaker(rec, text, at, point?.speakerId))
            }
        SummarySection(title, items, withSpeaker = header == "Задачи").takeIf { items.isNotEmpty() }
    }
    return sections.ifEmpty { null }
}

/** Если голос уже объединили до прихода конспекта, ищем его в актуальных репликах. */
private fun summarySpeaker(rec: Recording, text: String, at: Long?, speakerId: String?): String? =
    speakerId?.takeIf { rec.speaker(it) != null } ?: at?.let { t ->
        rec.utterances.firstOrNull { it.startMs == t && it.text.contains(text) }?.speakerId
            ?: rec.utterances.firstOrNull { t in it.startMs..it.endMs && it.text.contains(text) }?.speakerId
            ?: rec.utterances.firstOrNull { t in it.startMs..it.endMs }?.speakerId
    }

/** Конспект обычным текстом: чтобы скопировать или отправить в мессенджер. */
fun summaryText(rec: Recording): String? {
    val s = rec.summary ?: return null
    val sections = summarySections(rec) ?: return "${rec.title}\n\n${s.text}"
    return buildString {
        appendLine(rec.title)
        sections.forEach { sec ->
            appendLine()
            appendLine(sec.title)
            sec.items.forEach { item ->
                val who = if (sec.withSpeaker) item.speakerId?.let { rec.speaker(it)?.name }?.let { "$it: " } ?: "" else ""
                val at = item.atMs?.let { " (${formatTime(it)})" } ?: ""
                appendLine("• $who${item.text}$at")
            }
        }
    }.trimEnd()
}
