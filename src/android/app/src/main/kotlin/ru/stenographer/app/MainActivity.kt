package ru.stenographer.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import ru.stenographer.app.data.ThemeMode
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import ru.stenographer.app.ui.export.ExportSheet
import ru.stenographer.app.ui.nbsp
import ru.stenographer.app.ui.settings.StorageInfo
import ru.stenographer.app.ui.theme.dialogColor
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.stenographer.app.data.Recording
import ru.stenographer.app.data.summaryText
import ru.stenographer.app.export.TranscriptExport
import ru.stenographer.app.service.RecordingService
import ru.stenographer.app.ui.components.AppIcons
import ru.stenographer.app.ui.home.HomeScreen
import ru.stenographer.app.ui.processing.ProcessingScreen
import ru.stenographer.app.ui.record.RecordScreen
import ru.stenographer.app.ui.settings.SettingsScreen
import ru.stenographer.app.ui.theme.StenographerTheme
import ru.stenographer.app.ui.transcript.TranscriptScreen
import ru.stenographer.app.ui.transcript.TranscriptViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

private enum class MicDialog { NONE, ASK, SETTINGS }

/** Экраны приложения. Навигация — простой стек: экранов мало, библиотека не нужна. */
sealed interface Route {
    data object Home : Route
    data object Settings : Route
    data class Record(val id: String, val file: String) : Route
    data class Processing(val id: String) : Route
    data class Transcript(val id: String) : Route
}

class MainActivity : ComponentActivity() {

    private val container get() = (application as StenographerApp).container

    /** Стек экранов; последний — открытый. */
    private val stack = mutableStateListOf<Route>(Route.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Если запись с микрофона уже идёт (приложение открыли из уведомления), возвращаемся к ней.
        RecordingService.state.value.takeIf { it.active }?.file?.let { stack.add(Route.Record(it.nameWithoutExtension, it.absolutePath)) }
        if (savedInstanceState == null) handleShared(intent)
        setContent {
            val theme by container.settings.theme.collectAsStateWithLifecycle()
            // Значки строки состояния под тему приложения, а не системы: иначе в тёмной теме
            // при светлой системе часы и батарея тёмные на тёмном и не видны.
            val dark = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }
            StenographerTheme(theme) {
                // Surface задаёт цвет текста по умолчанию под тему, иначе в тёмной теме текст остаётся чёрным.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShared(intent)
    }

    /** Аудио, присланное из другого приложения через «Поделиться» или «Открыть с помощью». */
    private fun handleShared(intent: Intent?) {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableListExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val id = container.importAndQueue(uris)
            if (id != null) open(Route.Processing(id)) else toast("Не удалось открыть файл")
        }
    }

    private fun open(r: Route) {
        stack.add(r)
    }

