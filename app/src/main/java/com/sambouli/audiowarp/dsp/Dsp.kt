package com.sambouli.audiowarp.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin DSP core. No android.* imports: everything here runs and is unit tested on the JVM.
 * All processing works on mono Float buffers normalized to [-1, 1].
 */
object Dsp {

    const val GAIN = 0.95f

    fun clamp(x: Float): Float = if (x > 1f) 1f else if (x < -1f) -1f else x

    fun clampBuffer(buf: FloatArray) {
        for (i in buf.indices) buf[i] = clamp(buf[i])
    }

    /** Convert interleaved 16-bit PCM bytes (little endian) to mono float [-1,1]. */
    fun pcmBytesToMonoFloats(pcm: ByteArray, channels: Int): FloatArray {
        require(channels >= 1)
        val frames = pcm.size / (2 * channels)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0.0
            for (c in 0 until channels) {
                val idx = (f * channels + c) * 2
                val lo = pcm[idx].toInt() and 0xFF
                val hi = pcm[idx + 1].toInt()
                val s = ((hi shl 8) or lo).toShort()
                sum += s / 32768.0
            }
            out[f] = (sum / channels).toFloat()
        }
        return out
    }

    /** Convert mono float [-1,1] to interleaved 16-bit PCM bytes (little endian). */
    fun monoFloatsToPcmBytes(buf: FloatArray, channels: Int): ByteArray {
        require(channels >= 1)
        val out = ByteArray(buf.size * 2 * channels)
        for (f in buf.indices) {
            val v = clamp(buf[f])
            val s = Math.round(v * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            for (c in 0 until channels) {
                val idx = (f * channels + c) * 2
                out[idx] = (s and 0xFF).toByte()
                out[idx + 1] = ((s shr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    /** Normalize peak to [targetPeak], no-op when silent. */
    fun normalize(buf: FloatArray, targetPeak: Float = GAIN) {
        var peak = 0f
        for (x in buf) peak = max(peak, abs(x))
        if (peak < 1e-6f) return
        val g = targetPeak / peak
        for (i in buf.indices) buf[i] = clamp(buf[i] * g)
    }

    /** Blend dry + wet at a fixed wet level (0..1). */
    fun blend(dry: FloatArray, wet: FloatArray, wetLevel: Float): FloatArray {
        val w = wetLevel.coerceIn(0f, 1f)
        val n = min(dry.size, wet.size)
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = clamp((1f - w) * dry[i] + w * wet[i])
        return out
    }

    /** Mix dry + wet with crossfade-in of wet over the first [fadeSamples] samples. fade 0 => full wet. */
    fun mix(dry: FloatArray, wet: FloatArray, fadeSamples: Int = 1): FloatArray {
        val n = min(dry.size, wet.size)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val w = if (fadeSamples <= 0) 1f else min(1f, i.toFloat() / fadeSamples)
            out[i] = clamp((1f - w) * dry[i] + w * wet[i])
        }
        return out
    }

    /** Linear interpolation read at fractional index; 0 outside bounds. */
    fun readLinear(buf: FloatArray, pos: Double): Float {
        if (buf.isEmpty()) return 0f
        if (pos < 0.0 || pos >= buf.size - 1) {
            return if (pos >= 0 && pos < buf.size) buf[pos.toInt()] else 0f
        }
        val i = pos.toInt()
        val frac = (pos - i).toFloat()
        return buf[i] * (1f - frac) + buf[i + 1] * frac
    }

    /** One biquad section (RBJ, Q ~ 0.707). */
    private fun biquad(buf: FloatArray, b0: Float, b1: Float, b2: Float, a1: Float, a2: Float) {
        var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
        for (i in buf.indices) {
            val x0 = buf[i]
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0
            y2 = y1; y1 = y0
            buf[i] = y0
        }
    }

    /** Butterworth low-pass (two cascaded RBJ sections => -24 dB/oct). */
    fun lowPass(buf: FloatArray, cutoffHz: Float, sampleRate: Int) {
        run {
            val w0 = 2.0 * Math.PI * cutoffHz.coerceIn(10f, sampleRate / 2f - 100f) / sampleRate
            val cosw = Math.cos(w0)
            val sinw = Math.sin(w0)
            val q = 0.5412 // two cascaded Q=0.707 sections approximated by one slightly-tuned pair
            val alpha = sinw / (2.0 * q)
            val b0 = ((1 - cosw) / 2).toFloat()
            val b1 = (1 - cosw).toFloat()
            val b2 = b0
            val a0 = (1 + alpha).toFloat()
            val a1 = (-2 * cosw).toFloat()
            val a2 = ((1 - alpha)).toFloat()
            biquad(buf, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            biquad(buf, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }

    /** Butterworth high-pass (two cascaded RBJ sections). */
    fun highPass(buf: FloatArray, cutoffHz: Float, sampleRate: Int) {
        val w0 = 2.0 * Math.PI * cutoffHz.coerceIn(10f, sampleRate / 2f - 100f) / sampleRate
        val cosw = Math.cos(w0)
        val sinw = Math.sin(w0)
        val q = 0.5412
        val alpha = sinw / (2.0 * q)
        val b0 = ((1 + cosw) / 2).toFloat()
        val b1 = (-(1 + cosw)).toFloat()
        val b2 = b0
        val a0 = (1 + alpha).toFloat()
        val a1 = (-2 * cosw).toFloat()
        val a2 = ((1 - alpha)).toFloat()
        biquad(buf, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        biquad(buf, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /** Zero-phase (forward+backward) low-pass: no group-delay smear. */
    fun zeroPhaseLowPass(buf: FloatArray, cutoffHz: Float, sampleRate: Int) {
        lowPass(buf, cutoffHz, sampleRate)
        reverseInPlace(buf)
        lowPass(buf, cutoffHz, sampleRate)
        // Restore time orientation: the buffer is mirrored after the backward
        // pass. Forgetting this flip leaves the signal time-reversed — spectral
        // tests still pass (spectrum is reversal-invariant) but any positional
        // assertion (echo at `delaySamples`) sees the tail mirrored to the end.
        reverseInPlace(buf)
    }

    private fun reverseInPlace(buf: FloatArray) {
        var i = 0
        var j = buf.size - 1
        while (i < j) {
            val t = buf[i]; buf[i] = buf[j]; buf[j] = t
            i++; j--
        }
    }

    /** Soft-clip saturation, drive > 1 pushes harder. tanh curve. */
    fun softClip(buf: FloatArray, drive: Float) {
        val d = drive.coerceAtLeast(1f)
        for (i in buf.indices) {
            buf[i] = clamp(tanhApprox(buf[i] * d))
        }
    }

    private fun tanhApprox(x: Float): Float {
        val ax = if (x < 0) -x else x
        if (ax > 4f) return if (x < 0) -1f else 1f
        val e = Math.exp((-2.0 * ax).toDouble()).toFloat()
        val t = (1f - e) / (1f + e)
        return if (x < 0) -t else t
    }

    /** Quantize amplitude to 2^bits levels. bits e.g. 5 => 32 levels. */
    fun bitCrush(buf: FloatArray, bits: Int) {
        val b = bits.coerceIn(1, 16)
        val levels = (1 shl b) - 1
        for (i in buf.indices) {
            buf[i] = (Math.round(clamp(buf[i]) * levels) / levels.toDouble()).toFloat()
        }
    }

    /** Integer-lag feedback echo. feedback in [0,0.95). Output length == input length. */
    fun feedbackEcho(buf: FloatArray, delaySamples: Int, feedback: Float, mixWet: Float = 0.5f): FloatArray {
        val lag = delaySamples.coerceIn(1, max(1, buf.size))
        val fb = feedback.coerceIn(0f, 0.95f)
        val wet = FloatArray(buf.size)
        // iterative comb: wet[n] = x[n] + fb*wet[n-lag]
        for (n in buf.indices) {
            wet[n] = buf[n] + if (n >= lag) fb * wet[n - lag] else 0f
        }
        return blend(buf, wet, mixWet)
    }

    /** Schroeder reverb: parallel comb filters + serial allpass. */
    fun reverb(input: FloatArray, sampleRate: Int, roomSize: Float = 0.8f): FloatArray {
        val size = roomSize.coerceIn(0f, 0.98f)
        // long delays (60..150 ms) => a hall-sized tail even for short inputs
        val combDelaysMs = floatArrayOf(67.7f, 83.1f, 101.3f, 121.7f)
        val allpassDelaysMs = floatArrayOf(15.0f, 7.1f)
        val combFeedback = 0.75f + 0.22f * size
        val combBufs = combDelaysMs.map { FloatArray((it / 1000f * sampleRate).toInt().coerceAtLeast(1)) }
        val combIdx = IntArray(combBufs.size)
        val allpassBufs = allpassDelaysMs.map { FloatArray((it / 1000f * sampleRate).toInt().coerceAtLeast(1)) }
        val allpassIdx = IntArray(allpassBufs.size)
        val allpassG = 0.5f
        val outLen = input.size + (combBufs.maxOf { it.size } * 4)
        val out = FloatArray(outLen)
        val combGain = 0.42f / combBufs.size
        // run the network past the input end so the tail rings out
        for (n in 0 until outLen) {
            val x = if (n < input.size) input[n] else 0f
            var acc = 0f
            for (c in combBufs.indices) {
                val cb = combBufs[c]
                acc += combGain * cb[combIdx[c]]
                cb[combIdx[c]] = clamp(x + combFeedback * cb[combIdx[c]])
                combIdx[c] = (combIdx[c] + 1) % cb.size
            }
            var y = acc
            for (a in allpassBufs.indices) {
                val ab = allpassBufs[a]
                val bufOut = ab[allpassIdx[a]]
                val mid = -allpassG * y + bufOut
                ab[allpassIdx[a]] = y + allpassG * mid
                y = mid
                allpassIdx[a] = (allpassIdx[a] + 1) % ab.size
            }
            out[n] = clamp((if (n < input.size) input[n] * 0.6f else 0f) + y)
        }
        return out
    }
}
