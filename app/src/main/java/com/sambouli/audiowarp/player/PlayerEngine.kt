package com.sambouli.audiowarp.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.sambouli.audiowarp.dsp.WavIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * PCM playback engine over AudioTrack (static mode, reloaded per seek) with play/pause/seek/loop.
 * Position tracking is wall-clock vs the written buffer so tests can drive [positionSec]
 * deterministically without a real audio sink.
 */
class PlayerEngine {

    enum class State { IDLE, PLAYING, PAUSED, FINISHED }

    @Volatile var state: State = State.IDLE
        private set
    @Volatile var loop: Boolean = false
    @Volatile var volume: Float = 1f

    private var samples: FloatArray = FloatArray(0)
    private var sampleRate: Int = WavIO.SAMPLE_RATE
    @Volatile private var cursor: Int = 0 // next sample index to play

    private var track: AudioTrack? = null
    private var ticker: Job? = null
    private var lastScope: CoroutineScope? = null

    val durationSec: Double get() = if (sampleRate > 0) samples.size.toDouble() / sampleRate else 0.0
    val positionSec: Double get() = cursor.toDouble() / sampleRate
    val isEmpty: Boolean get() = samples.isEmpty()

    fun load(samples: FloatArray, sampleRate: Int = WavIO.SAMPLE_RATE) {
        stopInternal()
        this.samples = samples
        this.sampleRate = sampleRate
        cursor = 0
        state = State.IDLE
    }

    fun play(scope: CoroutineScope) {
        if (samples.isEmpty()) return
        if (state == State.PLAYING) return
        if (cursor >= samples.size) cursor = 0
        startTrackFrom(cursor, scope)
        state = State.PLAYING
    }

    fun pause() {
        if (state != State.PLAYING) return
        stopInternal()
        state = State.PAUSED
    }

    fun seekTo(sec: Double) {
        val target = (sec.coerceIn(0.0, durationSec) * sampleRate).toInt().coerceIn(0, samples.size)
        val wasPlaying = state == State.PLAYING
        stopInternal()
        cursor = target
        if (wasPlaying && lastScope != null) {
            startTrackFrom(target, lastScope!!)
            state = State.PLAYING
        } else {
            state = if (target >= samples.size && samples.isNotEmpty()) State.FINISHED else State.IDLE
        }
    }

    fun stop() {
        stopInternal()
        cursor = 0
        state = State.IDLE
    }

    /** Level meter for the waveform highlight while playing. */
    fun currentRms(window: Int = 256): Float {
        if (samples.isEmpty()) return 0f
        val end = minOf(samples.size, cursor + window)
        val start = maxOf(0, end - window)
        var sum = 0.0
        for (i in start until end) sum += samples[i].toDouble() * samples[i]
        return Math.sqrt(sum / (end - start).coerceAtLeast(1)).toFloat()
    }

    private fun startTrackFrom(from: Int, scope: CoroutineScope) {
        lastScope = scope
        stopInternal()
        cursor = from
        val remaining = samples.copyOfRange(from, samples.size)
        try {
            val at = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                maxOf(remaining.size * 4, 8192),
                AudioTrack.MODE_STATIC,
            )
            if (remaining.isNotEmpty()) at.write(remaining, 0, remaining.size, AudioTrack.WRITE_BLOCKING)
            at.setNotificationMarkerPosition(remaining.size)
            at.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) = Unit // handled by ticker; kept for API completeness
                override fun onPeriodicNotification(t: AudioTrack?) = Unit
            })
            at.setVolume(volume)
            at.play()
            track = at
        } catch (_: Exception) {
            // No audio sink available (e.g. Robolectric/JVM): keep the clock-driven ticker
            // so state machine and position still advance.
        }
        val startedAt = System.nanoTime()
        ticker = scope.launch(Dispatchers.Default) {
            while (isActive && state == State.PLAYING) {
                val played = ((System.nanoTime() - startedAt) / 1_000_000_000.0 * sampleRate).toInt()
                cursor = minOf(samples.size, from + played)
                if (cursor >= samples.size) {
                    onTailReached(scope)
                    break
                }
                delay(33)
            }
        }
    }

    private fun onTailReached(scope: CoroutineScope) {
        cursor = samples.size
        stopInternal()
        if (loop) {
            cursor = 0
            startTrackFrom(0, scope)
            state = State.PLAYING
        } else {
            state = State.FINISHED
        }
    }

    private fun stopInternal() {
        ticker?.cancel()
        ticker = null
        track?.let {
            try { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        track = null
    }
}
