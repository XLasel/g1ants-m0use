package ru.stenographer.app.ui.export

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.stenographer.app.data.Recording
import ru.stenographer.app.export.TranscriptExport
import ru.stenographer.app.ui.components.AppIcons
import ru.stenographer.app.ui.nbsp
import ru.stenographer.app.ui.theme.Extra

/**
 * Лист экспорта. Для одной записи показывает начало JSON, чтобы было видно формат задания,
 * и куда ляжет файл. Для всех записей — сколько файлов и как они будут названы.
 */
@Composable
fun ExportSheet(
    recs: List<Recording>,
    allCount: Int,
    onSave: () -> Unit,
    onShare: (Recording) -> Unit,
    onExportAll: () -> Unit,
) {
    val single = recs.singleOrNull()
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
        Text(if (single != null) "Экспорт" else "Экспорт всех записей: ${recs.size}", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(14.dp))
        if (single != null) {
            Text(
                jsonPreview(single),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp),
                maxLines = 12,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth()
                    .background(if (Extra.colors.glow) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
                    .padding(14.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
        Text(
            nbsp(
                (if (single != null) "Download/${TranscriptExport.fileName(single, "json")}. " else "В Download по файлу на запись, имена как у исходных аудио. ") +
                    "Формат задания: голос, начало и конец в миллисекундах, текст",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(AppIcons.Download, null, Modifier.size(20.dp)); Spacer(Modifier.size(8.dp)); Text("Сохранить JSON")
        }
        if (single != null) {
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = { onShare(single) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(AppIcons.Share, null, Modifier.size(20.dp)); Spacer(Modifier.size(8.dp)); Text("Поделиться")
            }
            if (allCount > 1) {
                TextButton(onClick = onExportAll, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Экспортировать все $allCount ${recordsWord(allCount)}")
                }
            }
        }
    }
}

/** Первая реплика в формате задания и сколько ещё осталось. */
private fun jsonPreview(rec: Recording): String {
    val u = rec.utterances.firstOrNull() ?: return "{\n  \"transcript\": []\n}"
    val text = if (u.text.length > 24) u.text.take(24).trimEnd() + "…" else u.text
    val rest = rec.utterances.size - 1
    return buildString {
        append("{\n  \"transcript\": [\n    {\n")
        append("      \"speaker_id\": \"speaker_1\",\n")
        append("      \"start_ms\": ${u.startMs},\n")
        append("      \"end_ms\": ${u.endMs},\n")
        append("      \"text\": \"$text\"\n    }")
        if (rest > 0) append(",\n    …ещё $rest ${repliesWord(rest)}")
        append("\n  ]\n}")
    }
}

private fun recordsWord(n: Int): String = when {
    n % 100 in 11..14 -> "записей"
    n % 10 == 1 -> "запись"
    n % 10 in 2..4 -> "записи"
    else -> "записей"
}

private fun repliesWord(n: Int): String = when {
    n % 100 in 11..14 -> "реплик"
    n % 10 == 1 -> "реплика"
    n % 10 in 2..4 -> "реплики"
    else -> "реплик"
}
