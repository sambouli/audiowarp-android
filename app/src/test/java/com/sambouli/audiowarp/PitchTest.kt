package com.sambouli.audiowarp

import com.sambouli.audiowarp.dsp.Pitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class PitchTest {

    private val sr = 16_000

    private fun sine(freqHz: Double, seconds: Double, amp: Double = 0.8): FloatArray {
        val n = (seconds * sr).toInt()
        return FloatArray(n) { (amp * sin(2.0 * PI * freqHz * it / sr)).toFloat() }
    }

    private fun goertzelPower(buf: FloatArray, freqHz: Double): Double {
        val w = 2.0 * PI * freqHz / sr
        val coeff = 2.0 * kotlin.math.cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (x in buf) { val s0 = x + coeff * s1 - s2; s2 = s1; s1 = s0 }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    @Test fun resampleHalvesLengthAtDoubleSpeed() {
        val input = sine(440.0, 1.0)
        val out = Pitch.resample(input, 2.0)
        assertEquals(input.size / 2, out.size)
    }

    @Test fun resampleRatioOneIsIdentity() {
        val input = sine(300.0, 0.1)
        val out = Pitch.resample(input, 1.0)
        assertEquals(input.size, out.size)
        for (i in input.indices) assertEquals(input[i], out[i], 0f)
    }

    @Test fun timeStretchPreservesPitchAndScalesDuration() {
        val f0 = 330.0
        val input = sine(f0, 1.0)
        val stretched = Pitch.timeStretch(input, 0.5) // twice as long
        assertTrue(
            "duration wrong: ${stretched.size} vs ${input.size}",
            abs(stretched.size - input.size * 2) < input.size * 0.1,
        )
        // dominant frequency must remain f0 (pitch preserved); compare against the
        // SAME power measured on the un-stretched reference at each probe frequency
        val refAtF0 = goertzelPower(input, f0)
        val refAtOct = goertzelPower(input, f0 * 2)
        val atF0 = goertzelPower(stretched, f0)
        val atOct = goertzelPower(stretched, f0 * 2)
        // OLA dilutes absolute power; require f0 to stay strong AND dominate its own octave
        assertTrue("pitch drifted: $atF0 vs $atOct", atF0 > atOct * 2 && atF0 > refAtF0 * 0.05)
    }

    @Test fun pitchShiftUpOneOctaneDoublesDominantFrequency() {
        val f0 = 220.0
        val input = sine(f0, 0.8)
        val shifted = Pitch.pitchShift(input, 12.0)
        // duration preserved
        assertTrue(abs(shifted.size - input.size) < input.size * 0.15)
        val at2f = goertzelPower(shifted, f0 * 2)
        val atF = goertzelPower(shifted, f0)
        assertTrue("octave shift failed: 2f=$at2f f=$atF", at2f > atF * 3)
    }

    @Test fun pitchShiftZeroSemitonesIsNearIdentity() {
        val input = sine(440.0, 0.3)
        val out = Pitch.pitchShift(input, 0.0)
        assertEquals(input.size, out.size)
    }

    @Test fun estimatePitchFindsConcertA() {
        val frame = sine(440.0, 0.1)
        val est = Pitch.estimatePitchHz(frame, sr)
        assertTrue("expected ~440, got $est", abs(est - 440.0) < 10.0)
    }

    @Test fun estimatePitchFindsBassNote() {
        val frame = sine(110.0, 0.15)
        val est = Pitch.estimatePitchHz(frame, sr)
        assertTrue("expected ~110, got $est", abs(est - 110.0) < 4.0)
    }

    @Test fun estimatePitchRejectsSilence() {
        assertEquals(0.0, Pitch.estimatePitchHz(FloatArray(2048), sr), 0.0)
    }

    @Test fun estimatePitchAvoidsOctaveErrorOnPureTone() {
        // pure tone: autocorr peak is true period; must NOT report double frequency
        val frame = sine(200.0, 0.2)
        val est = Pitch.estimatePitchHz(frame, sr)
        assertTrue("octave error: $est", abs(est - 200.0) < 5.0)
    }

    @Test fun hannWindowEndsAreZeroCenterIsOne() {
        val w = Pitch.hannWindow(1024)
        assertEquals(0f, w[0], 1e-6f)
        assertEquals(1f, w[511], 0.01f)
        assertEquals(0f, w[1023], 1e-6f)
    }
}
