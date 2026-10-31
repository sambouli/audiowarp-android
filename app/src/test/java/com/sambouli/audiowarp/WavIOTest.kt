package com.sambouli.audiowarp

import com.sambouli.audiowarp.dsp.WavIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.math.PI
import kotlin.math.sin

class WavIOTest {

    @Test fun headerIsCanonicalRiff44Bytes() {
        val wav = WavIO.floatsToWav(FloatArray(1600))
        assertEquals(44 + 3200, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals("data", String(wav, 36, 4))
        // RIFF size = file - 8
        val riffSize = le32(wav, 4)
        assertEquals(wav.size - 8, riffSize)
        // data size = pcm bytes
        assertEquals(3200, le32(wav, 40))
        // PCM format 1, mono, 16k, 16-bit
        assertEquals(1, le16(wav, 20))
        assertEquals(1, le16(wav, 22))
        assertEquals(16_000, le32(wav, 24))
        assertEquals(16, le16(wav, 34))
    }

    @Test fun roundTripPreservesSamplesWithinQuantization() {
        val samples = FloatArray(4000) { (0.7 * sin(2.0 * PI * 300.0 * it / 16_000)).toFloat() }
        val wav = WavIO.floatsToWav(samples)
        val (back, sr) = WavIO.wavToFloats(wav)
        assertEquals(16_000, sr)
        assertEquals(samples.size, back.size)
        for (i in samples.indices) assertTrue(Math.abs(back[i] - samples[i]) < 1e-4)
    }

    @Test fun readToleratesExtraChunksBeforeData() {
        val base = WavIO.floatsToWav(FloatArray(100))
        // inject a LIST chunk between fmt and data
        val out = ByteArray(base.size + 8 + 4)
        base.copyInto(out, 0, 0, 36)
        var o = 36
        "LIST".toByteArray().copyInto(out, o); o += 4
        writeLe32(out, o, 4); o += 4
        "INFO".toByteArray().copyInto(out, o); o += 4
        base.copyInto(out, o, 36, base.size)
        val (samples, _) = WavIO.wavToFloats(out)
        assertEquals(100, samples.size)
    }

    @Test fun rejectsNonWavInput() {
        try {
            WavIO.read(ByteArrayInputStream("not a wav at all, really long string here".toByteArray()))
            throw AssertionError("expected failure")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    private fun le16(b: ByteArray, off: Int) =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int) =
        le16(b, off) or (le16(b, off + 2) shl 16)

    private fun writeLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }
}
