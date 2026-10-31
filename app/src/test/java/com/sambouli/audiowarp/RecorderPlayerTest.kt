package com.sambouli.audiowarp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sambouli.audiowarp.audio.Recorder
import com.sambouli.audiowarp.dsp.Effect
import com.sambouli.audiowarp.dsp.WavIO
import com.sambouli.audiowarp.player.PlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecorderPlayerTest {

    @Test fun recorderWithoutPermissionFailsCleanly() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val rec = Recorder(ctx)
        assertTrue(!rec.hasPermission)
        assertTrue(!rec.start(CoroutineScope(Dispatchers.Default)))
        assertTrue(!rec.isRecording)
    }

    @Test fun stopWithoutStartYieldsSilentValidWav() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val rec = Recorder(ctx)
        // Robolectric's AudioRecord reports uninitialized state, so this exercises the
        // safe-stop path: no active capture -> valid silent WAV, no crash.
        val wav = rec.stop()
        val (samples, sr) = WavIO.wavToFloats(wav)
        assertEquals(16_000, sr)
        assertEquals(1600, samples.size) // 0.1 s of silence
        assertTrue(samples.all { it == 0f })
    }

    @Test fun playerLoadAndStateTransitions() {
        val p = PlayerEngine()
        assertTrue(p.isEmpty)
        val samples = FloatArray(16_000) { (0.3 * kotlin.math.sin(it * 0.05)).toFloat() }
        p.load(samples)
        assertTrue(!p.isEmpty)
        assertEquals(1.0, p.durationSec, 0.001)
        assertEquals(PlayerEngine.State.IDLE, p.state)

        p.seekTo(0.5)
        assertEquals(0.5, p.positionSec, 0.001)
        p.seekTo(-3.0)
        assertEquals(0.0, p.positionSec, 1e-9)
        p.seekTo(99.0)
        assertEquals(1.0, p.positionSec, 0.001)
        assertEquals(PlayerEngine.State.FINISHED, p.state)

        p.stop()
        assertEquals(0.0, p.positionSec, 1e-9)
        assertEquals(PlayerEngine.State.IDLE, p.state)
    }

    @Test fun playerLoopFlagToggles() {
        val p = PlayerEngine()
        assertEquals(false, p.loop)
        p.loop = true
        assertEquals(true, p.loop)
    }

    @Test fun playerRmsOfSilenceIsZero() {
        val p = PlayerEngine()
        p.load(FloatArray(1000))
        assertEquals(0f, p.currentRms(), 1e-6f)
    }
}
