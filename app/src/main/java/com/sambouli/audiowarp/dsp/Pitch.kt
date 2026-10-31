package com.sambouli.audiowarp.dsp

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Time-domain pitch shifter (windowed overlap-add) and linear resampler.
 *
 * Pitch shift preserves duration; speed change preserves pitch via OLA stretch.
 * Both are pure functions on FloatArray so unit tests can probe them with synthetic tones.
 */
object Pitch {

    /**
     * Resample with linear interpolation. ratio > 1 reads faster: output is SHORTER and the
     * pitch goes UP (like speeding up a tape). ratio < 1 slows down and lowers pitch.
     */
    fun resample(input: FloatArray, ratio: Double): FloatArray {
        require(ratio > 0.0)
        if (input.isEmpty() || ratio == 1.0) return input.copyOf()
        val outLen = max(1, ceil(input.size / ratio).toInt())
        val out = FloatArray(outLen)
        for (o in 0 until outLen) {
            out[o] = Dsp.readLinear(input, o * ratio)
        }
        return out
    }

    /**
     * Pitch shift by [semitones] keeping duration: resample so the signal gets compressed in
     * time for a positive shift (pitch up, like speeding a tape) then OLA-stretch back to the
     * original length (restores length, keeps pitch).
     */
    fun pitchShift(input: FloatArray, semitones: Double): FloatArray {
        if (input.isEmpty() || semitones == 0.0) return input.copyOf()
        val factor = Math.pow(2.0, semitones / 12.0)
        // Step 1 — tape trick: read the input at `factor` speed. freq scales by factor,
        // length scales by 1/factor. Linear interpolation, no windowing: transients keep
        // their energy exactly (an impulse stays an impulse).
        val tapeLen = max(1, ceil(input.size / factor).toInt())
        val tape = FloatArray(tapeLen) { Dsp.readLinear(input, it * factor) }
        // Step 2 — restore the original duration with pitch-preserving OLA.
        return timeStretch(tape, tapeLen.toDouble() / input.size)
    }

    /**
     * Duration scale preserving pitch: output length ~= input / rate.
     * rate > 1 => faster/shorter. Hann-windowed OLA at 75% grain overlap.
     *
     * Pitch preservation requires the WRITE hop to stay fixed while the READ hop scales:
     * each grain is played at natural speed (no resampling), and stretching comes from
     * re-reading overlapping regions (rate < 1) or skipping ahead (rate > 1). Scaling the
     * write hop instead (the naive design) simply resamples and shifts the pitch.
     */
    fun timeStretch(input: FloatArray, rate: Double): FloatArray {
        require(rate > 0.0)
        if (input.isEmpty() || rate == 1.0) return input.copyOf()
        val grainLen = min(1024, max(32, evenDown(input.size)))
        val hopOut = grainLen / 4
        val readStep = hopOut / rate
        val nominal = max(1, Math.round(input.size / rate).toInt())
        val outLen = nominal + grainLen
        val out = FloatArray(outLen)
        val norm = FloatArray(outLen)
        val win = hannWindow(grainLen)
        var outPos = 0
        var inPos = 0.0
        while (inPos + grainLen <= input.size && outPos + grainLen <= outLen) {
            val base = inPos.toInt()
            for (k in 0 until grainLen) {
                val w = win[k]
                out[outPos + k] += input[base + k] * w
                norm[outPos + k] += w * w
            }
            inPos += readStep
            outPos += hopOut
        }
        // Head compensation: the first grain starts at outPos 0 with its window edge there,
        // so output positions before hopOut are under-covered (norm near zero -> division
        // spike or forced silence). Add one extra grain anchored at outPos -hopOut that
        // reads the input head clamped at index 0, completing COLA coverage over [0, hopOut).
        run {
            val anchor = -hopOut
            for (k in 0 until grainLen) {
                val outIdx = anchor + k
                if (outIdx in 0 until outLen) {
                    val idx = min(input.size - 1, max(0, k + anchor))
                    val w = win[k]
                    out[outIdx] += input[idx] * w
                    norm[outIdx] += w * w
                }
            }
        }
        // Tail: emit ONE final grain anchored at the last full window of the input so the
        // stretched audio covers the nominal duration. Emitting it repeatedly (the old loop)
        // replays one fixed window forever — silent for a decaying transient whose tail is
        // zeros; a single anchored grain keeps energy without the lock-to-silence pathology.
        if (outPos < outLen - 1 && input.size >= grainLen) {
            val base = max(0, input.size - grainLen)
            val remain = min(grainLen, outLen - outPos)
            for (k in 0 until remain) {
                val w = win[k]
                out[outPos + k] += input[base + k] * w
                norm[outPos + k] += w * w
            }
        }
        for (i in out.indices) {
            out[i] = if (norm[i] > 1e-6f) out[i] / norm[i] else 0f
        }
        return if (out.size >= nominal) out.copyOf(nominal) else out + FloatArray(nominal - out.size)
    }

    fun hannWindow(n: Int): FloatArray {
        val w = FloatArray(n)
        for (i in 0 until n) w[i] = (0.5 - 0.5 * Math.cos(2.0 * PI * i / (n - 1).coerceAtLeast(1))).toFloat()
        return w
    }

