package com.sambouli.audiowarp.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The 12 built-in voice warps. */
enum class Effect(val id: String, val label: String) {
    NONE("none", "Original"),
    REVERB("reverb", "Reverb Hall"),
    ROBOT("robot", "Robot / Ring Mod"),
    GHOST("ghost", "Ghost Delay"),
    CHIPMUNK("chipmunk", "Chipmunk"),
    MONSTER("monster", "Deep Monster"),
    ECHO("echo", "Echo Feedback"),
    MEGAPHONE("megaphone", "Megaphone"),
    UNDERWATER("underwater", "Underwater"),
    REVERSE("reverse", "Reverse"),
    RADIO("radio", "Radio Static"),
    ALIEN("alien", "Alien Flanger"),
    SLOWMO("slowmo", "Slow-Mo");

    companion object {
        fun fromId(id: String): Effect = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * Offline (whole-buffer) effect engine. Each effect is deterministic and pure so unit tests
 * can feed synthetic tones and assert spectral/structural properties without a device.
 */
class EffectEngine(private val sampleRate: Int = WavIO.SAMPLE_RATE) {

    fun apply(effect: Effect, input: FloatArray): FloatArray {
        if (effect == Effect.NONE) return input.copyOf() // identity: no normalize, no touch
        val out = when (effect) {
            Effect.REVERB -> reverbHall(input)
            Effect.ROBOT -> ringMod(input, 180f)
            Effect.GHOST -> ghostDelay(input)
            Effect.CHIPMUNK -> pitchUp(input)
            Effect.MONSTER -> pitchDown(input)
            Effect.ECHO -> echo(input)
            Effect.MEGAPHONE -> megaphone(input)
            Effect.UNDERWATER -> underwater(input)
            Effect.REVERSE -> reverse(input)
            Effect.RADIO -> radioStatic(input)
            Effect.ALIEN -> alienFlanger(input)
            Effect.SLOWMO -> slowMo(input)
            Effect.NONE -> input.copyOf()
        }
        Dsp.normalize(out)
        return out
    }

    // 1. Reverb — hall space via Schroeder comb+allpass network.
    private fun reverbHall(input: FloatArray): FloatArray =
        Dsp.reverb(input, sampleRate, roomSize = 0.85f)

    // 2. Robot — ring modulation with a clean sine carrier (+ slight drive for edge).
    internal fun ringMod(input: FloatArray, freqHz: Float): FloatArray {
        val out = FloatArray(input.size)
        val inc = 2.0 * PI * freqHz / sampleRate
        for (i in input.indices) {
            out[i] = Dsp.clamp(input[i] * cos(inc * i).toFloat())
        }
        Dsp.softClip(out, 1.6f)
        return out
    }

    // 3. Ghost — pitch-shifted (-5 st) feedback delay with darkened repeats over the dry head.
    internal fun ghostDelay(input: FloatArray): FloatArray {
        val delaySamples = (sampleRate * 0.28f).toInt()
        // Wet path = the input DELAYED by delaySamples (classic echo), then
        // pitch-shifted -5 st and darkened. Delay FIRST, on a buffer long enough
        // to hold the shifted copy (input + 2*delay): delaying after shifting
        // pushes the first tap past the dry-length truncation in Dsp.mix.
        val wetLen = input.size + 2 * delaySamples
        val delayed = FloatArray(wetLen)
        System.arraycopy(input, 0, delayed, delaySamples, input.size)
        val shifted = Pitch.pitchShift(delayed, -5.0)
        // pitchShift compresses time by `factor` before OLA-restoring length, so the
        // head of the delayed impulse sits at ~delaySamples / factor (~7649 for -5 st).
        // Locate it by scanning around that expectation, then align the echo head to
        // index `delaySamples` in a wet buffer that is as long as `shifted` (Dsp.mix
        // trims to dry length afterwards). The historic "wet is all zeros" bug was NOT
        // here: zeroPhaseLowPass used to leave the buffer time-reversed, mirroring the
        // echo past the dry cut (fixed in Dsp.zeroPhaseLowPass).
        val expectedHead = (delaySamples / Math.pow(2.0, -5.0 / 12.0)).toInt()
        var head = -1
        val scanFrom = max(0, expectedHead - 2048)
        val scanTo = min(shifted.size - 1, expectedHead + 2048)
        for (i in scanFrom..scanTo) { if (abs(shifted[i]) > 1e-6f) { head = i; break } }
        if (head < 0) head = expectedHead.coerceIn(0, max(0, shifted.size - 1))
        // Copy the WHOLE shifted buffer into a wet that is long enough to hold it,
        // shifted left by (head - delaySamples): the echo head lands exactly at
        // index delaySamples. wet stays full length through the LPF (a dry-length
        // cut BEFORE the filter truncates the tail — that was the all-zero bug);
        // Dsp.mix trims to dry length afterwards.
        val shift = (head - delaySamples).coerceIn(0, max(0, shifted.size - 1))
        val wet = FloatArray(shifted.size)
        val n = minOf(shifted.size - shift, wet.size)
        for (i in 0 until n) wet[i] = shifted[shift + i]
        Dsp.zeroPhaseLowPass(wet, 2400f, sampleRate)
        return Dsp.mix(input, wet, fadeSamples = sampleRate / 10)
    }

    // 4. Chipmunk — +6 semitones pitch shift (duration preserved).
    internal fun pitchUp(input: FloatArray): FloatArray = Pitch.pitchShift(input, 6.0)

    // 5. Monster — -7 semitones plus a touch of saturation for body.
    internal fun pitchDown(input: FloatArray): FloatArray {
        val out = Pitch.pitchShift(input, -7.0)
        Dsp.softClip(out, 1.25f)
        return out
    }

    // 6. Echo — dotted eighth feedback delay.
    internal fun echo(input: FloatArray): FloatArray {
        val delaySamples = (sampleRate * 0.375f).toInt()
        return Dsp.feedbackEcho(input, delaySamples, feedback = 0.42f, mixWet = 0.55f)
    }

    // 7. Megaphone — bandpass 300..3000 Hz + heavy soft clip + crunch.
    internal fun megaphone(input: FloatArray): FloatArray {
        val out = input.copyOf()
        Dsp.highPass(out, 300f, sampleRate)
        Dsp.lowPass(out, 3000f, sampleRate)
        Dsp.softClip(out, 3.5f)
        return out
    }

    // 8. Underwater — strong zero-phase low-pass ~550 Hz + gentle wobble via slow AM.
    internal fun underwater(input: FloatArray): FloatArray {
        val out = input.copyOf()
        Dsp.zeroPhaseLowPass(out, 550f, sampleRate)
        val lfoInc = 2.0 * PI * 0.6 / sampleRate
        for (i in out.indices) {
            val am = 0.85f + 0.15f * sin(lfoInc * i).toFloat()
            out[i] = out[i] * am
        }
        return out
    }

    // 9. Reverse — time reversal.
    internal fun reverse(input: FloatArray): FloatArray = input.reversedArray()

    // 10. Radio static — bandpass 400..2400, heavy drive, bitcrush, deterministic hiss floor.
    internal fun radioStatic(input: FloatArray): FloatArray {
        val out = input.copyOf()
        Dsp.highPass(out, 400f, sampleRate)
        Dsp.lowPass(out, 2400f, sampleRate)
        Dsp.softClip(out, 6.0f)
        Dsp.bitCrush(out, 5)
        var state = 0x5DEECE66L and 0xFFFFFFFFFL
        for (i in out.indices) {
            state = (state * 6364136223846793005L + 1442695040888963407L) and 0xFFFFFFFFFL
            val r = ((state ushr 33).toInt() / 2147483647.0f) - 1f
            out[i] = Dsp.clamp(out[i] + 0.04f * r)
        }
        return out
    }

    // 11. Alien — flanger (swept comb) crossed with a fast tremolo.
    internal fun alienFlanger(input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        val maxDelay = (sampleRate * 0.01f).toInt() // 10 ms
        val sweepHz = 0.5
        val tremHz = 11.0
        for (i in input.indices) {
            val d = maxDelay * (0.5 + 0.5 * sin(2.0 * PI * sweepHz * i / sampleRate))
            val read = i - d
            val wet = if (read >= 0) Dsp.readLinear(input, read.toDouble()) else 0f
            val trem = 0.6f + 0.4f * sin(2.0 * PI * tremHz * i / sampleRate).toFloat()
            out[i] = Dsp.clamp((input[i] * 0.55f + wet * 0.75f) * trem)
        }
        return out
    }

    // 12. Slow-mo — 0.6x speed preserving pitch (OLA stretch), slightly softened highs.
    internal fun slowMo(input: FloatArray): FloatArray {
        val out = Pitch.timeStretch(input, 0.6)
        Dsp.lowPass(out, 6500f, sampleRate)
        return out
    }
}
