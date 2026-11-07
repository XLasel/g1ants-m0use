package ru.stenographer.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.stenographer.app.MainActivity
import ru.stenographer.app.R
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.sqrt

data class RecorderState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val elapsedMs: Long = 0,
    /** Громкость последних кусочков по 50 мс, 0..1, для ленты на экране. */
    val levels: List<Float> = emptyList(),
    val bytes: Long = 0,
    val file: File? = null,
    /** Почему запись остановилась сама: микрофон занят, кончилось место. Записанное до этого сохранено. */
    val error: String? = null,
)

/**
 * Запись с микрофона в WAV 16 кГц моно — ровно тот формат, что ждут модели,
 * поэтому перекодировать ничего не нужно.
 * Работает как foreground-сервис: запись продолжается при свёрнутом
 * приложении и погашенном экране, а в шторке видно, что микрофон включён.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val path = intent.getStringExtra(EXTRA_FILE) ?: return START_NOT_STICKY
                startInForeground()
                start(File(path))
            }
            ACTION_PAUSE -> _state.update { it.copy(paused = true) }
            ACTION_RESUME -> _state.update { it.copy(paused = false) }
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notifications.builder(this, Notifications.CHANNEL_RECORDING)
            .setContentTitle(getString(R.string.notif_recording))
            .setUsesChronometer(true)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(Notifications.ID_RECORDING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(Notifications.ID_RECORDING, n)
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun start(file: File) {
        if (job?.isActive == true) return
        _state.value = RecorderState(active = true, file = file)
        job = scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val chunk = SAMPLE_RATE / 20 // 50 мс
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, chunk * 4),
            )
            val out = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
            val buf = ShortArray(chunk)
            val bytes = ByteArray(chunk * 2)
            var written = 0L
            try {
                // Микрофон может быть занят звонком или другим приложением: тогда AudioRecord не запускается.
                if (record.state != AudioRecord.STATE_INITIALIZED) throw MicBusyException()
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw MicBusyException()
                while (isActive) {
                    val n = record.read(buf, 0, chunk)
                    if (n < 0) throw MicBusyException()
                    if (n == 0 || _state.value.paused) continue
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = buf[i].toInt()
                        bytes[i * 2] = (v and 0xff).toByte()
                        bytes[i * 2 + 1] = (v shr 8 and 0xff).toByte()
                        sum += (v * v).toDouble()
                    }
                    out.write(bytes, 0, n * 2)
                    written += n * 2
                    // Раз в секунду обновляем заголовок WAV: если систему или телефон выключат посреди записи,
                    // файл останется читаемым, и при следующем запуске приложение его подберёт.
                    if (written % BYTES_PER_SECOND < n * 2) {
                        val end = out.filePointer
                        writeWavHeader(out, written)
                        out.seek(end)
                    }
                    // Корень из средней энергии, поднятый так, чтобы обычная речь занимала полленты.
                    val level = (sqrt(sum / n) / 6000.0).toFloat().coerceIn(0f, 1f)
                    _state.update {
                        it.copy(
                            elapsedMs = written / 2 * 1000 / SAMPLE_RATE,
                            levels = (it.levels + level).takeLast(MAX_LEVELS),
                            bytes = written,
                        )
                    }
                }
            } catch (e: MicBusyException) {
                _state.update { it.copy(error = "Микрофон занят другим приложением или звонком.") }
            } catch (e: IOException) {
                _state.update { it.copy(error = "На телефоне кончилось место.") }
            } finally {
                runCatching { record.stop() }
                record.release()
                runCatching { writeWavHeader(out, written) }
                runCatching { out.close() }
            }
        }
    }

    private class MicBusyException : Exception()

    private fun stop() {
        scope.launch {
            job?.cancel()
            job?.join()
            _state.update { it.copy(active = false) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Заголовок WAV: 44 байта в начале файла, длины известны только в конце записи. */
    private fun writeWavHeader(f: RandomAccessFile, dataBytes: Long) {
        fun le32(v: Long) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte(), (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte())
        fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())
        f.seek(0)
        f.write("RIFF".toByteArray()); f.write(le32(36 + dataBytes)); f.write("WAVE".toByteArray())
        f.write("fmt ".toByteArray()); f.write(le32(16)); f.write(le16(1)); f.write(le16(1))
        f.write(le32(SAMPLE_RATE.toLong())); f.write(le32(SAMPLE_RATE * 2L)); f.write(le16(2)); f.write(le16(16))
        f.write("data".toByteArray()); f.write(le32(dataBytes))
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MAX_LEVELS = 400
        private const val BYTES_PER_SECOND = SAMPLE_RATE * 2L
        private const val ACTION_START = "start"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_FILE = "file"

        private val _state = MutableStateFlow(RecorderState())
        val state: StateFlow<RecorderState> = _state.asStateFlow()

        fun start(context: Context, file: File) {
            val i = Intent(context, RecordingService::class.java).setAction(ACTION_START).putExtra(EXTRA_FILE, file.absolutePath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            context.startService(Intent(context, RecordingService::class.java).setAction(action))
        }
    }
}
