package ru.stenographer.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenWordsTest {
    @Test fun `пробел начинает слово, а пунктуация прилипает`() {
        val words = tokensToWords(arrayOf(" при", "вет", ",", " мир", "."), floatArrayOf(0.1f, 0.2f, 0.3f, 0.5f, 0.7f), 10_000, 11_000)
        assertEquals(listOf("привет,", "мир."), words.map { it.text })
        assertEquals(listOf(10_100L, 10_500L), words.map { it.startMs })
        assertEquals(listOf(10_500L, 11_000L), words.map { it.endMs })
    }

    @Test fun `первый токен без пробела тоже становится словом`() {
        assertEquals(listOf("да"), tokensToWords(arrayOf("да"), floatArrayOf(0f), 0, 500).map { it.text })
    }

    @Test fun `слово перед паузой ограничено секундой`() {
        val words = tokensToWords(arrayOf(" а", " б"), floatArrayOf(0f, 5f), 0, 6_000)
        assertEquals(1_000L, words[0].endMs)
        assertEquals(6_000L, words[1].endMs)
    }

    @Test fun `пустые токены не дают слов`() {
        assertEquals(emptyList<Word>(), tokensToWords(arrayOf(" ", "▁"), floatArrayOf(0f, 1f), 0, 2_000))
    }

    @Test fun `конец не раньше начала`() {
        val word = tokensToWords(arrayOf(" а"), floatArrayOf(3f), 0, 2_000).single()
        assertEquals(word.startMs, word.endMs)
    }
}