    private fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish()
    }

    /** Заменяет открытый экран, чтобы «Назад» не возвращал на него. */
    private fun replace(r: Route) {
        stack[stack.lastIndex] = r
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App() {
        val recordings by container.repository.recordings.collectAsStateWithLifecycle()
        val live by container.queue.live.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var exporting by remember { mutableStateOf<List<Recording>?>(null) }
        var pendingJson by remember { mutableStateOf<Pair<String, String>?>(null) }

        // Android 8–9: сохранить файл можно только через системный диалог «Сохранить как».
        val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val content = pendingJson?.second
            if (uri != null && content != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                toast("Сохранено")
            }
            pendingJson = null
        }

        val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) scope.launch {
                val id = container.importAndQueue(uris)
                if (id != null) open(Route.Processing(id)) else toast("Не удалось открыть файл")
            }
        }

        val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        fun askNotifications() {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        fun startRecording() {
            val id = UUID.randomUUID().toString()
            val file = container.repository.newRecordingFile(id)
            RecordingService.start(this, file)
            open(Route.Record(id, file.absolutePath))
        }

        // Перед системным запросом объясняем, зачем микрофон; если запрещён навсегда, ведём в настройки.
        var micDialog by remember { mutableStateOf(MicDialog.NONE) }
        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            when {
                granted -> startRecording()
                !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> micDialog = MicDialog.SETTINGS
                else -> toast("Без микрофона можно загружать файлы")
            }
        }

        val onRecord = {
            askNotifications()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
            else micDialog = MicDialog.ASK
        }
        val onImport = {
            askNotifications()
            pickFiles.launch(arrayOf("audio/*", "video/*"))
        }

        fun saveJson(recs: List<Recording>) {
            for (r in recs) {
                val name = TranscriptExport.fileName(r, "json")
                val json = TranscriptExport.toJson(r)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching {
                        TranscriptExport.saveToDownloads(this, name, "application/json", json)
                        if (container.settings.exportTxt.value) {
                            TranscriptExport.saveToDownloads(this, TranscriptExport.fileName(r, "txt"), "text/plain", TranscriptExport.toText(r))
                        }
                    }.onFailure { toast("Не удалось сохранить: ${it.message}") }
                } else {
                    pendingJson = name to json
                    saveAs.launch(name)
                    return
                }
            }
            toast(if (recs.size == 1) "Сохранено в Загрузки: ${TranscriptExport.fileName(recs[0], "json")}" else "Сохранено файлов в Загрузки: ${recs.size}")
        }

        fun share(name: String, content: String, mime: String) {
            val file = TranscriptExport.shareFile(this, name, content)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, null))
        }

        BackHandler(stack.size > 1) { back() }

        when (val route = stack.last()) {
            Route.Home -> HomeScreen(
                recordings = recordings,
                live = live,
                onRecord = onRecord,
                onImport = onImport,
                onOpen = { r -> open(if (r.status == Recording.Status.DONE) Route.Transcript(r.id) else Route.Processing(r.id)) },
                onSettings = { open(Route.Settings) },
                onExportAll = { exporting = recordings.filter { it.status == Recording.Status.DONE } },
            )

            Route.Settings -> {
                val theme by container.settings.theme.collectAsStateWithLifecycle()
                val summary by container.settings.summary.collectAsStateWithLifecycle()
                val exportTxt by container.settings.exportTxt.collectAsStateWithLifecycle()
                SettingsScreen(
                    theme = theme,
                    summary = summary,
                    exportTxt = exportTxt,
                    storage = remember(recordings) { storageInfo(recordings) },
                    onTheme = container.settings::setTheme,
                    onSummary = container.settings::setSummary,
                    onExportTxt = container.settings::setExportTxt,
                    onClearAudio = { container.repository.clearAudio(); toast("Аудио удалено") },
                    onBack = ::back,
                )
            }

            is Route.Record -> {
                val state by RecordingService.state.collectAsStateWithLifecycle()
                RecordScreen(
                    state = state,
                    onPause = { RecordingService.pause(this) },
                    onResume = { RecordingService.resume(this) },
                    onDone = {
                        RecordingService.stop(this)
                        lifecycleScope.launch {
                            // Ждём, пока сервис допишет заголовок WAV, иначе файл не прочитается.
                            RecordingService.state.first { !it.active }
                            val title = "Запись " + SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(System.currentTimeMillis())
                            container.addRecorded(route.id, File(route.file), title)
                            replace(Route.Processing(route.id))
                        }
                    },
                    onDiscard = {
                        RecordingService.stop(this)
                        lifecycleScope.launch {
                            RecordingService.state.first { !it.active }
                            File(route.file).delete()
                        }
                        back()
                    },
                )
            }

            is Route.Processing -> {
                val rec = recordings.firstOrNull { it.id == route.id }
                if (rec == null) { LaunchedEffect(Unit) { back() }; return }
                // Готово, пока экран открыт: сразу показываем расшифровку.
                LaunchedEffect(rec.status) { if (rec.status == Recording.Status.DONE) replace(Route.Transcript(rec.id)) }
                ProcessingScreen(
                    rec = rec,
                    live = live?.takeIf { it.recordingId == rec.id },
                    queued = recordings.filter { it.status == Recording.Status.QUEUED && it.id != rec.id }.reversed(),
                    onBack = ::back,
                    onRetry = { container.retry(rec.id) },
                    onDelete = { container.repository.delete(rec.id); back() },
                )
            }

            is Route.Transcript -> {
                val rec = recordings.firstOrNull { it.id == route.id }
                if (rec == null) { LaunchedEffect(Unit) { back() }; return }
                val vm: TranscriptViewModel = viewModel(
                    key = rec.id,
                    factory = viewModelFactory { initializer { TranscriptViewModel(application, rec.id) } },
                )
                DisposableEffect(rec.id) { onDispose { vm.pause() } }
                val player by vm.state.collectAsStateWithLifecycle()
                TranscriptScreen(
                    rec = rec,
                    player = player,
                    onBack = ::back,
                    onTogglePlay = vm::togglePlay,
                    onSeek = vm::seekTo,
                    onSkip = vm::skip,
                    onSolo = vm::toggleSolo,
                    onSpeed = vm::cycleSpeed,
                    onExport = { exporting = listOf(rec) },
                    onCopy = {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(rec.title, TranscriptExport.toText(rec)))
                        if (Build.VERSION.SDK_INT < 33) toast("Текст скопирован")
                    },
                    onRename = vm::rename,
                    onRenameSpeaker = vm::renameSpeaker,
                    onMergeSpeaker = vm::mergeSpeaker,
                    onCopySummary = {
                        summaryText(rec)?.let { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(rec.title, it)) }
                        if (Build.VERSION.SDK_INT < 33) toast("Конспект скопирован")
                    },
                    onShareSummary = {
                        summaryText(rec)?.let { text ->
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                        }
                    },
                    onDelete = { vm.delete(); back() },
                )
            }
        }

        exporting?.let { recs ->
            val allDone = recordings.filter { it.status == Recording.Status.DONE }
            // Лист сразу открыт целиком: кнопки не должны прятаться под краем экрана.
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(onDismissRequest = { exporting = null }, sheetState = sheetState, containerColor = dialogColor) {
                ExportSheet(
                    recs = recs,
                    allCount = allDone.size,
                    onSave = { exporting = null; saveJson(recs) },
                    onShare = { r -> exporting = null; share(TranscriptExport.fileName(r, "json"), TranscriptExport.toJson(r), "application/json") },
                    onExportAll = { exporting = allDone },
                )
            }
        }

        when (micDialog) {
            MicDialog.NONE -> Unit
            MicDialog.ASK -> AlertDialog(
                onDismissRequest = { micDialog = MicDialog.NONE },
                containerColor = dialogColor,
                title = { Text("Разрешить запись звука?") },
                text = { Text(nbsp("Микрофон нужен только для записи разговора. Звук не покидает телефон.")) },
                confirmButton = {
                    TextButton(onClick = { micDialog = MicDialog.NONE; micPermission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Разрешить") }
                },
                dismissButton = {
                    TextButton(onClick = { micDialog = MicDialog.NONE; toast("Без микрофона можно загружать файлы") }) { Text("Не разрешать") }
                },
            )
            MicDialog.SETTINGS -> AlertDialog(
                onDismissRequest = { micDialog = MicDialog.NONE },
                containerColor = dialogColor,
                title = { Text("Микрофон выключен") },
                text = { Text(nbsp("Включите его в настройках телефона: Разрешения → Микрофон. Без микрофона можно загружать готовые файлы.")) },
                confirmButton = {
                    TextButton(onClick = {
                        micDialog = MicDialog.NONE
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    }) { Text("Открыть настройки") }
                },
                dismissButton = { TextButton(onClick = { micDialog = MicDialog.NONE }) { Text("Отмена") } },
            )
        }
    }

    /** Размер аудио записей и моделей во внутренней памяти: всё, кроме папки аудио, — модели. */
    private fun storageInfo(recordings: List<Recording>): StorageInfo {
        val audio = recordings.map { File(it.audioPath) }.filter { it.isFile }
        val models = filesDir.listFiles().orEmpty().filter { it.isDirectory && it.name != "audio" }.sumOf { dir ->
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        return StorageInfo(audio.sumOf { it.length() }, audio.size, models)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

@Suppress("DEPRECATION")
private fun Intent.parcelableExtra(key: String): Uri? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Uri::class.java) else getParcelableExtra(key)

@Suppress("DEPRECATION")
private fun Intent.parcelableListExtra(key: String): List<Uri> =
    (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, Uri::class.java) else getParcelableArrayListExtra(key)) ?: emptyList()
