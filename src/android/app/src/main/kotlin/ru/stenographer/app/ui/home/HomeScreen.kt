package ru.stenographer.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.activity.compose.BackHandler
import ru.stenographer.app.data.LiveProgress
import ru.stenographer.app.data.Recording
import ru.stenographer.app.export.formatTime
import ru.stenographer.app.ui.components.AppIcons
import ru.stenographer.app.ui.components.RecordButtonShape
import ru.stenographer.app.ui.components.RecordCenter
import ru.stenographer.app.ui.components.RosettePlaceholder
import ru.stenographer.app.ui.components.VoiceRosette
import ru.stenographer.app.ui.theme.Extra
import ru.stenographer.app.ui.nbsp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun HomeScreen(
    recordings: List<Recording>,
    live: LiveProgress?,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    onOpen: (Recording) -> Unit,
    onSettings: () -> Unit,
    onExportAll: () -> Unit,
) {
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    BackHandler(searching) { searching = false; query = "" }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Разговоры", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (recordings.isNotEmpty()) {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(AppIcons.Search, contentDescription = "Поиск")
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(AppIcons.More, contentDescription = "Меню") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (recordings.any { it.status == Recording.Status.DONE }) {
                            DropdownMenuItem(text = { Text("Экспортировать все") }, onClick = { menu = false; onExportAll() })
                        }
                        DropdownMenuItem(text = { Text("Настройки") }, onClick = { menu = false; onSettings() })
                    }
                }
            }
            AnimatedVisibility(searching, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                SearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            if (recordings.isEmpty()) {
                EmptyState(onRecord, onImport, Modifier.weight(1f))
            } else {
                val shown = remember(recordings, query) { filter(recordings, query) }
                if (shown.isEmpty()) {
                    Text(
                        nbsp("Ничего не нашлось по запросу «$query»"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(shown, key = { it.id }) { rec ->
                        RecordingCard(rec, live?.takeIf { it.recordingId == rec.id }, onClick = { onOpen(rec) })
                    }
                }
            }
        }

        // Плавающие кнопки без подложки; при открытой клавиатуре прячутся, чтобы не закрывать поиск.
        if (recordings.isNotEmpty() && !imeVisible) {
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                FilledTonalButton(onClick = onImport, modifier = Modifier.height(52.dp), contentPadding = PaddingValues(horizontal = 20.dp)) {
                    Icon(AppIcons.Upload, null, Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Загрузить")
                }
                RecordButtonShape(
                    68.dp, Extra.colors.record,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClickLabel = "Записать разговор", onClick = onRecord),
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Название или слова из разговора") },
        leadingIcon = { Icon(AppIcons.Search, null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth().focusRequester(focus),
    )
}

@Composable
private fun EmptyState(onRecord: () -> Unit, onImport: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RecordButtonShape(
            156.dp, Extra.colors.record, center = RecordCenter.ROSE,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClickLabel = "Записать разговор", onClick = onRecord),
        )
        Spacer(Modifier.height(20.dp))
        Text("Записать разговор", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight(600), fontSize = 22.sp))
        Spacer(Modifier.height(14.dp))
        FilledTonalButton(onClick = onImport, modifier = Modifier.height(44.dp)) {
            Icon(AppIcons.Upload, null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Загрузить файлы", style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp))
        }
    }
}

@Composable
private fun RecordingCard(rec: Recording, live: LiveProgress?, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
    ) {
        Cover(rec, live)
        Spacer(Modifier.height(4.dp))
        Text(
            nbsp(rec.title),
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.5.sp, lineHeight = 19.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            meta(rec, live),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = if (rec.status == Recording.Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Обложка: узоры голосов внахлёст, у каждого три слоя, как на банкноте.
 * Пока запись в очереди, вместо узоров пунктирное место; во время обработки узоры дорисовываются.
 */
@Composable
private fun Cover(rec: Recording, live: LiveProgress?) {
    val speakers = rec.speakers.take(3)
    BoxWithConstraints(Modifier.fillMaxWidth().height(84.dp)) {
        if (speakers.isEmpty()) {
            RosettePlaceholder(62.dp, MaterialTheme.colorScheme.outlineVariant, Modifier.align(Alignment.Center))
        }
        speakers.forEachIndexed { i, s ->
            val center = maxWidth * (0.5f + (i - (speakers.size - 1) / 2f) * 0.24f)
            VoiceRosette(
                s, 62.dp,
                Modifier.offset(x = center - 31.dp, y = 10.dp),
                layers = 3, filled = false, strokeWidth = 1.3f,
                progress = if (rec.status == Recording.Status.PROCESSING && live != null) live.overall.coerceIn(0.15f, 1f) else 1f,
            )
        }
    }
}

private fun meta(rec: Recording, live: LiveProgress?): String = when (rec.status) {
    Recording.Status.QUEUED -> "В очереди"
    Recording.Status.PROCESSING -> "Расшифровываю, ${((live?.overall ?: 0f) * 100).toInt()}%"
    Recording.Status.FAILED -> "Не получилось расшифровать"
    Recording.Status.DONE -> "${dayLabel(rec.createdAt)}, ${formatTime(rec.durationMs)}"
}

private fun dayLabel(t: Long): String {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val sameYear = now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
    return when {
        sameYear && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR) -> "сегодня"
        sameYear && now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR) == 1 -> "вчера"
        else -> SimpleDateFormat("d MMMM", Locale("ru")).format(t)
    }
}

/** Поиск по названию и по словам из расшифровок, без учёта регистра и ё. */
private fun filter(list: List<Recording>, query: String): List<Recording> {
    val q = query.trim().lowercase().replace('ё', 'е')
    if (q.isEmpty()) return list
    return list.filter { r ->
        r.title.lowercase().replace('ё', 'е').contains(q) ||
            r.utterances.any { it.text.lowercase().replace('ё', 'е').contains(q) }
    }
}
