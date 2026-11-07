package ru.stenographer.speech

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Сводит чередующиеся каналы в моно средним значением. */
internal fun downmix(interleaved: FloatArray, count: Int, channels: Int): FloatArray {
    val out = FloatArray(count / channels.coerceAtLeast(1))
    downmixInto(interleaved, count, channels, out)
    return out
}

/** То же, что [downmix], но пишет в готовый массив и возвращает число отсчётов. Можно передать один и тот же массив. */
internal fun downmixInto(interleaved: FloatArray, count: Int, channels: Int, out: FloatArray): Int {
    require(channels > 0) { "Число каналов должно быть больше нуля" }
    val frames = count / channels
    if (channels == 1) {
        if (out !== interleaved) interleaved.copyInto(out, 0, 0, frames)
        return frames
    }
    for (frame in 0 until frames) {
        var sum = 0f
        val base = frame * channels
        for (channel in 0 until channels) sum += interleaved[base + channel]
        out[frame] = sum / channels
    }
    return frames
}

/** Растущий буфер, чтобы не копировать звук при каждом добавлении блока. */
internal class FloatBuffer(capacity: Int = 1 shl 16) {
    private var data = FloatArray(max(capacity, 16))
    var size = 0
        private set

    fun add(values: FloatArray, count: Int = values.size) {
        if (size + count > data.size) {
            val needed = size.toLong() + count
            val grown = max(needed, data.size * 3L / 2)
            check(grown <= Int.MAX_VALUE - 8) { "Запись слишком длинная для одного массива" }
            data = data.copyOf(grown.toInt())
        }
        values.copyInto(data, size, 0, count)
        size += count
    }

    fun toArray(): FloatArray = if (size == data.size) data else data.copyOf(size)
}

/**
 * Потоковая смена частоты: windowed-sinc с окном Блэкмана и готовой таблицей фаз.
 * Линейная интерполяция при понижении частоты дала бы наложение спектра и могла бы ухудшить распознавание.
 * Задержки на выходе нет: выходной отсчёт `n` соответствует моменту `n / outRate` исходной записи.
 */
internal class Resampler(private val inRate: Int, private val outRate: Int) {
    private val passthrough = inRate == outRate
    private val phases: Int
    private val step: Int
    private val halfTaps: Int
    private val table: Array<FloatArray>

    private var pending = FloatArray(0)
    private var pendingSize = 0
    private var pendingStart = 0L // номер входного отсчёта, с которого начинается pending
    private var consumed = 0L // сколько входных отсчётов получено
    private var produced = 0L // сколько выходных отсчётов выдано

    init {
        require(inRate > 0 && outRate > 0) { "Частота должна быть больше нуля: $inRate -> $outRate" }
        val divisor = gcd(inRate, outRate)
        phases = outRate / divisor
        step = inRate / divisor
        val cutoff = min(1.0, outRate.toDouble() / inRate) * CUTOFF_MARGIN
        halfTaps = if (passthrough) 0 else kotlin.math.ceil(ZERO_CROSSINGS / cutoff).toInt()
        table = if (passthrough) emptyArray() else Array(phases) { phase -> filter(phase.toDouble() / phases, cutoff) }
    }

    /** Возвращает все отсчёты, которые уже можно посчитать. */
    fun process(input: FloatArray, count: Int = input.size): FloatArray {
        if (passthrough) return input.copyOf(count)
        append(input, count)
        consumed += count
        return drain(consumed - halfTaps)
    }

    /** Дополняет конец тишиной и выдаёт остаток. После вызова объект больше не используется. */
    fun finish(): FloatArray {
        if (passthrough) return FloatArray(0)
        append(FloatArray(halfTaps), halfTaps)
        return drain(consumed)
    }

    /** Считает выходные отсчёты, у которых целая часть входной позиции меньше [centerLimit]. */
    private fun drain(centerLimit: Long): FloatArray {
        val available = ceilDiv(centerLimit * phases, step.toLong())
        val count = max(0L, available - produced).toInt()
        val out = FloatArray(count)
        for (i in 0 until count) {
            val position = produced * step
            val center = position / phases
            val phase = (position % phases).toInt()
            out[i] = convolve(center, table[phase])
            produced++
        }
        val keepFrom = (produced * step / phases) - halfTaps
        trim(keepFrom)
        return out
    }

    private fun convolve(center: Long, taps: FloatArray): Float {
        var sum = 0f
        val first = (center - halfTaps + 1 - pendingStart).toInt()
        // Индексы до начала записи считаем тишиной, а после конца их не бывает: drain ждёт нужные отсчёты.
        for (i in maxOf(0, -first) until taps.size) sum += pending[first + i] * taps[i]
        return sum
    }

    private fun filter(fraction: Double, cutoff: Double): FloatArray {
        val size = 2 * halfTaps
        val taps = DoubleArray(size) { i ->
            val x = (i - halfTaps + 1) - fraction
            val window = blackman((x + halfTaps) / size)
            sinc(cutoff * x) * cutoff * window
        }
        val gain = taps.sum()
        return FloatArray(size) { (taps[it] / gain).toFloat() }
    }

    private fun append(input: FloatArray, count: Int) {
        if (pendingSize + count > pending.size) pending = pending.copyOf(max(pendingSize + count, pending.size * 2))
        input.copyInto(pending, pendingSize, 0, count)
        pendingSize += count
    }

    private fun trim(keepFrom: Long) {
        val drop = min(max(0L, keepFrom - pendingStart), pendingSize.toLong()).toInt()
        if (drop == 0) return
        pending.copyInto(pending, 0, drop, pendingSize)
        pendingSize -= drop
        pendingStart += drop
    }

    private companion object {
        const val ZERO_CROSSINGS = 16
        const val CUTOFF_MARGIN = 0.95

        fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        fun blackman(t: Double): Double {
            if (t < 0.0 || t > 1.0) return 0.0
            return 0.42 - 0.5 * cos(2 * PI * t) + 0.08 * cos(4 * PI * t)
        }

        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        fun ceilDiv(a: Long, b: Long): Long = if (a <= 0) 0 else (a + b - 1) / b
    }
}
