package com.sambouli.audiowarp

import com.sambouli.audiowarp.player.PlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for what PlayerEngine hands android.media.AudioTrack on play.
 *
 * Robolectric shadows AudioTrack, so a unit test cannot prove a device accepts the track. What it
 * can pin are the arguments the AudioTrack contract bounds — the notification marker inside the
 * written range and a notification period the framework will not reject — plus the rule that no
 * Throwable may leave a control method, since the app calls those straight from a Compose click
 * handler and an escaped Throwable there is a dead process.
 */
class StaticTrackContractTest {

    private fun tone(frames: Int): FloatArray =
        FloatArray(frames) { (0.3 * kotlin.math.sin(it * 0.05)).toFloat() }

    /** setNotificationMarkerPosition addresses a frame, so it must stay inside what was written. */
    @Test
    fun markerPositionStaysWithinWrittenFrames() {
        for (written in listOf(1, 2, 512, 16_000, 480_000)) {
            val marker = PlayerEngine.markerFor(written)
            assertTrue("marker $marker must address a written frame (last is ${written - 1})", marker in 0..(written - 1))
        }
    }

    /** A track with nothing in it must not be handed a marker pointing before its start. */
    @Test
    fun markerOfEmptyBufferIsZero() {
        assertEquals(0, PlayerEngine.markerFor(0))
    }

    /**
     * The notification period is a count of frames and the framework rejects a non-positive one,
     * so it has to stay positive however small the clip.
     */
    @Test
    fun notificationPeriodAlwaysPositive() {
        for (capacity in listOf(0, 1, 100, 512, 480_000)) {
            val period = PlayerEngine.notificationFramesFor(capacity)
            assertTrue("period $period must be positive for capacity $capacity", period >= 1)
            assertTrue("period $period must not exceed capacity $capacity", period <= maxOf(capacity, 1))
        }
    }

    /**
     * Regression for "crashes every time I press play": play() runs on the UI thread, so anything
     * that leaves it — an Error included, which `catch (Exception)` does not catch — ends the
     * process immediately. Same rule for every control a gesture can reach.
     */
    @Test
    fun playerControlMethodsNeverEscapeAThrowable() {
        val p = PlayerEngine()
        p.load(tone(16_000))
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val escape = mutableListOf<String>()

        fun guard(label: String, block: () -> Unit) = try {
            block()
        } catch (t: Throwable) {
            escape += "$label -> ${t.javaClass.name}: ${t.message}"
        }

        guard("play") { p.play(scope) }
        guard("pause") { p.pause() }
        guard("seek mid") { p.seekTo(0.5) }
        guard("resume") { p.play(scope) }
        guard("seek past end") { p.seekTo(99.0) }
        guard("play at end") { p.play(scope) }
        guard("stop") { p.stop() }
        guard("play with nothing loaded") { PlayerEngine().play(scope) }
        guard("seek with nothing loaded") { PlayerEngine().seekTo(1.0) }
        guard("loop tail") { p.loop = true; p.seekTo(0.0); p.play(scope) }

        scope.coroutineContext[Job]!!.cancel()
        assertEquals("control methods leaked throwables to the UI thread: $escape", emptyList<String>(), escape)
    }

    /**
     * When the platform will not hand out an audio sink the engine must say so on itself. Without
     * that signal the caller cannot tell "playing" from "silent", which is how a broken track reads
     * as a play button that does nothing.
     */
    @Test
    fun engineReportsWhenTheAudioSinkIsUnavailable() {
        val p = PlayerEngine()
        // Robolectric never gives out a real AudioTrack, so the sink is by definition unavailable.
        p.load(tone(16_000))
        val scope = CoroutineScope(Dispatchers.Default + Job())
        p.play(scope)
        assertTrue("engine must flag a missing sink rather than claim it is playing", p.audioUnavailable)
        p.stop()
        scope.coroutineContext[Job]!!.cancel()
    }

    /** A finished clip must land in FINISHED, so the UI can rewind rather than freeze at 0:00. */
    @Test
    fun playingToTheEndReachesFinishedState() {
        val p = PlayerEngine()
        p.load(tone(1_600)) // 0.1 s
        val scope = CoroutineScope(Dispatchers.Default + Job())
        p.play(scope)
        assertEquals(PlayerEngine.State.PLAYING, p.state)
        p.seekTo(10.0) // past the end
        assertEquals(PlayerEngine.State.FINISHED, p.state)
        scope.coroutineContext[Job]!!.cancel()
    }

    /** Reloading after a play must clear the previous run, including any sink fault. */
    @Test
    fun reloadClearsThePreviousFaultState() {
        val p = PlayerEngine()
        p.load(tone(16_000))
        val scope = CoroutineScope(Dispatchers.Default + Job())
        p.play(scope)
        p.load(tone(8_000))
        assertEquals("a fresh clip must not inherit the last one's duration", 0.5, p.durationSec, 0.001)
        assertEquals(PlayerEngine.State.IDLE, p.state)
        scope.coroutineContext[Job]!!.cancel()
    }
}
