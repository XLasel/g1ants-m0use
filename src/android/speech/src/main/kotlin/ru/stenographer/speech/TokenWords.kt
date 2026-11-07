package ru.stenographer.speech

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** У токена есть только начало, поэтому длину слова приходится ограничивать, иначе оно займёт всю паузу после себя. */
internal const val MAX_WORD_MS = 1_000L

/**
 * Собирает токены GigaAM в слова. Новое слово начинается с пробела или `▁`,
 * а пунктуация приходит без них и прилипает к предыдущему слову.
 *
 * [timestamps] заданы в секундах от начала куска. [chunkStartMs] и [chunkEndMs] заданы от начала записи.
 */
internal fun tokensToWords(
    tokens: Array<String>,
    timestamps: FloatArray,
    chunkStartMs: Long,
    chunkEndMs: Long,
): List<Word> {
    val texts = ArrayList<StringBuilder>()
    val starts = ArrayList<Long>()
    for (i in 0 until min(tokens.size, timestamps.size)) {
        val token = tokens[i]
        if (texts.isEmpty() || token.startsWith(' ') || token.startsWith('▁')) {
            texts += StringBuilder(token.trimStart(' ', '▁'))
            starts += chunkStartMs + (timestamps[i] * 1000.0).roundToLong()
        } else {
            texts.last().append(token)
        }
    }
    return texts.indices.mapNotNull { i ->
        val limit = if (i + 1 < starts.size) starts[i + 1] else chunkEndMs
        val end = max(starts[i], min(limit, starts[i] + MAX_WORD_MS))
        texts[i].toString().takeIf { it.isNotEmpty() }?.let { Word(it, starts[i], end) }
    }
}
