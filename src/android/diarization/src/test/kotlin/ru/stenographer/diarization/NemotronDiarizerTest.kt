package ru.stenographer.diarization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Сверка с Python-прототипом по шагам: embed, кэш спикеров после каждого чанка, итоговые вероятности. */
class NemotronDiarizerTest {
    companion object {
        private val shared by lazy {
            NemotronDiarizer.open(Reference.modelsDir, threads = 4).also { d ->
                Runtime.getRuntime().addShutdownHook(Thread { d.close() })
            }
        }

        /** Одна модель на все тестовые классы: загрузка занимает время. */
        val diarizer: NemotronDiarizer
            get() {
                Reference.requireModels()
                return shared
            }
    }

    @Test fun `эмбеддинги совпадают с Python`() {
        val ref = Reference.load("ru_3spk")
        val mel = ref.floats("mel")
        val embeds = diarizer.embed(mel, mel.size / Nemotron.MEL_BINS)
        val expected = ref.floats("embeds")
        assertEquals(ref.shape("embeds")[0], embeds.size)
        val diff = Diff.of(expected, embeds.flatMap { it.asIterable() }.toFloatArray())
        println("ru_3spk: embed $diff")
        assertTrue(diff.toString(), diff.max < 1e-3f)
    }

    @Test fun `кэш спикеров после каждого чанка совпадает с Python`() {
        for (ref in Reference.all()) {
            val embeds = rows(ref.floats("embeds"), Nemotron.EMBED_DIM)
            val frames = ref.shape("mel")[0]
            var worst = 0f
            var chunks = 0
            diarizer.diarizeEmbeds(embeds, frames) { i, cache ->
                val info = ref.chunk(i)
                assertEquals("${ref.name}, чанк $i: размер кэша", info.getInt("cache"), cache.embeds.size)
                assertEquals("${ref.name}, чанк $i: размер FIFO", info.getInt("fifo"), cache.fifo.size)
                val expected = ref.floats("cache_%02d".format(i))
                val actual = (cache.embeds + cache.fifo).flatMap { it.asIterable() }.toFloatArray()
                assertEquals("${ref.name}, чанк $i: длина", expected.size, actual.size)
                // Кэш — это выбранные кадры. Если выбран другой кадр, расхождение будет порядка самих эмбеддингов.
                worst = maxOf(worst, Diff.of(expected, actual).max)
                chunks++
            }
            println("${ref.name}: чанков $chunks, кэш расходится максимум на %.2e".format(worst))
            assertEquals(ref.chunkCount, chunks)
            assertTrue("${ref.name}: кэш выбрал другие кадры ($worst)", worst < 1e-3f)
        }
    }

    @Test fun `вероятности от звука совпадают с Python`() {
        for (ref in Reference.all()) {
            val started = System.nanoTime()
            val probs = diarizer.diarize(ref.floats("audio"))
            val seconds = (System.nanoTime() - started) / 1e9
            val expected = ref.floats("probs")
            assertEquals(ref.name, expected.size, probs.data.size)
            val diff = Diff.of(expected, probs.data)
            val flips = expected.indices.count { (expected[it] >= 0.5f) != (probs.data[it] >= 0.5f) }
            val audioSeconds = ref.floats("audio").size / SAMPLE_RATE.toDouble()
            println(
                "${ref.name}: %.1f с звука за %.1f с (RTF %.3f), вероятности %s, решений «говорит» изменилось %d из %d, спикеров %d"
                    .format(audioSeconds, seconds, seconds / audioSeconds, diff, flips, expected.size, probs.activeSpeakers()),
            )
            assertEquals(ref.name, ref.meta.getInt("speakers"), probs.activeSpeakers())
            // От эмбеддингов Python вероятности совпадают до 1e-7 (тест ниже). От звука признаки отличаются
            // на ~2e-6 из-за порядка сложения, а динамическое int8-квантование step.onnx раздувает это в отдельных
            // кадрах. Сам Python от шума 1e-6 в признаках меняет 384 решения из 112 432 на kpjud и 11 на ru_3spk,
            // поэтому проверяется среднее и доля изменённых решений, а не максимум.
            assertTrue("${ref.name}: $diff", diff.mean < 1e-2)
            assertTrue("${ref.name}: $flips изменённых решений", flips <= expected.size / 200)
        }
    }

    @Test fun `вероятности от эмбеддингов Python`() {
        // Отделяет расхождения модели (версия ONNX Runtime, int8) от расхождений признаков.
        for (ref in Reference.all()) {
            val probs = diarizer.diarizeEmbeds(rows(ref.floats("embeds"), Nemotron.EMBED_DIM), ref.shape("mel")[0])
            val expected = ref.floats("probs")
            val flips = expected.indices.count { (expected[it] >= 0.5f) != (probs.data[it] >= 0.5f) }
            val diff = Diff.of(expected, probs.data)
            println("${ref.name}: от эмбеддингов Python $diff, изменённых решений $flips")
            assertTrue("${ref.name}: $diff", diff.max < 1e-5f)
            assertEquals(ref.name, 0, flips)
        }
    }

    @Test fun `короткий звук даёт пустой результат`() {
        assertEquals(0, diarizer.diarize(FloatArray(1000)).frames)
    }

    @Test fun `прогресс доходит до конца и считает спикеров`() {
        val ref = Reference.load("ru_3spk")
        val progress = ArrayList<Pair<Float, Int>>()
        diarizer.diarize(ref.floats("audio")) { p, n -> progress += p to n }
        assertEquals(ref.chunkCount, progress.size)
        assertTrue(abs(progress.last().first - 1f) < 1e-6f)
        assertTrue(progress.zipWithNext().all { (a, b) -> a.first < b.first && a.second <= b.second })
        assertEquals(3, progress.last().second)
    }
}
