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

/**
 * Модель отдаёт конспект текстом «Тема / Решения / Задачи» с пунктами через «•».
 * Пункты — дословные фрагменты реплик, поэтому время и голос берём из расшифровки.
 * Если формат другой, возвращаем null и показываем текст как есть.
 */
fun summarySections(rec: Recording): List<SummarySection>? {
    val s = rec.summary ?: return null
    val blocks = s.text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
    val sections = blocks.mapNotNull { block ->
        val lines = block.lines()
        val header = lines.first().trim()
        val title = SECTION_TITLES[header] ?: return null
        val items = lines.drop(1)
            .map { it.trim().removePrefix("•").trim() }
            .filter { it.isNotEmpty() && it != "—" }
            .map { text ->
                val at = s.points.firstOrNull { it.text == text }?.sourceStartMs
                SummaryItem(text, at, at?.let { t -> rec.utterances.firstOrNull { t in it.startMs..it.endMs }?.speakerId })
            }
        SummarySection(title, items, withSpeaker = header == "Задачи").takeIf { items.isNotEmpty() }
    }
    return sections.ifEmpty { null }
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
