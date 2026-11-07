package ru.stenographer.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.stenographer.core.Stage

class ProgressThrottleTest {
    @Test fun `не чаще раза в 100 мс, начало и конец всегда`() {
        var now = 0L
        val sent = ArrayList<Pair<Stage, Float>>()
        val throttle = ProgressThrottle(clock = { now }) { stage, value -> sent += stage to value }
        throttle.start(Stage.DIARIZE)
        for (i in 1..99) {
            now += 10
            throttle.report(Stage.DIARIZE, i / 100f)
        }
        throttle.finish(Stage.DIARIZE)
        assertEquals(0f, sent.first().second)
        assertEquals(1f, sent.last().second)
        // 990 мс по 10 мс: начало, ~9 промежуточных, конец
        assertEquals(11, sent.size)
    }

    @Test fun `прогресс не откатывается, этапы независимы`() {
        val sent = ArrayList<Pair<Stage, Float>>()
        var now = 0L
        val throttle = ProgressThrottle(clock = { now += 1_000; now }) { stage, value -> sent += stage to value }
        throttle.report(Stage.DECODE, 0.5f)
        throttle.report(Stage.DECODE, 0.3f)
        throttle.report(Stage.RECOGNIZE, 0.1f)
        assertEquals(listOf(Stage.DECODE to 0.5f, Stage.RECOGNIZE to 0.1f), sent)
    }
}
