package com.sambouli.audiowarp

import com.sambouli.audiowarp.dsp.Effect
import com.sambouli.audiowarp.dsp.EffectEngine
import com.sambouli.audiowarp.dsp.Pitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Behavioural tests for all 12 effects using synthetic signals + Goertzel frequency probes.
 * Each test asserts the *defining property* of the effect, not exact samples.
 */
class EffectsTest {

    private val sr = 16_000
    private val engine = EffectEngine(sr)

    private fun sine(freqHz: Double, seconds: Double, amp: Double = 0.7): FloatArray {
        val n = (seconds * sr).toInt()
        return FloatArray(n) { (amp * sin(2.0 * PI * freqHz * it / sr)).toFloat() }
    }

    private fun goertzel(buf: FloatArray, freqHz: Double): Double {
        val w = 2.0 * PI * freqHz / sr
        val coeff = 2.0 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (x in buf) { val s0 = x + coeff * s1 - s2; s2 = s1; s1 = s0 }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    /** Mean power over a set of probe frequencies (energy meter robust to OLA ripple). */
    private fun bandPower(buf: FloatArray, vararg freqs: Double): Double =
        freqs.sumOf { goertzel(buf, it) } / freqs.size

    private fun rms(buf: FloatArray): Double {
        var s = 0.0
        for (x in buf) s += x * x
        return kotlin.math.sqrt(s / buf.size)
    }

    @Test fun everyEffectProducesFiniteNonSilentOutputAndPreservesNoneIdentity() {
        val input = sine(300.0, 0.5)
        for (e in Effect.entries) {
            val out = engine.apply(e, input)
            assertTrue("$e is empty", out.isNotEmpty())
            assertTrue("$e has NaN/Inf", out.all { it.isFinite() })
            assertTrue("$e is silent", rms(out) > 1e-4)
            assertTrue("$e clips", out.all { it >= -1.0001f && it <= 1.0001f })
        }
        val none = engine.apply(Effect.NONE, input)
        assertTrue("NONE must be exact identity", input.contentEquals(none))
    }

    @Test fun robotShiftsSpectrumToSumFrequencies() {
        // ring mod of 300 Hz with 180 Hz carrier => peaks at 120 and 480, NOT at 300
        val out = engine.apply(Effect.ROBOT, sine(300.0, 0.6))
        val atOriginal = goertzel(out, 300.0)
        val atSum = goertzel(out, 480.0)
        val atDiff = goertzel(out, 120.0)
        assertTrue("robot kept original tone: orig=$atOriginal sum=$atSum diff=$atDiff",
            atSum > atOriginal || atDiff > atOriginal)
    }

    @Test fun chipmunkRaisesDominantFrequency() {
        val f0 = 220.0
        val out = engine.apply(Effect.CHIPMUNK, sine(f0, 0.7))
        // +6 st => ~311 Hz; the shifted peak must beat the original pitch by a wide margin
        val shiftedBand = bandPower(out, 290.0, 305.0, 320.0)
        val origBand = bandPower(out, 200.0, 220.0, 240.0)
        assertTrue("chipmunk did not raise pitch: shifted=$shiftedBand orig=$origBand",
            shiftedBand > origBand * 1.5)
    }

    @Test fun monsterLowersDominantFrequency() {
        val f0 = 440.0
        val out = engine.apply(Effect.MONSTER, sine(f0, 0.7))
        // -7 st => ~330 Hz
        val shiftedBand = bandPower(out, 315.0, 330.0, 345.0)
        val origBand = bandPower(out, 420.0, 440.0, 460.0)
        assertTrue("monster did not lower pitch: shifted=$shiftedBand orig=$origBand",
            shiftedBand > origBand * 1.5)
    }

    @Test fun echoAddsLateEnergy() {
        val impulse = FloatArray(sr).also { it[0] = 1f }
        val out = engine.apply(Effect.ECHO, impulse)
        var late = 0.0
        for (i in (sr / 5 until minOf(out.size, sr))) late += out[i] * out[i]
        assertTrue("echo tail missing: $late", late > 1e-4)
    }

    @Test fun megaphoneAttenuatesSubSonicAndUltraSonic() {
        val low = engine.apply(Effect.MEGAPHONE, sine(120.0, 0.6))
        val mid = engine.apply(Effect.MEGAPHONE, sine(1200.0, 0.6))
        val high = engine.apply(Effect.MEGAPHONE, sine(6500.0, 0.6))
        // compare spectral power AT the stimulus frequency, not total rms (normalize equalizes rms)
        val lowAt = goertzel(low, 120.0) / goertzel(engine.apply(Effect.NONE, sine(120.0, 0.6)), 120.0)
        val midAt = goertzel(mid, 1200.0) / goertzel(engine.apply(Effect.NONE, sine(1200.0, 0.6)), 1200.0)
        val highAt = goertzel(high, 6500.0) / goertzel(engine.apply(Effect.NONE, sine(6500.0, 0.6)), 6500.0)
        assertTrue("megaphone passed bass: $lowAt vs mid $midAt", lowAt < midAt * 0.1)
        assertTrue("megaphone passed treble: $highAt vs mid $midAt", highAt < midAt * 0.1)
    }

    @Test fun underwaterKillsTreble() {
        val out = engine.apply(Effect.UNDERWATER, sine(3500.0, 0.6))
        val ref = engine.apply(Effect.NONE, sine(3500.0, 0.6))
        assertTrue("underwater kept treble", goertzel(out, 3500.0) < goertzel(ref, 3500.0) * 0.02)
    }

    @Test fun reverseFlipsTheEnvelope() {
        // loud first half, silent second half -> reversed must be silent first
        val input = FloatArray(sr) { if (it < sr / 2) (0.8 * sin(2.0 * PI * 400.0 * it / sr)).toFloat() else 0f }
        val out = engine.apply(Effect.REVERSE, input)
        var first = 0.0; var last = 0.0
        for (i in 0 until sr / 4) first += out[i] * out[i]
        for (i in (3 * sr / 4) until sr) last += out[i] * out[i]
        assertTrue("reverse failed: first=$first last=$last", first < 1e-6 && last > 1e-3)
    }

    @Test fun radioAddsNoiseFloorButKeepsMidband() {
        val quiet = sine(1000.0, 0.5, amp = 0.02)
        val out = engine.apply(Effect.RADIO, quiet)
        // hiss floor must be audible relative to the tiny tone
        assertTrue("no static floor", rms(out) > rms(quiet) * 0.5)
        // deterministic: same input twice => identical output
        val again = engine.apply(Effect.RADIO, quiet)
        assertTrue("static not deterministic", out.contentEquals(again))
    }

    @Test fun alienModulatesAmplitudeOverTime() {
        val steady = sine(600.0, 1.5, amp = 0.6)
        val out = engine.apply(Effect.ALIEN, steady)
        // window RMS over time must vary a lot (flanger+tremolo), unlike the input
        fun variation(buf: FloatArray): Double {
            val win = sr / 10
            val vals = (0 until buf.size / win).map { w ->
                var s = 0.0
                for (i in 0 until win) s += buf[w * win + i] * buf[w * win + i]
                s / win
            }
            val mean = vals.average()
            return vals.map { abs(it - mean) }.average() / max(mean, 1e-9)
        }
        assertTrue("alien not modulating", variation(out) > variation(steady) * 2)
    }

    @Test fun slowMoLengthensWithoutChangingPitch() {
        val f0 = 300.0
        val input = sine(f0, 1.0)
        val out = engine.apply(Effect.SLOWMO, input)
        assertTrue("slowmo didn't stretch: ${out.size} vs ${input.size}", out.size > input.size * 1.3)
        assertTrue("slowmo changed pitch", goertzel(out, f0) > goertzel(out, f0 * 2) * 2)
    }

    @Test fun ghostKeepsDryHeadAndAddsDarkenedTail() {
        // Clip must be LONGER than the echo delay (4480 samples): with a 0.25 s
        // clip the first tap lands past the end of the dry buffer and the tail
        // window below is structurally empty — the test could never pass.
        val impulse = FloatArray(sr).also { it[0] = 1f }
        val dry = engine.apply(Effect.NONE, impulse)
        val out = engine.apply(Effect.GHOST, impulse)
        assertEquals("ghost preserves length", dry.size, out.size)
        assertNotEquals(dry.toList(), out.toList())
        // dry head present at t=0
        assertTrue("ghost lost dry head", out[0] > 0.5f)
        // the -5 st delayed copy lands ~0.28 s (4480 samples) in; within-buffer tail energy
        var mid = 0.0
        for (i in 4000 until minOf(out.size, sr)) mid += out[i] * out[i]
        assertTrue("ghost tail missing", mid > 1e-3)
    }

    @Test fun reverbHallHasLongerTailThanDry() {
        val impulse = FloatArray(sr / 10).also { it[0] = 1f }
        val wet = engine.apply(Effect.REVERB, impulse)
        var energyAfter200ms = 0.0
        for (i in (sr / 5 until wet.size)) energyAfter200ms += wet[i] * wet[i]
        assertTrue("reverb tail too short", energyAfter200ms > 1e-4)
    }

    @Test fun effectFromIdRoundTripsAndGuardsUnknown() {
        for (e in Effect.entries) assertEquals(e, Effect.fromId(e.id))
        assertEquals(Effect.NONE, Effect.fromId("does-not-exist"))
    }
}
