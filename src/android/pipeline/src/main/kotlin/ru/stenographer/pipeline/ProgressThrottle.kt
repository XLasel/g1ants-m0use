package ru.stenographer.pipeline

import ru.stenographer.core.Stage

/**
 * Прореживает прогресс: по контракту события приходят не чаще 10 раз в секунду, анимации интерфейс считает сам.
 * Начало (0) и конец (1) этапа отдаются всегда, прогресс внутри этапа не убывает.
 */
internal class ProgressThrottle(
    private val minIntervalMs: Long = 100,
    private val clock: () -> Long = System::currentTimeMillis,
    private val emit: (Stage, Float) -> Unit,
) {
    private val lastTime = HashMap<Stage, Long>()
    private val lastValue = HashMap<Stage, Float>()

    @Synchronized
    fun report(stage: Stage, progress: Float) {
        val value = progress.coerceIn(0f, 1f)
        val previous = lastValue[stage]
        if (previous != null && value <= previous) return
        val now = clock()
        val due = previous == null || value >= 1f || now - (lastTime[stage] ?: 0L) >= minIntervalMs
        if (!due) return
        lastTime[stage] = now
        lastValue[stage] = value
        emit(stage, value)
    }

    fun start(stage: Stage) = report(stage, 0f)
    fun finish(stage: Stage) = report(stage, 1f)
}