    /**
     * Autocorrelation pitch estimate of a frame, Hz. Returns 0 when unvoiced.
     *
     * Traps handled (all three bit us during development):
     *  - raw autocorr sums grow with lag -> normalize each lag by its own overlap energy (NCCF);
     *  - a tone that is periodic in the frame gives near-perfect correlation at integer period
     *    multiples -> take the FIRST qualifying peak, never the global max;
     *  - the true period may not land on an integer lag -> refine with parabolic interpolation.
     */
    fun estimatePitchHz(frame: FloatArray, sampleRate: Int): Double {
        val n = frame.size
        if (n < 32) return 0.0
        var energy = 0.0
        for (x in frame) energy += x * x
        if (energy / n < 1e-7) return 0.0 // silence gate

        val mean = (0 until n).sumOf { frame[it].toDouble() } / n
        val buf = DoubleArray(n) { frame[it] - mean } // DC removal kills half-period bias

        val minLagAbs = max(2, (sampleRate / 1000.0).toInt())  // f0 max 1 kHz
        val maxLag = min(n - 2, (sampleRate / 70.0).toInt())   // f0 min 70 Hz
        if (maxLag <= minLagAbs) return 0.0

        // prefix sums of squares for O(1) overlap-energy queries
        val pre = DoubleArray(n + 1)
        for (i in 0 until n) pre[i + 1] = pre[i] + buf[i] * buf[i]

        /** NCCF score at [lag]; caller guarantees lag in range. */
        fun scoreAt(lag: Int): Double {
            var s = 0.0
            for (i in lag until n) s += buf[i] * buf[i - lag]
            val e1 = pre[n] - pre[lag]          // energy of buf[lag..n)
            val e2 = pre[n - lag]               // energy of buf[0..n-lag)
            val denom = Math.sqrt(e1 * e2)
            return if (denom < 1e-9) -1.0 else s / denom
        }

        // Collect every local maximum of the NCCF above the voicing floor, then take the
        // EARLIEST one that is not a harmonic trap. A spurious half-frequency estimate (a
        // period-2p candidate) always has the real period p as an equally strong peak behind
        // it — but the true fundamental itself can score slightly BELOW its own multiples
        // (edge-energy normalization), so "no earlier rival" would wrongly veto it. The
        // discriminator is harmonic alignment: reject p only when some earlier near-equal
        // peak q divides it (p ≈ k*q). For a pure tone the first such q IS the fundamental
        // (multiples at 2p, 3p... are the traps); for the half-frequency trap, the earlier
        // q = p wins because we scan earliest-first and q itself passes this test.
        data class Peak(val lag: Int, val value: Double)
        val peaks = ArrayList<Peak>()
        var lag = minLagAbs
        while (lag <= maxLag) {
            if (scoreAt(lag) >= 0.8 &&
                (lag == minLagAbs || scoreAt(lag) > scoreAt(lag - 1)) &&
                (lag == maxLag || scoreAt(lag) >= scoreAt(lag + 1))
            ) {
                var end = lag
                while (end + 1 <= maxLag && scoreAt(end + 1) == scoreAt(lag)) end++
                peaks += Peak((lag + end) / 2, scoreAt(lag))
                lag = end + 1
            } else lag++
        }
        fun isTrap(p: Peak): Boolean {
            for (q in peaks) {
                if (q.lag >= p.lag || q.value < 0.95 * p.value) continue
                val ratio = p.lag.toDouble() / q.lag
                val nearest = Math.round(ratio)
                if (nearest >= 2 && Math.abs(ratio - nearest) <= 0.12) return true
            }
            return false
        }
        for (p in peaks) {
            if (!isTrap(p)) {
                val lm = p.lag.coerceIn(minLagAbs + 1, maxLag - 1)
                val sl = scoreAt(lm - 1); val sm = scoreAt(lm); val sr = scoreAt(lm + 1)
                val d = sl - 2 * sm + sr
                val shift = if (d != 0.0) 0.5 * (sl - sr) / d else 0.0
                return sampleRate / (lm + shift.coerceIn(-0.5, 0.5))
            }
        }
        return fallbackPeaks(buf, pre, n, minLagAbs, maxLag, sampleRate)
    }

    private fun fallbackPeaks(
        buf: DoubleArray, pre: DoubleArray, n: Int, minLag: Int, maxLag: Int, sampleRate: Int,
    ): Double {
        var bestLag = -1
        var bestScore = 0.5
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (i in lag until n) s += buf[i] * buf[i - lag]
            val e1 = pre[n] - pre[lag]
            val e2 = pre[n - lag]
            val denom = Math.sqrt(e1 * e2)
            val sc = if (denom < 1e-9) -1.0 else s / denom
            if (sc > bestScore) { bestScore = sc; bestLag = lag }
        }
        return if (bestLag < 0) 0.0 else sampleRate / bestLag.toDouble()
    }

    private fun evenDown(n: Int): Int = if (n % 2 == 0) n else n - 1
}
