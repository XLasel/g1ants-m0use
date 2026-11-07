package ru.stenographer.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import ru.stenographer.app.data.Speaker
import ru.stenographer.app.ui.theme.Extra
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Узор голоса: роза r = cos(a/b · θ) внутри «печеньки» Material 3.
 * Форма выбирается по номеру голоса, чтобы соседние голоса не путались.
 * Все координаты считаем в квадрате 40×40, как в прототипе, и масштабируем.
 */

/** Параметры розы a/b и число зубцов печеньки для голоса. */
private data class RoseShape(val a: Int, val b: Int, val cookie: Int)

// Силуэты по возрастанию числа лепестков: 3, 5, 5, 7, 8, 8. Соседние заметно разные, чтобы голоса не путались.
private val SHAPES = listOf(
    RoseShape(3, 1, 6), RoseShape(5, 2, 9), RoseShape(5, 1, 10),
    RoseShape(7, 3, 4), RoseShape(4, 1, 8), RoseShape(8, 3, 7),
)

/** По высоте голоса, если она известна, иначе по порядку появления. */
private fun shapeFor(speaker: Speaker): RoseShape = SHAPES[(speaker.shape ?: speaker.index).mod(SHAPES.size)]

/** Контур розы в квадрате 40×40; progress < 1 рисует только начало линии (анимация появления). */
private fun rosePath(r: Float, a: Int, b: Int, progress: Float): Path {
    val path = Path()
    val steps = (360 * progress).toInt().coerceAtLeast(2)
    for (i in 0..steps) {
        val th = i / 360.0 * 2 * PI * b
        val rr = r * cos(a.toDouble() / b * th)
        val x = (20 + rr * cos(th)).toFloat()
        val y = (20 + rr * sin(th)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    return path
}

private fun cookiePath(r: Float, n: Int, amp: Float): Path {
    val path = Path()
    for (i in 0..120) {
        val a = i / 120.0 * 2 * PI
        val rr = r + amp * cos(n * a)
        val x = (20 + rr * cos(a)).toFloat()
        val y = (20 + rr * sin(a)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/**
 * Узор голоса.
 * @param layers сколько слоёв розы, как на банкноте: чем больше говорил голос, тем больше слоёв.
 * @param progress 0..1, для анимации, когда голос только нашёлся.
 * @param filled рисовать ли подложку-печеньку.
 */
@Composable
fun VoiceRosette(
    speaker: Speaker,
    size: Dp,
    modifier: Modifier = Modifier,
    layers: Int = 1,
    progress: Float = 1f,
    filled: Boolean = true,
    strokeWidth: Float = 1.6f,
    color: Color = Extra.colors.voice(speaker.index),
) {
    val glow = Extra.colors.glow
    val shape = shapeFor(speaker)
    val cookie = remember(shape) { cookiePath(17.5f, shape.cookie, if (shape.cookie > 8) 1.2f else 2f) }
    val roses = remember(shape, layers, progress) {
        List(layers) { l ->
            val lp = if (layers > 1) (progress * layers - l).coerceIn(0f, 1f) else progress
            rosePath(13.5f * (1 - l * 0.14f), shape.a, shape.b, lp) to (180f / (shape.a * layers) * l)
        }
    }
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 40f
        scaleTo40(k) {
            if (filled) drawPath(cookie, color.copy(alpha = 0.1f))
            roses.forEachIndexed { l, (path, rot) ->
                if (l > 0 && progress * layers - l <= 0f) return@forEachIndexed
                rotate(rot, pivot = Offset(20f, 20f)) {
                    val w = if (l == 0) strokeWidth else strokeWidth * 0.6f
                    if (glow) {
                        drawPath(path, color.copy(alpha = 0.18f), style = Stroke(w * 4, cap = StrokeCap.Round))
                        drawPath(path, color.copy(alpha = 0.35f), style = Stroke(w * 2, cap = StrokeCap.Round))
                    }
                    drawPath(path, color.copy(alpha = if (l == 0) 1f else 0.75f), style = Stroke(w, cap = StrokeCap.Round))
                }
            }
        }
    }
}

/** Пустое место под голос, который ещё ищем: пунктирная печенька. */
@Composable
fun RosettePlaceholder(size: Dp, color: Color, modifier: Modifier = Modifier) {
    val cookie = remember { cookiePath(17.5f, 8, 0f) }
    Canvas(modifier.size(size)) {
        scaleTo40(this.size.minDimension / 40f) {
            drawPath(
                cookie, color,
                style = Stroke(0.6f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(1.5f, 2f))),
            )
        }
    }
}

/** Что нарисовано в середине кнопки записи. */
enum class RecordCenter { DOT, STOP, ROSE }

/**
 * Волнистая красная кнопка записи, как в прототипе.
 * DOT — обычная кнопка, STOP — квадрат «готово», ROSE — большая кнопка на пустой главной
 * с медленно вращающейся розой. level 0..1 даёт мягкую пульсацию в такт голосу.
 */
@Composable
fun RecordButtonShape(size: Dp, color: Color, modifier: Modifier = Modifier, center: RecordCenter = RecordCenter.DOT, level: Float = 0f) {
    val cookie = remember { cookiePath(18f, 11, 0.9f) }
    val rose = remember { rosePath(10.5f, 5, 3, 1f) }
    // Пульсация сглажена: значения громкости приходят рывками раз в 50 мс.
    val scale by animateFloatAsState(1f + level.coerceIn(0f, 1f) * 0.08f, tween(120), label = "pulse")
    val spin = if (center == RecordCenter.ROSE) {
        val t = rememberInfiniteTransition(label = "spin")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(50_000, easing = LinearEasing)), label = "spin").value
    } else 0f
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 40f
        withTransform({ scale(scale, scale) }) {
            scaleTo40(k) { drawPath(cookie, color) }
        }
        val d = this.size.minDimension
        when (center) {
            RecordCenter.DOT -> drawCircle(Color.White, radius = d * 0.12f)
            RecordCenter.STOP -> {
                val s = d * 0.3f
                drawRoundRect(
                    Color.White,
                    topLeft = Offset((this.size.width - s) / 2, (this.size.height - s) / 2),
                    size = androidx.compose.ui.geometry.Size(s, s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.22f),
                )
            }
            RecordCenter.ROSE -> rotate(spin) {
                scaleTo40(k) { drawPath(rose, Color.White.copy(alpha = 0.85f), style = Stroke(0.55f)) }
            }
        }
    }
}

/** Рисует в координатах 40×40 (как в прототипе), растянутых на размер холста. */
private inline fun DrawScope.scaleTo40(k: Float, block: DrawScope.() -> Unit) {
    withTransform({ scale(k, k, Offset.Zero) }, block)
}
