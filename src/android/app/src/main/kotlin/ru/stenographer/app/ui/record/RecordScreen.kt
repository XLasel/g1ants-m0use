package ru.stenographer.app.ui.record

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import ru.stenographer.app.export.formatTime
import ru.stenographer.app.service.RecorderState
import ru.stenographer.app.ui.components.AppIcons
import ru.stenographer.app.ui.components.RecordButtonShape
import ru.stenographer.app.ui.components.RecordCenter
import ru.stenographer.app.ui.nbsp
import ru.stenographer.app.ui.theme.Extra
import ru.stenographer.app.ui.theme.dialogColor
import java.util.Locale

/**
 * Запись с микрофона: крупный таймер, лента громкости и две кнопки.
 * «Готово» отправляет запись на расшифровку, крестик спрашивает, что делать с записанным.
 */
@Composable
fun RecordScreen(
    state: RecorderState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDone: () -> Unit,
    onDiscard: () -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    BackHandler { asking = true }
    val record = Extra.colors.record

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { asking = true }) { Icon(AppIcons.Close, contentDescription = "Закрыть") }
            Text("Новая запись", style = MaterialTheme.typography.titleLarge)
        }

        // Пока на паузе, таймер мягко мигает: видно, что запись не идёт.
        val blink by rememberInfiniteTransition(label = "pause").animateFloat(
            initialValue = 1f, targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "blink",
        )
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                formatTime(state.elapsedMs),
                style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.alpha(if (state.paused) blink else 1f),
            )
            Text(
                "${String.format(Locale("ru"), "%.1f", state.bytes / 1_000_000f)} МБ, WAV 16 кГц, свободно на ${freeHours()} ч",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(36.dp))
            LevelRibbon(state.levels, state.elapsedMs, record)
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    state.paused -> "На паузе"
                    state.levels.size > 50 && state.levels.takeLast(50).all { it < 0.06f } -> "Очень тихо, поднесите телефон ближе"
                    else -> nbsp("Можно свернуть приложение, запись продолжится")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Три равные колонки: кнопка «Готово» всегда в центре и не прыгает при смене подписи слева.
        Row(Modifier.fillMaxWidth().padding(bottom = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                FilledTonalIconButton(onClick = if (state.paused) onResume else onPause, modifier = Modifier.size(56.dp)) {
                    Icon(if (state.paused) AppIcons.Play else AppIcons.Pause, contentDescription = null)
                }
                Spacer(Modifier.height(6.dp))
                Text(if (state.paused) "Продолжить" else "Пауза", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val level = state.levels.lastOrNull() ?: 0f
                RecordButtonShape(
                    84.dp, record, center = RecordCenter.STOP, level = if (state.paused) 0f else level,
                    modifier = Modifier.clip(CircleShape).clickable(onClickLabel = "Готово", onClick = onDone),
                )
                Spacer(Modifier.height(6.dp))
                Text("Готово", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.weight(1f))
        }
    }

    // Запись остановилась сама: предлагаем сохранить то, что успели записать.
    state.error?.let { reason ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = dialogColor,
            title = { Text("Запись остановилась") },
            text = {
                Text(
                    nbsp(
                        if (state.elapsedMs > 0) "$reason Записано ${formatTime(state.elapsedMs)}, это можно сохранить и расшифровать."
                        else "$reason Попробуйте ещё раз, когда микрофон освободится.",
                    ),
                )
            },
            confirmButton = {
                if (state.elapsedMs > 0) TextButton(onClick = onDone) { Text("Сохранить") }
                else TextButton(onClick = onDiscard) { Text("Понятно") }
            },
            dismissButton = { if (state.elapsedMs > 0) TextButton(onClick = onDiscard) { Text("Удалить") } },
        )
        return
    }

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            containerColor = dialogColor,
            title = { Text("Остановить запись?") },
            text = { Text(nbsp("Записано ${formatTime(state.elapsedMs)}. Можно сохранить и расшифровать или удалить.")) },
            confirmButton = { TextButton(onClick = { asking = false; onDone() }) { Text("Сохранить") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { asking = false; onDiscard() }) { Text("Удалить") }
                    TextButton(onClick = { asking = false }) { Text("Продолжить") }
                }
            },
        )
    }
}

/**
 * Лента громкости за последние ~8 секунд, как в прототипе: сплошная волна,
 * толщина которой равна громкости, свежий звук справа, засечки раз в секунду.
 */
@Composable
private fun LevelRibbon(levels: List<Float>, elapsedMs: Long, color: androidx.compose.ui.graphics.Color) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 20.dp)) {
        val mid = size.height / 2
        val step = size.width / 170f // 170 значений по 50 мс ≈ 8,5 с на ширину
        drawLine(line, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        val shown = levels.takeLast(170)
        if (shown.isNotEmpty()) {
            val path = androidx.compose.ui.graphics.Path()
            val maxA = mid * 0.62f
            shown.forEachIndexed { i, v ->
                val x = size.width - (shown.size - 1 - i) * step
                val y = mid - 1.dp.toPx() - v * maxA
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (i in shown.indices.reversed()) {
                val x = size.width - (shown.size - 1 - i) * step
                path.lineTo(x, mid + 1.dp.toPx() + shown[i] * maxA)
            }
            path.close()
            drawPath(path, color)
            drawCircle(color, 4.dp.toPx(), Offset(size.width, mid))
        }
        // Засечки секунд внизу: длинные на каждой пятой.
        val sec = elapsedMs / 1000f
        var s2 = kotlin.math.ceil(sec - 9f).toInt()
        while (s2 <= sec) {
            if (s2 >= 0) {
                val x = size.width - (sec - s2) * 20 * step
                if (x >= 0) {
                    val h = if (s2 % 5 == 0) 10.dp.toPx() else 5.dp.toPx()
                    drawLine(tick, Offset(x, size.height - h), Offset(x, size.height), 1.dp.toPx())
                }
            }
            s2++
        }
    }
}

/** На сколько часов записи хватит места: WAV 16 кГц моно — около 115 МБ в час. */
@Composable
private fun freeHours(): Long {
    val dir = androidx.compose.ui.platform.LocalContext.current.filesDir
    return remember { android.os.StatFs(dir.path).availableBytes / (16_000L * 2 * 3600) }
}
