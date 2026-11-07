package ru.stenographer.app.ui.transcript

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.stenographer.app.StenographerApp
import ru.stenographer.app.data.Recording
import ru.stenographer.app.data.mergeSpeaker
import java.io.File

data class PlayerState(
    val positionMs: Long = 0,
    val playing: Boolean = false,
    /** Голос, который слушаем отдельно, или null. */
    val solo: String? = null,
    val speed: Float = 1f,
)

/**
 * Плеер расшифровки. Соло голоса сделано перемоткой: когда текущий голос
 * замолкает, перескакиваем к его следующей реплике. Резать звук не нужно.
 */
class TranscriptViewModel(app: Application, val recordingId: String) : AndroidViewModel(app) {
    private val container = (app as StenographerApp).container
    val recording: StateFlow<List<Recording>> = container.repository.recordings

    private val player = ExoPlayer.Builder(app).build()
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    init {
        container.repository.get(recordingId)?.let {
            player.setMediaItem(MediaItem.fromUri(File(it.audioPath).toURI().toString()))
            player.prepare()
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(playing = isPlaying) }
            }
        })
        // Позиция для подсветки слова: 20 раз в секунду хватает глазу.
        viewModelScope.launch {
            while (isActive) {
                if (player.isPlaying) {
                    _state.update { it.copy(positionMs = player.currentPosition) }
                    followSolo()
                }
                delay(50)
            }
        }
    }

    private fun current(): Recording? = container.repository.get(recordingId)

    fun togglePlay() {
        if (player.isPlaying) player.pause()
        else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    fun pause() = player.pause()

    fun seekTo(ms: Long, play: Boolean = false) {
        player.seekTo(ms.coerceAtLeast(0))
        _state.update { it.copy(positionMs = ms.coerceAtLeast(0)) }
        if (play) player.play()
    }

    fun skip(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)

    fun toggleSolo(speakerId: String) {
        _state.update { it.copy(solo = if (it.solo == speakerId) null else speakerId) }
    }

    fun cycleSpeed() {
        val next = when (_state.value.speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        player.playbackParameters = PlaybackParameters(next)
        _state.update { it.copy(speed = next) }
    }

    private fun followSolo() {
        val solo = _state.value.solo ?: return
        val rec = current() ?: return
        val pos = player.currentPosition
        val mine = rec.utterances.filter { it.speakerId == solo }
        if (mine.any { pos in it.startMs..it.endMs }) return
        val next = mine.firstOrNull { it.startMs > pos }
        if (next != null) player.seekTo(next.startMs) else player.pause()
    }

    fun rename(title: String) {
        container.repository.update(recordingId) { it.copy(title = title.trim().ifEmpty { it.title }, titleEdited = true) }
    }

    fun renameSpeaker(speakerId: String, name: String) {
        container.repository.update(recordingId) { r ->
            r.copy(speakers = r.speakers.map { if (it.id == speakerId) it.copy(name = name.trim().ifEmpty { it.name }) else it })
        }
    }

    fun mergeSpeaker(from: String, into: String) {
        container.repository.update(recordingId) { it.mergeSpeaker(from, into) }
        _state.update { if (it.solo == from) it.copy(solo = null) else it }
    }

    fun delete() {
        player.stop()
        container.repository.delete(recordingId)
    }

    override fun onCleared() {
        player.release()
    }
}
