package ru.stenographer.speech

import kotlin.math.min

/*
 * Склейка кусков, которые читались параллельно.
 *
 * Каждый кусок читает свой кодек: он начинает чуть раньше своего стыка и заканчивает чуть позже.
 * Кусок сразу переводится в 16 кГц через [PartResampler], поэтому звук на исходной частоте целиком
 * в памяти не лежит. Потом [stitchParts] берёт у каждого куска только отрезок между его стыками.
 *
 * Чтобы результат совпал с одним проходом [Resampler] по всей записи до последнего бита, нужны две вещи:
 * 1. Все куски считают отсчёты от одного начала и начинают пересчёт с отсчёта на общей сетке
 *    (кратного `step`), тогда фазы фильтра у всех кусков одинаковые.
 * 2. У стыка фильтр должен видеть настоящие отсчёты, а не тишину на краю куска. Для этого кусок
 *    должен заходить за стык на [stitchMargin], и это проверяется по `validFrom` и `validTo`.
 *
 * Соседние куски перекрываются, поэтому склейку можно проверить: в перекрытии они должны совпасть.
 * На телефоне правильная склейка даёт относительную ошибку до 0,01, а сдвиг даже на один отсчёт — от 0,16.
 */

/** Стыки не сошлись с тем, что прочитали куски. Тогда запись читается заново одним проходом. */
internal class StitchException(message: String) : Exception(message)

/** Запас у стыка: 20 мс, это больше половины фильтра [Resampler] на любой частоте (там около 1 мс). */
internal fun stitchMargin(inRate: Int, outRate: Int = SAMPLE_RATE): Long {
    val step = stepOf(inRate, outRate)
    return ceilDiv(inRate / 50L, step) * step
}

/** Номер выходного отсчёта для стыка на исходной частоте: стык сдвигается вниз на общую сетку. */
internal fun cutToOutput(inputSample: Long, inRate: Int, outRate: Int = SAMPLE_RATE): Long =
    Math.floorDiv(inputSample, stepOf(inRate, outRate)) * phasesOf(inRate, outRate)

/** Кусок в 16 кГц. [firstOut] — номер его первого отсчёта во всей записи, честные отсчёты лежат в `validFrom until validTo`. */
internal class ResampledPart(val firstOut: Long, val samples: FloatArray, val validFrom: Long, val validTo: Long) {
    val endOut: Long get() = firstOut + samples.size
}

/**
 * Переводит кусок в [outRate] по мере чтения.
 * [firstSample] — номер первого отсчёта куска во всей записи, на исходной частоте.
 */
internal class PartResampler(val inRate: Int, firstSample: Long, private val outRate: Int = SAMPLE_RATE) {
    private val step = stepOf(inRate, outRate)
    private val phases = phasesOf(inRate, outRate)
    private val margin = stitchMargin(inRate, outRate)
    private val start = ceilDiv(firstSample, step) * step // первый отсчёт на общей сетке
    private var toSkip = start - firstSample
    private var fed = 0L
    private val resampler = Resampler(inRate, outRate)
    private val out = FloatBuffer()

    fun push(samples: FloatArray, count: Int) {
        var from = 0
        if (toSkip > 0) {
            from = min(toSkip, count.toLong()).toInt()
            toSkip -= from
        }
        val left = count - from
        if (left <= 0) return
        val input = if (from == 0) samples else samples.copyOfRange(from, count)
        out.add(resampler.process(input, left))
        fed += left
    }

    fun finish(): ResampledPart {
        out.add(resampler.finish())
        val end = start + fed
        return ResampledPart(
            firstOut = start / step * phases,
            samples = out.toArray(),
            // Фильтр уже не задевает край куска, если центр отсчёта дальше края на margin.
            validFrom = (start + margin) / step * phases,
            validTo = ceilDiv((end - margin) * phases, step),
        )
    }
}

/**
 * Склеивает куски по стыкам [cuts] (номера выходных отсчётов из [cutToOutput], по одному между соседями).
 * Первый кусок берётся с самого начала, последний до самого конца, как при чтении одним проходом.
 */
internal fun stitchParts(parts: List<ResampledPart>, cuts: List<Long>): FloatArray {
    require(parts.isNotEmpty() && cuts.size == parts.size - 1) { "Стыков должно быть на один меньше, чем кусков" }
    val ranges = parts.mapIndexed { k, part ->
        val from = if (k == 0) part.firstOut else cuts[k - 1]
        val to = if (k == parts.lastIndex) part.endOut else cuts[k]
        if (k > 0 && from < part.validFrom) throw StitchException("Кусок $k начался слишком поздно: ${part.validFrom} > $from")
        if (k < parts.lastIndex && to > part.validTo) throw StitchException("Кусок $k закончился слишком рано: ${part.validTo} < $to")
        if (from < part.firstOut || to > part.endOut || to < from) throw StitchException("Куску $k не хватило отсчётов")
        from to to
    }
    for (k in 1 until parts.size) checkSeam(parts[k - 1], parts[k], cuts[k - 1])
    val total = ranges.sumOf { (from, to) -> to - from }
    check(total <= Int.MAX_VALUE - 8) { "Запись слишком длинная для одного массива" }
    val result = FloatArray(total.toInt())
    var offset = 0
    parts.forEachIndexed { k, part ->
        val (from, to) = ranges[k]
        part.samples.copyInto(result, offset, (from - part.firstOut).toInt(), (to - part.firstOut).toInt())
        offset += (to - from).toInt()
    }
    return result
}

/** Сравнивает соседние куски в перекрытии около стыка и бросает [StitchException], если они разошлись. */
internal fun checkSeam(previous: ResampledPart, next: ResampledPart, cut: Long) {
    val from = maxOf(next.validFrom, previous.firstOut, cut - SEAM_CHECK_HALF)
    val to = minOf(previous.validTo, next.endOut, cut + SEAM_CHECK_HALF)
    if (to - from < SEAM_CHECK_MIN) throw StitchException("Куски у стыка $cut перекрываются слишком мало: ${to - from}")
    var signal = 0.0
    var error = 0.0
    for (i in from until to) {
        val a = previous.samples[(i - previous.firstOut).toInt()].toDouble()
        val b = next.samples[(i - next.firstOut).toInt()].toDouble()
        signal += a * a
        error += (a - b) * (a - b)
    }
    val n = (to - from).toDouble()
    val rms = kotlin.math.sqrt(signal / n)
    if (rms < SEAM_QUIET) return // в тишине сравнивать нечего
    val relative = kotlin.math.sqrt(error / n) / rms
    if (relative > SEAM_TOLERANCE) throw StitchException("Куски разошлись у стыка $cut: ошибка %.3f".format(relative))
}

private const val SEAM_CHECK_HALF = SAMPLE_RATE / 4L // 250 мс в каждую сторону
private const val SEAM_CHECK_MIN = SAMPLE_RATE / 10L
private const val SEAM_QUIET = 1e-3
private const val SEAM_TOLERANCE = 0.05

private fun stepOf(inRate: Int, outRate: Int): Long = (inRate / gcd(inRate, outRate)).toLong()

private fun phasesOf(inRate: Int, outRate: Int): Long = (outRate / gcd(inRate, outRate)).toLong()

private fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)

private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
