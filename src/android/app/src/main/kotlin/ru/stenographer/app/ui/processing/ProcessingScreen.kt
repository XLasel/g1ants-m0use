package ru.stenographer.app.ui.processing

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.stenographer.app.data.LiveProgress
import ru.stenographer.app.data.Recording
import ru.stenographer.app.data.Speaker
import ru.stenographer.app.export.formatTime
import ru.stenographer.app.ui.components.AppIcons
import ru.stenographer.app.ui.components.RosettePlaceholder
import ru.stenographer.app.ui.components.VoiceRosette
import ru.stenographer.app.ui.nbsp
import ru.stenographer.core.Stage

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun ProcessingScreen(
    rec: Recording,
    live: LiveProgress?,
    queued: List<Recording>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(AppIcons.Down, contentDescription = "Свернуть") }
            Text(rec.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }

        if (rec.status == Recording.Status.FAILED) {
            Failed(rec.error, onRetry, onDelete)
            return@Column
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(headline(rec, live), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                when { rec.durationMs > 0 -> "Запись ${formatTime(rec.durationMs)}"; live == null -> "В очереди"; else -> " " },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Голоса: пока ни одного нет — пустой узор «ищу», первый найденный голос встаёт на его место,
        // следующие плавно раздвигают соседей. Так ничего не прыгает.
        val searching = live == null || live.stage <= Stage.DIARIZE
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedContent(rec.speakers.isEmpty(), transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(300)) }, label = "voices") { empty ->
                    if (empty) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
                            RosettePlaceholder(92.dp, MaterialTheme.colorScheme.outlineVariant)
                            Text("ищу голоса", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            rec.speakers.forEach { s -> key(s.id) { AppearingVoice(s) } }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                // Строка всегда занимает место, чтобы узоры над ней не сдвигались, когда она пропадает.
                Text(
                    if (searching && rec.speakers.isNotEmpty()) "ищу ещё голоса" else " ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (queued.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text("Дальше в очереди", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                queued.take(4).forEach {
                    Text(it.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // Прогресс приходит рывками по этапам; сглаживаем, чтобы волна ползла, а не прыгала.
        val animatedOverall by animateFloatAsState(live?.overall ?: 0f, tween(600), label = "overall")
        Column(Modifier.padding(horizontal = 20.dp)) {
            STEPS.forEach { (label, stages) -> StepRow(label, stages, live) }
        }
        LinearWavyProgressIndicator(
            progress = { animatedOverall },
            stopSize = 0.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        )
        FilledTonalButton(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).height(52.dp)) {
            Text("Свернуть, продолжу в фоне")
        }
    }
}

private val STEPS = listOf(
    "Чтение записи" to listOf(Stage.DECODE),
    "Поиск голосов" to listOf(Stage.DIARIZE),
    "Распознавание речи" to listOf(Stage.RECOGNIZE),
    // Конспект делается уже после того, как расшифровка открылась, поэтому здесь его нет.
    "Сборка реплик" to listOf(Stage.MERGE),
)

@Composable
private fun StepRow(label: String, stages: List<Stage>, live: LiveProgress?) {
    val current = live?.stage
    val state = when {
        current == null -> ""
        current > stages.last() -> "готово"
        current in stages -> "${(live.stageProgress * 100).toInt()}%"
        else -> ""
    }
    val active = current != null && current >= stages.first()
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(state, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Узор голоса: место под него раскрывается плавно, сам узор дорисовывается за 3 секунды. */
@Composable
private fun AppearingVoice(s: Speaker) {
    val shown = remember(s.id) { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(shown, enter = expandHorizontally(tween(450)) + fadeIn(tween(450))) {
        GrowingVoice(s)
    }
}

@Composable
private fun GrowingVoice(s: Speaker) {
    val progress = remember(s.id) { Animatable(0f) }
    LaunchedEffect(s.id) { progress.animateTo(1f, tween(3400)) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
        VoiceRosette(s, 92.dp, layers = 2, progress = progress.value, filled = false, strokeWidth = 1.2f)
        Text(s.name, style = MaterialTheme.typography.labelMedium, color = ru.stenographer.app.ui.theme.Extra.colors.voice(s.index))
    }
}

@Composable
private fun Failed(error: String?, onRetry: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.padding(20.dp)) {
        Text("Не получилось расшифровать", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(nbsp(error ?: "Файл не читается или формат не поддерживается."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onRetry) { Text("Повторить") }
            TextButton(onClick = onDelete) { Text("Удалить") }
        }
    }
}

private fun headline(rec: Recording, live: LiveProgress?): String = when (live?.stage) {
    null -> "Жду очереди"
    Stage.DECODE -> "Читаю запись"
    Stage.DIARIZE -> if (rec.speakers.isEmpty()) "Ищу голоса" else "Голосов: ${rec.speakers.size}"
    Stage.RECOGNIZE -> "Распознаю речь"
    Stage.MERGE, Stage.SUMMARY -> "Собираю реплики"
}
