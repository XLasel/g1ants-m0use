package ru.stenographer.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.stenographer.app.data.ThemeMode
import ru.stenographer.app.ui.components.AppIcons
import ru.stenographer.app.ui.nbsp
import ru.stenographer.app.ui.theme.dialogColor

/** Сколько места занимают записи и модели, для раздела «Память». */
data class StorageInfo(val audioBytes: Long, val audioCount: Int, val modelBytes: Long)

/** Настройки по прототипу: оформление, расшифровка, экспорт, память. */
@Composable
fun SettingsScreen(
    theme: ThemeMode,
    summary: Boolean,
    exportTxt: Boolean,
    storage: StorageInfo,
    onTheme: (ThemeMode) -> Unit,
    onSummary: (Boolean) -> Unit,
    onExportTxt: (Boolean) -> Unit,
    onClearAudio: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "Назад") }
            Text("Настройки", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Header("Оформление")
            Segmented(
                options = listOf(ThemeMode.SYSTEM to "Системная", ThemeMode.LIGHT to "Светлая", ThemeMode.DARK to "Тёмная"),
                selected = theme,
                onSelect = onTheme,
            )

            Header("Расшифровка")
            Group {
                SwitchRow("Конспект и название", "Локальная модель на телефоне, после расшифровки", summary, onSummary)
            }

            Header("Экспорт")
            Group {
                InfoRow("Папка", "Download")
                SwitchRow("Ещё текстом (TXT)", "Рядом с JSON, чтобы читать глазами", exportTxt, onExportTxt)
            }

            Header("Память")
            Group {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Аудио записей", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${megabytes(storage.audioBytes)}, ${recordsLabel(storage.audioCount)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (storage.audioCount > 0) {
                        TextButton(onClick = { confirmClear = true }) {
                            Text("Очистить", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight(600))
                        }
                    }
                }
                Divider()
                InfoRow(
                    "Модели",
                    if (storage.modelBytes > 0) "Встроены в приложение, ${megabytes(storage.modelBytes)} в памяти телефона"
                    else "Встроены в приложение, интернет не нужен",
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                nbsp("Расшифровывает разговоры прямо на телефоне. Записи и тексты не уходят в интернет."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = dialogColor,
            title = { Text("Удалить аудио записей?") },
            text = { Text(nbsp("Расшифровки и конспекты останутся, но слушать записи будет нельзя. Это нельзя отменить.")) },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClearAudio() }) { Text("Удалить аудио") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        content()
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(nbsp(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(nbsp(value), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Переключатель на несколько вариантов: серая подложка, выбранный вариант белой плашкой. */
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(3.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .then(
                        if (on) Modifier.background(MaterialTheme.colorScheme.background)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(13.dp))
                        else Modifier,
                    )
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight(if (on) 600 else 400)),
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun megabytes(bytes: Long): String = "${(bytes + 999_999) / 1_000_000} МБ"

/** «1 запись», «3 записи», «6 записей». */
private fun recordsLabel(n: Int): String {
    val word = when {
        n % 100 in 11..14 -> "записей"
        n % 10 == 1 -> "запись"
        n % 10 in 2..4 -> "записи"
        else -> "записей"
    }
    return "$n $word"
}
