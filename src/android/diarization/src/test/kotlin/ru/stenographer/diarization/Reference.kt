package ru.stenographer.diarization

import ru.stenographer.core.Word
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Эталон из scripts/dump_reference.py. Если папки нет, тест пропускается, а не падает:
 * эталон и модели весят сотни мегабайт и в git не лежат.
 */
class Reference private constructor(val name: String, private val dir: File) {
    val meta = JSONObject(File(dir, "meta.json").readText())

    fun floats(file: String): FloatArray = readFloats(File(dir, "$file.f32"))
    fun json(file: String): String = File(dir, "$file.json").readText(Charsets.UTF_8)
    fun shape(array: String): List<Int> = meta.getJSONObject("arrays").getJSONArray(array).let { a -> List(a.length()) { a.getInt(it) } }
    val chunkCount: Int get() = meta.getJSONArray("chunks").length()
    fun chunk(i: Int): JSONObject = JSONObject(json("cache_%02d".format(i)))
    fun hasWords(): Boolean = File(dir, "words_in.json").isFile

    fun words(): List<Word> = JSONArray(json("words_in")).let { a ->
        List(a.length()) {
            val w = a.getJSONObject(it)
            Word(w.getString("text"), toMs(w.getDouble("start")), toMs(w.getDouble("end")))
        }
    }

    fun utterances(): JSONArray = JSONArray(json("utterances"))

    companion object {
        val referenceDir = File(System.getProperty("reference.dir") ?: "../../../../reference")
        val modelsDir = File(System.getProperty("models.dir") ?: "../../../../voicerec-original/models_onnx")

        fun load(name: String): Reference {
            val dir = File(referenceDir, name)
            assumeTrue("Нет эталона $dir, запустите scripts/dump_reference.py", File(dir, "meta.json").isFile)
            return Reference(name, dir)
        }

        fun all(): List<Reference> {
            assumeTrue("Нет эталона $referenceDir", referenceDir.isDirectory)
            return referenceDir.listFiles { f -> File(f, "meta.json").isFile }.orEmpty().sortedBy { it.name }
                .map { Reference(it.name, it) }
        }

        fun requireModels() {
            assumeTrue("Нет модели в $modelsDir", Nemotron.MODEL_FILES.all { File(modelsDir, it).isFile })
        }

        fun toMs(seconds: Double): Long = Math.round(seconds * 1000)
    }
}

/** Наибольшее и среднее расхождение двух массивов. */
data class Diff(val max: Float, val mean: Double) {
    override fun toString() = "макс %.2e, среднее %.2e".format(max, mean)

    companion object {
        fun of(a: FloatArray, b: FloatArray, from: Int = 0, to: Int = a.size): Diff {
            require(a.size == b.size) { "Разная длина: ${a.size} и ${b.size}" }
            var worst = 0f
            var sum = 0.0
            for (i in from until to) {
                val d = abs(a[i] - b[i])
                worst = max(worst, d)
                sum += d
            }
            return Diff(worst, if (to > from) sum / (to - from) else 0.0)
        }
    }
}

fun rows(flat: FloatArray, width: Int): List<FloatArray> =
    List(flat.size / width) { flat.copyOfRange(it * width, (it + 1) * width) }
