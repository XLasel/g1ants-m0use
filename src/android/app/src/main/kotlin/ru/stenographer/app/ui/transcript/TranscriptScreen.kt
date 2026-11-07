package ru.stenographer.app.ui.transcript

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.stenographer.app.data.Recording
import ru.stenographer.app.data.Speaker
import ru.stenographer.app.data.SummaryItem
import ru.stenographer.app.data.summarySections
import ru.stenographer.app.export.formatTime
import ru.stenographer.app.ui.components.AppIcons
import ru.stenographer.app.ui.components.VoiceRosette
import ru.stenographer.app.ui.nbsp
import ru.stenographer.app.ui.theme.Extra
import ru.stenographer.app.ui.theme.dialogColor
import ru.stenographer.core.Utterance

private enum class Tab(val label: String) { TEXT("Текст"), SUMMARY("Конспект"), VOICES("Голоса") }

/**
 * Расшифровка: дорожки голосов сверху, под ними вкладки и плеер внизу.
 * Тап по реплике перематывает к ней, тап по узору голоса включает соло.
 */
@Composable
fun TranscriptScreen(
    rec: Recording,
    player: PlayerState,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long, Boolean) -> Unit,
    onSkip: (Long) -> Unit,
    onSolo: (String) -> Unit,
    onSpeed: () -> Unit,
    onExport: () -> Unit,
    onCopy: () -> Unit,
    onRename: (String) -> Unit,
    onRenameSpeaker: (String, String) -> Unit,
    onMergeSpeaker: (from: String, into: String) -> Unit,
    onCopySummary: () -> Unit,
    onShareSummary: () -> Unit,
    onDelete: () -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.TEXT) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    BackHandler(searching) { searching = false; query = "" }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "Назад") }
            Column(Modifier.weight(1f).clickable(onClickLabel = "Переименовать") { renaming = true }.padding(start = 4.dp)) {
                Text(rec.title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 24.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatTime(rec.durationMs)}, ${voicesLabel(rec.speakers.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { searching = !searching; if (!searching) query = "" else tab = Tab.TEXT }) {
                Icon(AppIcons.Search, contentDescription = "Поиск")
            }
            IconButton(onClick = onExport) { Icon(AppIcons.Download, contentDescription = "Экспорт") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(AppIcons.More, contentDescription = "Меню") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Скопировать текст") }, onClick = { menu = false; onCopy() })
                    DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }

        AnimatedVisibility(searching) { SearchField(query, { query = it }) }

        Lanes(rec, player, onSeek, onSolo, query)

        Tabs(tab) { tab = it }

        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.TEXT -> TextTab(rec, player, query) { u -> onSeek(u.startMs, true) }
                Tab.SUMMARY -> SummaryTab(rec, onSeek = { ms -> tab = Tab.TEXT; onSeek(ms, true) }, onCopy = onCopySummary, onShare = onShareSummary)
                Tab.VOICES -> VoicesTab(
                    rec, player,
                    onSolo = { id -> onSolo(id); tab = Tab.TEXT; if (!player.playing) onTogglePlay() },
                    onRename = onRenameSpeaker,
                    onMerge = onMergeSpeaker,
                )
            }
        }

        // При открытой клавиатуре плеер прячем, чтобы поиску хватило места.
        if (!imeVisible) PlayerBar(rec, player, onTogglePlay, onSkip, onSpeed)
    }

    if (renaming) RenameDialog("Название", rec.title, onDismiss = { renaming = false }) { onRename(it); renaming = false }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = dialogColor,
            title = { Text("Удалить запись?") },
            text = { Text(nbsp("Запись и расшифровка удалятся с телефона. Это нельзя отменить.")) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Найти в разговоре") },
        leadingIcon = { Icon(AppIcons.Search, null) },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus),
    )
}

