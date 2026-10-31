package com.sambouli.audiowarp.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sambouli.audiowarp.audio.Recorder
import com.sambouli.audiowarp.data.ClipStore
import com.sambouli.audiowarp.dsp.Effect
import com.sambouli.audiowarp.dsp.EffectEngine
import com.sambouli.audiowarp.dsp.WavIO
import com.sambouli.audiowarp.player.PlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single source of truth for the screen. All long work (DSP, IO) runs off the main thread;
 * UI observes [state].
 */
class WarpViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val recording: Boolean = false,
        val rmsLevel: Float = 0f,
        val busy: Boolean = false,
        val selectedEffect: Effect = Effect.NONE,
        val sourceWav: ByteArray? = null,       // raw recording
        val processedWav: ByteArray? = null,    // last applied effect output
        val waveform: List<Float> = emptyList(),// envelope for the canvas
        val positionSec: Float = 0f,
        val durationSec: Float = 0f,
        val playing: Boolean = false,
        val loop: Boolean = false,
        val clips: List<ClipStore.Clip> = emptyList(),
        val activeClipId: String? = null,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val recorder = Recorder(app)
    val player = PlayerEngine()
    private val store = ClipStore(app)
    private val engine = EffectEngine(WavIO.SAMPLE_RATE)

    fun consumeMessage(): String? = _state.value.message.also { _state.value = _state.value.copy(message = null) }

    fun selectEffect(effect: Effect) {
        _state.value = _state.value.copy(selectedEffect = effect)
        // Re-apply instantly if we already have a source clip.
        _state.value.sourceWav?.let { applySelected(it) }
    }

    fun toggleRecord(onPermissionMissing: () -> Unit) {
        if (recorder.isRecording) {
            val wav = recorder.stop()
            _state.value = _state.value.copy(recording = false, rmsLevel = 0f)
            loadSource(wav)
        } else {
            if (!recorder.hasPermission) { onPermissionMissing(); return }
            val ok = recorder.start(viewModelScope)
            if (ok) {
                player.stop()
                _state.value = _state.value.copy(
                    recording = true, sourceWav = null, processedWav = null,
                    waveform = emptyList(), positionSec = 0f, durationSec = 0f,
                    playing = false, activeClipId = null,
                )
                pollLevels()
            } else {
                _state.value = _state.value.copy(message = "Microphone unavailable")
            }
        }
    }

    private fun pollLevels() {
        viewModelScope.launch {
            while (_state.value.recording) {
                _state.value = _state.value.copy(rmsLevel = recorder.rmsLevel)
                kotlinx.coroutines.delay(33)
            }
        }
    }

    /** Load a WAV (fresh recording or stored clip) as the current editing source. */
    fun loadSource(wav: ByteArray, clipId: String? = null) {
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val (samples, sr) = withContext(Dispatchers.Default) { WavIO.wavToFloats(wav) }
            val env = envelope(samples, BARS)
            player.load(samples, sr)
            _state.value = _state.value.copy(
                busy = false,
                sourceWav = wav,
                processedWav = null,
                waveform = env,
                durationSec = player.durationSec.toFloat(),
                positionSec = 0f,
                playing = false,
                activeClipId = clipId,
            )
            if (_state.value.selectedEffect != Effect.NONE) applySelected(wav)
        }
    }

    private fun applySelected(sourceWav: ByteArray) {
        val effect = _state.value.selectedEffect
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val out = withContext(Dispatchers.Default) {
                val (samples, sr) = WavIO.wavToFloats(sourceWav)
                val processed = engine.apply(effect, samples)
                WavIO.floatsToWav(processed, sr)
            }
            val (samples, sr) = WavIO.wavToFloats(out)
            val pos = player.positionSec.toFloat()
            player.load(samples, sr)
            _state.value = _state.value.copy(
                busy = false,
                processedWav = out,
                waveform = envelope(samples, BARS),
                durationSec = player.durationSec.toFloat(),
                positionSec = pos.coerceAtMost(player.durationSec.toFloat()),
                playing = false,
                message = if (effect == Effect.NONE) null else "${effect.label} applied",
            )
        }
    }

    fun togglePlay() {
        if (player.isEmpty) return
        if (player.state == PlayerEngine.State.PLAYING) {
            player.pause()
            _state.value = _state.value.copy(playing = false)
        } else {
            player.play(viewModelScope)
            _state.value = _state.value.copy(playing = true)
            pollPosition()
        }
    }

    private var polling = false
    private fun pollPosition() {
        if (polling) return
        polling = true
        viewModelScope.launch {
            while (player.state == PlayerEngine.State.PLAYING) {
                _state.value = _state.value.copy(positionSec = player.positionSec.toFloat())
                kotlinx.coroutines.delay(50)
            }
            polling = false
            _state.value = _state.value.copy(playing = false, positionSec = player.positionSec.toFloat())
        }
    }

    fun seek(sec: Float) {
        player.seekTo(sec.toDouble())
        _state.value = _state.value.copy(positionSec = player.positionSec.toFloat())
    }

    fun toggleLoop() {
        player.loop = !player.loop
        _state.value = _state.value.copy(loop = player.loop)
    }

    fun saveClip() {
        val wav = _state.value.processedWav ?: _state.value.sourceWav ?: return
        viewModelScope.launch {
            val clip = withContext(Dispatchers.IO) {
                store.save(wav, _state.value.selectedEffect, _state.value.activeClipId)
            }
            refreshClips()
            _state.value = _state.value.copy(activeClipId = clip.id, message = "Saved ${clip.name}")
        }
    }

    /** Returns a share intent for the current (processed) audio, or null when nothing loaded. */
    fun shareIntent(): Intent? {
        val wav = _state.value.processedWav ?: _state.value.sourceWav ?: return null
        val ctx = getApplication<Application>()
        val dir = java.io.File(ctx.cacheDir, "shared").apply { mkdirs() }
        val name = "audiowarp-${_state.value.selectedEffect.id}.wav"
        val f = java.io.File(dir, name)
        f.writeBytes(wav)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        return Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun refreshClips() {
        viewModelScope.launch {
            val clips = withContext(Dispatchers.IO) { store.list() }
            _state.value = _state.value.copy(clips = clips)
        }
    }

    fun openClip(id: String) {
        val clip = store.find(id) ?: return
        _state.value = _state.value.copy(selectedEffect = clip.effect)
        loadSource(clip.file.readBytes(), clipId = clip.id)
    }

    fun deleteClip(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            refreshClips()
            if (_state.value.activeClipId == id) {
                _state.value = _state.value.copy(sourceWav = null, processedWav = null, waveform = emptyList(), activeClipId = null)
                player.stop()
            }
        }
    }

    companion object {
        const val BARS = 160
        const val MAX_RECORD_SEC = 30

        /** Peak envelope with [bars] buckets, values 0..1. Pure + tested. */
        fun envelope(samples: FloatArray, bars: Int): List<Float> {
            if (samples.isEmpty() || bars <= 0) return List(maxOf(bars, 0)) { 0f }
            val out = FloatArray(bars)
            val bucket = (samples.size + bars - 1) / bars
            for (b in 0 until bars) {
                val start = b * bucket
                val end = minOf(samples.size, start + bucket)
                var peak = 0f
                var i = start
                while (i < end) {
                    val a = kotlin.math.abs(samples[i])
                    if (a > peak) peak = a
                    i++
                }
                out[b] = peak.coerceIn(0f, 1f)
            }
        return out.toList()
        }
    }
}
