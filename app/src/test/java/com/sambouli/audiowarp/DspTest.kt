package com.sambouli.audiowarp

import com.sambouli.audiowarp.dsp.Dsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DspTest {

    private fun sine(freqHz: Double, seconds: Double, sr: Int = 16_000, amp: Double = 0.8): FloatArray {
        val n = (seconds * sr).toInt()
        return FloatArray(n) { (amp * sin(2.0 * PI * freqHz * it / sr)).toFloat() }
    }

    /** Goertzel power at a frequency — our spectral ground truth on the JVM. */
    private fun goertzelPower(buf: FloatArray, freqHz: Double, sr: Int): Double {
        val w = 2.0 * PI * freqHz / sr
        val cw = kotlin.math.cos(w)
        val coeff = 2.0 * cw
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0
        for (x in buf) { s0 = x + coeff * s1 - s2; s2 = s1; s1 = s0 }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    @Test fun clampBounds() {
        assertEquals(1f, Dsp.clamp(5f), 0f)
        assertEquals(-1f, Dsp.clamp(-9f), 0f)
        assertEquals(0.3f, Dsp.clamp(0.3f), 0f)
    }

    @Test fun pcmRoundTripIsLosslessWithinQuantization() {
        val original = sine(440.0, 0.25)
        val bytes = Dsp.monoFloatsToPcmBytes(original, 1)
        assertEquals(original.size * 2, bytes.size)
        val back = Dsp.pcmBytesToMonoFloats(bytes, 1)
        assertEquals(original.size, back.size)
        for (i in original.indices) {
            assertTrue("sample $i drift", abs(back[i] - original[i]) < 2.0 / 32767)
        }
    }

    @Test fun stereoDownmixAveragesChannels() {
        // L = +full scale, R = silence -> mono should be ~half
        val pcm = ByteArray(4) // 1 frame stereo
        pcm[0] = 0; pcm[1] = 0x7F // L = 32512
        pcm[2] = 0; pcm[3] = 0     // R = 0
        val mono = Dsp.pcmBytesToMonoFloats(pcm, 2)
        assertEquals(1, mono.size)
        assertEquals(0.497, mono[0].toDouble(), 0.01)
    }

    @Test fun normalizeSetsPeakToTarget() {
        val buf = floatArrayOf(0.05f, -0.1f, 0.02f, 0f)
        Dsp.normalize(buf, 0.95f)
        var peak = 0f
        for (x in buf) peak = maxOf(peak, abs(x))
        assertEquals(0.95f, peak, 1e-4f)
    }

    @Test fun normalizeLeavesSilenceAlone() {
        val buf = FloatArray(10)
        Dsp.normalize(buf)
        assertTrue(buf.all { it == 0f })
    }

    @Test fun readLinearInterpolates() {
        val buf = floatArrayOf(0f, 1f, 0f)
        assertEquals(0.5f, Dsp.readLinear(buf, 0.5), 1e-6f)
        assertEquals(1f, Dsp.readLinear(buf, 1.0), 1e-6f)
        assertEquals(0f, Dsp.readLinear(buf, -1.0), 1e-6f)
    }

    @Test fun lowPassKillsHighFrequency() {
        val sr = 16_000
        val high = sine(5000.0, 0.3, sr)
        val refHigh = goertzelPower(high, 5000.0, sr)
        Dsp.lowPass(high, 600f, sr)
        val highPow = goertzelPower(high, 5000.0, sr)
        assertTrue("5kHz survived low-pass: $highPow vs ref $refHigh", highPow < refHigh * 0.01)
        val low = sine(200.0, 0.3, sr)
        val refLow = goertzelPower(low, 200.0, sr)
        Dsp.lowPass(low, 600f, sr)
        assertTrue("200Hz was destroyed", goertzelPower(low, 200.0, sr) > refLow * 0.5)
    }

    @Test fun highPassKillsLowFrequency() {
        val sr = 16_000
        val high = sine(4000.0, 0.3, sr)
        val refHigh = goertzelPower(high, 4000.0, sr)
        Dsp.highPass(high, 500f, sr)
        assertTrue(goertzelPower(high, 4000.0, sr) > refHigh * 0.9)
        val low = sine(80.0, 0.5, sr)
        val refLow = goertzelPower(low, 80.0, sr)
        Dsp.highPass(low, 500f, sr)
        assertTrue(goertzelPower(low, 80.0, sr) < refLow * 0.05)
    }

    @Test fun softClipSaturatesPeaksButKeepsSign() {
        val buf = floatArrayOf(0.9f, -0.9f, 0.1f, -0.1f)
        Dsp.softClip(buf, 4f)
        assertTrue(buf[0] in 0.95f..1f)
        assertTrue(buf[1] in -1f..-0.95f)
        // small signals stay roughly linear
        assertTrue(abs(buf[2] - 0.1f * 4f / (1f + 0.4f)) < 0.25f)
    }

    @Test fun bitCrushReducesLevels() {
        val buf = FloatArray(256) { sin(2 * PI * it / 64.0).toFloat() }
        Dsp.bitCrush(buf, 2) // 2 bits => levels {-1,-2/3,-1/3,0,1/3,2/3,1}
        val distinct = buf.map { (it * 1000).toInt() }.distinct().size
        assertTrue("expected <=7 quantized levels, got $distinct", distinct <= 7)
        Dsp.bitCrush(FloatArray(64) { sin(2 * PI * it / 64.0).toFloat() }.also { }, 1) // sanity no crash
    }

    @Test fun feedbackEchoAddsPeriodicRepeats() {
        val sr = 16_000
        val impulse = FloatArray(sr).also { it[0] = 1f }
        val lag = sr / 4
        val echoed = Dsp.feedbackEcho(impulse, lag, feedback = 0.5f, mixWet = 1f)
        assertEquals("output must stay same-length", sr, echoed.size)
        // taps at 0, lag, 2*lag... with decaying amplitude
        assertEquals(1f, echoed[0], 0.05f)
        assertTrue("echo tap missing at lag", echoed[lag] > 0.3f)
        assertTrue("echo tap missing at 2*lag", echoed[2 * lag] > 0.1f)
        assertTrue("echo tail too long", echoed[lag * 3] < 0.15f) // decayed: 0.5^3 = 0.125
    }

    @Test fun reverbExtendsEnergyTail() {
        val sr = 16_000
        val impulse = FloatArray(sr / 10).also { it[0] = 1f }
        val wet = Dsp.reverb(impulse, sr, roomSize = 0.9f)
        // energy well after the impulse must be non-trivial (the tail)
        var late = 0.0
        for (i in (sr / 20 until wet.size)) late += wet[i] * wet[i]
        assertTrue("no reverb tail: $late", late > 1e-3)
    }

    @Test fun mixBlendsDryAndWet() {
        val dry = floatArrayOf(1f, 1f)
        val wet = floatArrayOf(-1f, -1f)
        assertEquals(-1f, Dsp.mix(dry, wet, fadeSamples = 0)[0], 1e-6f) // full wet
        assertEquals(0f, Dsp.blend(dry, wet, 0.5f)[0], 1e-6f)           // half blend
    }
}
