package ru.stenographer.diarization

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.stenographer.core.SAMPLE_RATE
import ru.stenographer.core.Segment
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class VoiceFeaturesTest {
    /** Звонкий «голос»: основной тон с гармониками, как у гласной. */
    private fun voice(hz: Double, seconds: Double, amplitude: Float): FloatArray =
        FloatArray((seconds * SAMPLE_RATE).toInt()) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            (amplitude * (sin(2 * PI * hz * t) + 0.5 * sin(4 * PI * hz * t) + 0.25 * sin(6 * PI * hz * t)) / 1.75).toFloat()
        }

    @Test fun `тон и громкость двух синтетических голосов`() {
        val audio = voice(120.0, 3.0, 0.3f) + voice(220.0, 3.0, 0.8f)
        val features = VoiceFeatureExtractor.extract(audio, listOf(
            Segment("speaker_1", 0, 3_000),
            Segment("speaker_2", 3_000, 6_000),
        ))
        assertEquals(listOf("speaker_1", "speaker_2"), features.map { it.speakerId })
        val (low, high) = features
        assertEquals(120f, low.pitchMedianHz, 3f)
        assertEquals(220f, high.pitchMedianHz, 5f)
        assertTrue("ровный тон без интонации", low.pitchSpread < 0.05f && high.pitchSpread < 0.05f)
        assertTrue("громкость ${low.loudnessMean} < ${high.loudnessMean}", low.loudnessMean < high.loudnessMean)
        assertEquals(1f, high.loudnessMean, 0.05f)
    }

    @Test fun `тишина и шум не дают тона`() {
        val audio = FloatArray(SAMPLE_RATE * 2) { if (it % 7 == 0) 0.5f else -0.1f * (it % 3) }
        assertEquals(null, VoiceFeatureExtractor.yin(FloatArray(SAMPLE_RATE), 0))
        assertTrue(VoiceFeatureExtractor.extract(FloatArray(SAMPLE_RATE), listOf(Segment("speaker_1", 0, 1_000))).isEmpty())
        VoiceFeatureExtractor.extract(audio, listOf(Segment("speaker_1", 0, 2_000)))
    }

    @Test fun `на русских диалогах женские голоса выше мужских`() {
        val testset = File(Reference.referenceDir.parentFile, "voicerec-poc/testset/russian")
        assumeTrue("Нет testset $testset", testset.isDirectory)
        val male = ArrayList<Float>()
        val female = ArrayList<Float>()
        for (truth in testset.listFiles { f -> f.name.endsWith(".truth.json") }.orEmpty()) {
            val audio = readWav(File(testset, truth.name.removeSuffix(".truth.json") + ".wav"))
            val turns = JSONArray(truth.readText(Charsets.UTF_8))
            val gender = HashMap<String, String>()
            val segments = (0 until turns.length()).map { i ->
                val t = turns.getJSONObject(i)
                gender[t.getString("speaker")] = t.getString("gender")
                Segment(t.getString("speaker"), (t.getDouble("start") * 1000).toLong(), (t.getDouble("end") * 1000).toLong())
            }
            val started = System.nanoTime()
            val features = VoiceFeatureExtractor.extract(audio, segments)
            val ms = (System.nanoTime() - started) / 1_000_000
            for (f in features) (if (gender[f.speakerId] == "F") female else male) += f.pitchMedianHz
            println("${truth.name.removeSuffix(".truth.json")}: ${ms} мс, " + features.joinToString { f ->
                "${gender[f.speakerId]} %.0f Гц, разброс %.2f, громкость %.2f".format(f.pitchMedianHz, f.pitchSpread, f.loudnessMean)
            })
        }
        assertTrue(male.isNotEmpty() && female.isNotEmpty())
        println("мужские ${male.map { it.toInt() }}, женские ${female.map { it.toInt() }}")
        assertTrue("мужские $male, женские $female", male.max() < female.min())
    }

    private fun readWav(file: File): FloatArray {
        val bytes = file.readBytes()
        val data = bytes.copyOfRange(44, bytes.size) // PCM 16 бит моно 16 кГц, заголовок 44 байта
        val shorts = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
    }
}