/** Дорожки голосов: у каждого своя строка, громкость его реплик и общая линия времени. */
@Composable
private fun Lanes(rec: Recording, player: PlayerState, onSeek: (Long, Boolean) -> Unit, onSolo: (String) -> Unit, query: String) {
    val dur = rec.durationMs.coerceAtLeast(1)
    val ink = MaterialTheme.colorScheme.onSurface
    val line = MaterialTheme.colorScheme.outlineVariant
    val hits = remember(rec, query) { searchHits(rec, query) }
    Column(Modifier.padding(start = 12.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
        rec.speakers.forEach { s ->
            val off = player.solo != null && player.solo != s.id
            val color = Extra.colors.voice(s.index)
            val speaking = rec.utterances.any { it.speakerId == s.id && player.positionMs in it.startMs..it.endMs }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "Только этот голос") { onSolo(s.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    VoiceRosette(s, if (speaking && player.playing) 32.dp else 28.dp, color = if (off) color.copy(alpha = 0.3f) else color)
                }
                Spacer(Modifier.width(6.dp))
                Canvas(
                    Modifier.weight(1f).height(34.dp).pointerInput(dur) {
                        detectTapGestures { o -> onSeek((o.x / size.width * dur).toLong(), false) }
                    },
                ) {
                    val mid = size.height / 2
                    drawLine(line, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
                    // Каждая реплика — волна: толщина по громкости, сыгранное ярче.
                    rec.utterances.filter { it.speakerId == s.id }.forEach { u ->
                        val path = wavePath(rec, u, size.width, mid, mid - 2f)
                        val played = player.positionMs >= u.startMs
                        drawPath(path, color.copy(alpha = if (off) 0.15f else if (played) 0.9f else 0.35f))
                    }
                    hits.filter { it.first == s.id }.forEach { (_, t) ->
                        val x = t.toFloat() / dur * size.width
                        drawLine(ink, Offset(x, 2f), Offset(x, size.height - 2f), 1.5.dp.toPx())
                    }
                    val px = player.positionMs.toFloat() / dur * size.width
                    drawLine(ink, Offset(px, 0f), Offset(px, size.height), 1.5.dp.toPx())
                }
            }
        }
    }
}

/** Контур реплики на дорожке: сверху и снизу от линии на величину громкости в этот момент. */
private fun wavePath(rec: Recording, u: Utterance, width: Float, mid: Float, maxA: Float): androidx.compose.ui.graphics.Path {
    val dur = rec.durationMs.coerceAtLeast(1).toFloat()
    val env = rec.envelope
    val x0 = u.startMs / dur * width
    val x1 = (u.endMs / dur * width).coerceAtLeast(x0 + 2f)
    fun amp(x: Float): Float {
        if (env.isEmpty()) return maxA * 0.5f
        // Точек огибающей бывает меньше, чем пикселей: между соседними точками ведём плавно,
        // иначе на короткой записи волна выходит ступеньками.
        val pos = (x / width * env.size - 0.5f).coerceIn(0f, env.lastIndex.toFloat())
        val i = pos.toInt()
        val t = pos - i
        val a = env[i]
        val b = env[minOf(i + 1, env.lastIndex)]
        val smooth = t * t * (3 - 2 * t)
        val level = a + (b - a) * smooth
        // Края реплики сводим на нет, чтобы волна не обрывалась ступенькой.
        val edge = ((x - x0) / 6f).coerceIn(0f, 1f) * ((x1 - x) / 6f).coerceIn(0f, 1f)
        return 1.2f + level * (maxA - 1.2f) * edge
    }
    val path = androidx.compose.ui.graphics.Path()
    var x = x0
    path.moveTo(x0, mid)
    while (x <= x1) { path.lineTo(x, mid - amp(x)); x += 1f }
    x = x1
    while (x >= x0) { path.lineTo(x, mid + amp(x)); x -= 1f }
    path.close()
    return path
}

/** «1 голос», «3 голоса», «5 голосов». */
private fun voicesLabel(n: Int): String {
    val word = when {
        n % 100 in 11..14 -> "голосов"
        n % 10 == 1 -> "голос"
        n % 10 in 2..4 -> "голоса"
        else -> "голосов"
    }
    return "$n $word"
}

private fun norm(s: String) = s.lowercase().replace('ё', 'е')

/** Где в разговоре встречается запрос: голос и время слова. */
private fun searchHits(rec: Recording, query: String): List<Pair<String, Long>> {
    val q = norm(query.trim())
    if (q.length < 2) return emptyList()
    return rec.utterances.flatMap { u ->
        if (u.words.isNotEmpty()) u.words.filter { norm(it.text).contains(q) }.map { u.speakerId to it.startMs }
        else if (norm(u.text).contains(q)) listOf(u.speakerId to u.startMs) else emptyList()
    }
}

@Composable
private fun TextTab(rec: Recording, player: PlayerState, query: String, onTap: (Utterance) -> Unit) {
    val list = rememberLazyListState()
    val current = rec.utterances.indexOfFirst {
        player.positionMs in it.startMs..it.endMs && (player.solo == null || it.speakerId == player.solo)
    }
    // Во время проигрывания держим текущую реплику на виду.
    LaunchedEffect(current, player.playing) {
        if (player.playing && current >= 0) list.animateScrollToItem(current)
    }
    // При поиске прокручиваем к первому совпадению.
    LaunchedEffect(query) {
        val q = norm(query.trim())
        if (q.length >= 2) rec.utterances.indexOfFirst { norm(it.text).contains(q) }.takeIf { it >= 0 }?.let { list.animateScrollToItem(it) }
    }
    if (rec.utterances.isEmpty()) {
        Text(
            nbsp("В записи не нашлось речи. Попробуйте файл, где люди говорят ближе к микрофону."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    LazyColumn(state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(rec.utterances, key = { i, _ -> i }) { i, u ->
            UtteranceRow(
                u = u,
                speaker = rec.speaker(u.speakerId),
                positionMs = if (i == current) player.positionMs else -1,
                faded = player.solo != null && u.speakerId != player.solo,
                query = query,
                onTap = onTap,
            )
        }
    }
}

/** Одна реплика. Параметры простые, поэтому Compose пропускает строки, где ничего не поменялось. */
@Composable
private fun UtteranceRow(u: Utterance, speaker: Speaker?, positionMs: Long, faded: Boolean, query: String, onTap: (Utterance) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onTap(u) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            formatTime(u.startMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp).padding(top = 2.dp),
        )
        Column(Modifier.weight(1f)) {
            if (speaker != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VoiceRosette(speaker, 16.dp, filled = false, strokeWidth = 2.2f)
                    Spacer(Modifier.width(5.dp))
                    Text(speaker.name, style = MaterialTheme.typography.labelMedium, color = Extra.colors.voice(speaker.index))
                }
            }
            Text(
                utteranceText(u, positionMs, query, speaker?.let { Extra.colors.voice(it.index) }),
                style = MaterialTheme.typography.bodyLarge,
                color = if (faded) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f) else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Текст реплики с подсвеченным текущим словом и найденными словами. */
@Composable
private fun utteranceText(u: Utterance, positionMs: Long, query: String, voice: Color?): AnnotatedString {
    val cur = voice?.copy(alpha = 0.2f) ?: MaterialTheme.colorScheme.surfaceContainerHigh
    val mark = Extra.colors.highlight
    val q = norm(query.trim())
    val words = u.words.ifEmpty { null }
    return buildAnnotatedString {
        if (words == null) {
            append(nbsp(u.text))
            return@buildAnnotatedString
        }
        words.forEachIndexed { j, w ->
            val isCur = positionMs in w.startMs until w.endMs
            val isHit = q.length >= 2 && norm(w.text).contains(q)
            when {
                isHit -> withStyle(SpanStyle(background = mark)) { append(w.text) }
                isCur -> withStyle(SpanStyle(background = cur)) { append(w.text) }
                else -> append(w.text)
            }
            if (j < words.lastIndex) append(if (w.text.length <= 2) " " else " ")
        }
    }
}

/** Вкладки как в прототипе: подчёркивание на всю ширину вкладки, общая линия снизу. */
@Composable
private fun Tabs(selected: Tab, onSelect: (Tab) -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Column {
        Row(Modifier.fillMaxWidth()) {
            Tab.entries.forEach { t ->
                val on = t == selected
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .selectable(selected = on, role = Role.Tab) { onSelect(t) }
                        .drawBehind {
                            if (on) {
                                val h = 3.dp.toPx()
                                drawRect(ink, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        t.label,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight(if (on) 600 else 500)),
                        color = if (on) ink else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SummaryTab(rec: Recording, onSeek: (Long) -> Unit, onCopy: () -> Unit, onShare: () -> Unit) {
    val s = rec.summary
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        if (s == null && rec.summaryPending) {
            Text(nbsp("Модель пишет конспект. Расшифровка уже готова, её можно читать и слушать."), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        if (s == null) {
            Text(
                nbsp("Конспекта нет: он выключен в настройках или не получился у модели."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Text(
            nbsp("Сделано нейросетью прямо на телефоне, без интернета. Тап по времени откроет это место."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val sections = remember(rec.summary, rec.utterances) { summarySections(rec) }
        if (sections == null) {
            Spacer(Modifier.height(12.dp))
            Text(nbsp(s.text), style = MaterialTheme.typography.bodyLarge)
            s.points.forEach { p -> SummaryRow(SummaryItem(p.text, p.sourceStartMs, null), null, onSeek) }
        }
        sections.orEmpty().forEach { sec ->
            Spacer(Modifier.height(18.dp))
            Text(sec.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight(600)))
            Spacer(Modifier.height(4.dp))
            sec.items.forEach { item ->
                SummaryRow(item, if (sec.withSpeaker) item.speakerId?.let(rec::speaker) else null, onSeek)
            }
        }
        Spacer(Modifier.height(20.dp))
        // Главное действие — отправить конспект; копирование рядом значком, чтобы кнопки не спорили.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onShare, modifier = Modifier.height(44.dp)) {
                Icon(AppIcons.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Поделиться конспектом")
            }
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer),
            ) { Icon(AppIcons.Copy, contentDescription = "Скопировать конспект", modifier = Modifier.size(20.dp)) }
        }
    }
}

/** Пункт с точкой слева и временем в конце строки; тап открывает это место в тексте. */
@Composable
private fun SummaryRow(item: SummaryItem, speaker: Speaker?, onSeek: (Long) -> Unit) {
    val chipBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val label = item.atMs?.let(::formatTime)
    val text = buildAnnotatedString {
        if (speaker != null) {
            withStyle(SpanStyle(color = Extra.colors.voice(speaker.index), fontWeight = FontWeight(500))) { append(speaker.name + ": ") }
        }
        append(nbsp(item.text))
        if (label != null) {
            append(" ")
            appendInlineContent("time", label)
        }
    }
    // Ширина плашки под время: цифры примерно по полсимвола плюс поля.
    val inline = if (label == null) emptyMap() else mapOf(
        "time" to InlineTextContent(Placeholder((label.length * 0.5f + 1f).em, 1.4.em, PlaceholderVerticalAlign.Center)) {
            Box(Modifier.fillMaxSize().background(chipBg, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text(it, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
            }
        },
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .clickable(enabled = item.atMs != null) { item.atMs?.let(onSeek) }
            .padding(vertical = 5.dp),
    ) {
        Text("•", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(18.dp))
        Text(text, inlineContent = inline, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoicesTab(
    rec: Recording,
    player: PlayerState,
    onSolo: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onMerge: (String, String) -> Unit,
) {
    var renaming by remember { mutableStateOf<Speaker?>(null) }
    var merging by remember { mutableStateOf<Speaker?>(null) }
    val total = rec.utterances.sumOf { it.endMs - it.startMs }.coerceAtLeast(1)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        rec.speakers.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            val mine = rec.utterances.filter { it.speakerId == s.id }
            val share = mine.sumOf { it.endMs - it.startMs } * 100 / total
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                VoiceRosette(s, 84.dp, layers = mine.size.coerceIn(1, 4))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), color = Extra.colors.voice(s.index))
                    Text(
                        "$share% разговора, ${repliesLabel(mine.size)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(if (player.solo == s.id) "Слушать всех" else "Слушать") { onSolo(s.id) }
                        Pill("Переименовать") { renaming = s }
                        if (rec.speakers.size > 1) Pill("Объединить") { merging = s }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    renaming?.let { s ->
        RenameDialog("Имя голоса", s.name, onDismiss = { renaming = null }) { onRename(s.id, it); renaming = null }
    }
    merging?.let { from ->
        AlertDialog(
            onDismissRequest = { merging = null },
            containerColor = dialogColor,
            title = { Text("Объединить «${from.name}» с…") },
            text = {
                Column {
                    Text(
                        nbsp("Если один человек попал в два голоса. Его реплики перейдут к выбранному голосу, JSON пересоберётся."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    rec.speakers.filter { it.id != from.id }.forEach { into ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { merging = null; onMerge(from.id, into.id) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            VoiceRosette(into, 32.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(into.name, style = MaterialTheme.typography.titleMedium, color = Extra.colors.voice(into.index))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { merging = null }) { Text("Отмена") } },
        )
    }
}

/** Серая кнопка-плашка, как в прототипе. */
@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp))
    }
}

/** «1 реплика», «3 реплики», «5 реплик». */
private fun repliesLabel(n: Int): String {
    val word = when {
        n % 100 in 11..14 -> "реплик"
        n % 10 == 1 -> "реплика"
        n % 10 in 2..4 -> "реплики"
        else -> "реплик"
    }
    return "$n $word"
}

@Composable
private fun PlayerBar(rec: Recording, player: PlayerState, onTogglePlay: () -> Unit, onSkip: (Long) -> Unit, onSpeed: () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    // Звук могли удалить в настройках, чтобы освободить место: расшифровка остаётся, слушать нечего.
    val hasAudio = remember(rec) { java.io.File(rec.audioPath).isFile }
    if (!hasAudio) {
        Text(
            nbsp("Аудио удалено в настройках, расшифровка сохранена"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
        )
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FilledIconButton(
            onClick = onTogglePlay,
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(if (player.playing) AppIcons.Pause else AppIcons.Play, contentDescription = if (player.playing) "Пауза" else "Слушать")
        }
        IconButton(onClick = { onSkip(-5_000) }) { Icon(AppIcons.Rewind, contentDescription = "Назад 5 секунд") }
        IconButton(onClick = { onSkip(5_000) }) { Icon(AppIcons.Forward, contentDescription = "Вперёд 5 секунд") }
        Text(
            if (player.speed == 1f) "1×" else "${player.speed}×".replace('.', ','),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClickLabel = "Скорость", onClick = onSpeed)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            "${formatTime(player.positionMs)} / ${formatTime(rec.durationMs)}",
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun RenameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogColor,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus))
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
