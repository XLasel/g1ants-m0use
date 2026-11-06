package ru.stenographer.speech

import android.net.Uri

/** Частота звука, которую ждут модели. */
const val SAMPLE_RATE = 16_000

/** Слово с таймкодами от начала записи, в миллисекундах. */
data class Word(val text: String, val startMs: Long, val endMs: Long)

/** Превращает файл в звук 16 кГц, моно, со значениями от -1 до 1. */
interface AudioDecoder {
    /** [onProgress] получает долю от 0 до 1. */
    suspend fun decode(uri: Uri, onProgress: (Float) -> Unit = {}): FloatArray
}

/** Распознаёт куски речи. Таймкоды слов считаются от начала записи, а не куска. */
interface Recognizer {
    /** Каждый кусок задан как `началоМс..конецМс` от начала записи. [onProgress] получает долю от 0 до 1. */
    suspend fun transcribe(
        audio: FloatArray,
        chunks: List<LongRange>,
        onProgress: (Float) -> Unit = {},
    ): List<Word>
}
