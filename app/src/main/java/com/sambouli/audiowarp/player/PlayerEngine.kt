package com.sambouli.audiowarp.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.sambouli.audiowarp.dsp.WavIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * PCM playback engine over [AudioTrack], with play/pause/seek/loop.
 *
 * Position comes from [AudioTrack.getPlaybackHeadPosition] where the track exposes it and from a
 * clock-driven ticker otherwise, so the state machine stays testable without a real audio sink.
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

    /** True when the current [track] is refilled as it drains, rather than preloaded whole. */
    private var streaming = false
    /** Frames the streaming track can hold, so the refill knows when it has caught up. */
    private var streamFrames = 0
    /** Sample index the current track started from, so refills read the right window. */
    private var streamFrom = 0
    /** Frames handed to the track so far, and the last one it actually accepted. */
    private var writtenFrames = 0

    /** False when the platform could not open an audio sink; surfaced so the UI can say so. */
    @Volatile var audioUnavailable: Boolean = false
        private set

    val durationSec: Double get() = if (sampleRate > 0) samples.size.toDouble() / sampleRate else 0.0
    val positionSec: Double get() = cursor.toDouble() / sampleRate
    val isEmpty: Boolean get() = samples.isEmpty()

    fun load(samples: FloatArray, sampleRate: Int = WavIO.SAMPLE_RATE) {
        stopInternal()
        audioUnavailable = false
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

    /**
     * Move the play head. A seek while playing keeps playing from the new spot; a seek that lands
     * on the end of the clip ends playback instead of restarting a zero-length run.
     */
    fun seekTo(sec: Double) {
        val target = (sec.coerceIn(0.0, durationSec) * sampleRate).toInt().coerceIn(0, samples.size)
        val wasPlaying = state == State.PLAYING
        stopInternal()
        cursor = target
        val atEnd = target >= samples.size && samples.isNotEmpty()
        state = when {
            atEnd -> State.FINISHED
            wasPlaying && lastScope != null -> {
                startTrackFrom(target, lastScope!!)
                State.PLAYING
            }
            else -> State.IDLE
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
        streamFrom = from
        track = newTrack(remaining)
        if (track == null) {
            // No audio sink at all: keep the clock running so the UI advances instead of
            // wedging on a spinner. The user hears nothing, and audioUnavailable says so.
            safeLog { Log.i(TAG, "no audio sink; running position clock only") }
        }
        ticker = scope.launch(Dispatchers.Default) { tick(from) }
    }

    /**
     * Advance the playback cursor every [TICK_MS].
     *
     * Position is taken from the track itself where it can report one — [AudioTrack] exposes both
     * a low-latency timestamp and a plain frame counter, and either is closer to what the listener
     * actually hears than our own clock. Only when neither yields a reading does the clock stand
     * in, which is also the case when there is no track at all.
     */
    private suspend fun CoroutineScope.tick(from: Int) {
        val startedAt = System.nanoTime()
        while (isActive && state == State.PLAYING) {
            val t = track
            if (t != null) streamInto(t)
            cursor = minOf(samples.size, from + playedFrames(t, startedAt))
            if (cursor >= samples.size) {
                onTailReached(this)
                break
            }
            delay(TICK_MS)
        }
    }

    /** Frames consumed since [startedAt], preferring the track's own reading of itself. */
    private fun playedFrames(track: AudioTrack?, startedAt: Long): Int {
        if (track != null) {
            val head = frameReading { track.getPlaybackHeadPosition() }
            if (head >= 0) return head
        }
        val elapsedNanos = System.nanoTime() - startedAt
        return (elapsedNanos / 1_000_000_000.0 * sampleRate).toInt()
    }

    /**
     * Push the next block of audio into a streaming track as its buffer drains. A static track
     * already holds everything, so it is left alone.
     */
    private fun streamInto(at: AudioTrack) {
        if (!streaming) return
        val room = streamFrames - writtenFrames
        val left = samples.size - (streamFrom + writtenFrames)
        if (room <= 0 || left <= 0) return
        val chunk = minOf(left, room)
        ignoring {
            val accepted = at.write(samples, streamFrom + writtenFrames, chunk, AudioTrack.WRITE_NON_BLOCKING)
            if (accepted > 0) writtenFrames += accepted
        }
    }

    /**
     * Build and start the track carrying [data], or null when the platform refuses to hand out
     * an audio sink.
     *
     * The whole body is guarded on Throwable, not Exception: the platform reports an unusable
     * track with IllegalArgumentException from the constructor and IllegalStateException from
     * play(), but a track built against a signature the device ROM does not export fails with
     * NoSuchMethodError, which is an Error. Guarding only Exception let that escape to the
     * Compose click handler and end the process on the first play press.
     */
    private fun newTrack(data: FloatArray): AudioTrack? = try {
        buildTrack(data)?.also { it.play() }
    } catch (t: Throwable) {
        audioUnavailable = true
        safeLog { Log.w(TAG, "could not open audio sink; playback will be silent", t) }
        null
    }

    /**
     * A static track holds the whole clip in one native buffer sized up front, so the clip has to
     * fit it; a longer clip plays from a streaming track that the engine refills as it goes.
     */
    private fun buildTrack(data: FloatArray): AudioTrack? {
        val (format, attributes) = trackFormat()

        val static = data.isNotEmpty()
        val mode = if (static) AudioTrack.MODE_STATIC else AudioTrack.MODE_STREAM
        val requested = if (static) data.size else minOf(data.size, MIN_STREAM_FRAMES)
        // bufferSizeInBytes is genuinely bytes: the framework converts it to frames, and for a
        // static track the capacity it then reports is exactly that conversion. Multiplying by
        // the frame size here is what the parameter asks for, not a workaround.
        val at = AudioTrack(
            attributes, format, requested * BYTES_PER_FRAME, mode,
            notificationFrames(requested),
        )
        if (at.state != AudioTrack.STATE_INITIALIZED) {
            at.release()
            return null
        }
        // Read the capacity back rather than trusting `requested`: the framework may raise a
        // request below the output sink's minimum, so what it hands over is not what was asked
        // for. A static track's frame count never changes after construction, so this is final.
        val capacity = at.getBufferCapacityInFrames().coerceAtLeast(1)
        if (static && data.size > capacity) {
            at.release()
            return buildStreamingTrack(attributes, format)
        }
        if (static) {
            streaming = false
            streamFrames = capacity
            writtenFrames = minOf(data.size, capacity)
            at.write(data, 0, writtenFrames, AudioTrack.WRITE_BLOCKING)
        }
        return tune(at)
    }

    /**
     * A streaming track with no data in it yet. [streamInto] refills it from the sample array as
     * playback drains it, which is what lets a clip longer than the static buffer still play.
     */
    private fun buildStreamingTrack(
        attributes: AudioAttributes,
        format: AudioFormat,
    ): AudioTrack? {
        val at = AudioTrack(
            attributes, format, MIN_STREAM_FRAMES * BYTES_PER_FRAME,
            AudioTrack.MODE_STREAM, notificationFrames(MIN_STREAM_FRAMES),
        )
        if (at.state != AudioTrack.STATE_INITIALIZED) {
            at.release()
            return null
        }
        streaming = true
        streamFrames = MIN_STREAM_FRAMES
        writtenFrames = 0
        return tune(at)
    }

    private fun trackFormat(): Pair<AudioFormat, AudioAttributes> =
        AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build() to
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

    /** Volume, notification marker and (API 33+) the position callback. */
    private fun tune(at: AudioTrack): AudioTrack {
        at.setNotificationMarkerPosition(markerFor(writtenFrames))
        at.setVolume(volume)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            at.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = Unit // ticker owns the position
                override fun onPeriodicNotification(t: AudioTrack) = Unit
            })
        }
        return at
    }

    /** The audio stack reports a dead track by throwing; a reading we cannot take is no reading. */
    private fun frameReading(read: () -> Int): Int = try { read() } catch (_: Throwable) { -1 }

    /**
     * Write a log line without letting the logger become the fault.
     *
     * Every call site here is a fault handler, and android.util.Log is not inert: a host that has
     * not mocked it (Robolectric) throws from it outright. A throw from inside a catch block
     * escapes the catch, so an unguarded log turned a recoverable "no audio sink" into the very
     * process death the surrounding guard exists to prevent.
     */
    private inline fun safeLog(write: () -> Unit) {
        try { write() } catch (_: Throwable) {}
    }

    /** Ignore a result we cannot act on: a failed write or release leaves the play state alone. */
    private fun ignoring(action: () -> Unit) {
        try { action() } catch (_: Throwable) {}
    }

    /**
     * How often the track reports progress. The ticker derives position from the clock, so a wide
     * period keeps callback traffic down without costing accuracy.
     */
    private fun notificationFrames(capacity: Int): Int = notificationFramesFor(capacity)

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
            try { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop() } catch (_: Throwable) {}
            try { it.release() } catch (_: Throwable) {}
        }
        track = null
        streaming = false
        streamFrames = 0
        streamFrom = 0
        writtenFrames = 0
    }

    companion object {
        private const val TAG = "PlayerEngine"
        const val BYTES_PER_FRAME = 4 // ENCODING_PCM_FLOAT, mono
        const val TICK_MS = 33L
        const val MIN_NOTIFICATION_FRAMES = 512
        const val NOTIFICATION_DIVISOR = 8
        /** ~0.25 s of 16 kHz float audio: a full refill block for the streaming path. */
        const val MIN_STREAM_FRAMES = 4_096

        /**
         * Notification marker for a track holding [writtenFrames]: the last frame it really has.
         *
         * Two documented bounds shape this. The marker addresses frames the track actually holds,
         * so one past the end is out of range; and a marker of exactly zero *disables* the marker,
         * which is why an empty or single-frame track yields 0 (nothing to notify about) instead of
         * an artificial non-zero position.
         */
        fun markerFor(writtenFrames: Int): Int = maxOf(0, writtenFrames - 1)

        /** Notification period for a [capacity]-frame track; the framework wants a positive one. */
        fun notificationFramesFor(capacity: Int): Int =
            minOf(maxOf(capacity / NOTIFICATION_DIVISOR, MIN_NOTIFICATION_FRAMES), maxOf(capacity, 1))
    }
}
