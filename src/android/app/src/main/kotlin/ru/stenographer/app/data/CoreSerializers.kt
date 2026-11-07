package ru.stenographer.app.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import ru.stenographer.core.SummaryPoint
import ru.stenographer.core.Utterance
import ru.stenographer.core.Word

/*
 * Типы из :core — чистый Kotlin без библиотеки сериализации, их делит вся команда.
 * Чтобы сохранять записи в recordings.json, не трогая общий модуль, переводим их в свои копии и обратно.
 */

@Serializable
private class WordSurrogate(val text: String, val startMs: Long, val endMs: Long)

@Serializable
private class UtteranceSurrogate(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<WordSurrogate> = emptyList(),
)

@Serializable
private class SummaryPointSurrogate(
    val text: String,
    val sourceStartMs: Long,
    val kind: String = "topic",
    val speakerId: String? = null,
)

object UtteranceSerializer : KSerializer<Utterance> {
    override val descriptor: SerialDescriptor = UtteranceSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Utterance) = encoder.encodeSerializableValue(
        UtteranceSurrogate.serializer(),
        UtteranceSurrogate(value.speakerId, value.startMs, value.endMs, value.text, value.words.map { WordSurrogate(it.text, it.startMs, it.endMs) }),
    )

    override fun deserialize(decoder: Decoder): Utterance {
        val s = decoder.decodeSerializableValue(UtteranceSurrogate.serializer())
        return Utterance(s.speakerId, s.startMs, s.endMs, s.text, s.words.map { Word(it.text, it.startMs, it.endMs) })
    }
}

object SummaryPointSerializer : KSerializer<SummaryPoint> {
    override val descriptor: SerialDescriptor = SummaryPointSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: SummaryPoint) =
        encoder.encodeSerializableValue(
            SummaryPointSurrogate.serializer(),
            SummaryPointSurrogate(value.text, value.sourceStartMs, value.kind, value.speakerId),
        )

    override fun deserialize(decoder: Decoder): SummaryPoint =
        decoder.decodeSerializableValue(SummaryPointSurrogate.serializer()).let {
            SummaryPoint(it.text, it.sourceStartMs, it.kind, it.speakerId)
        }
}
