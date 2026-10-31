package com.sambouli.audiowarp.dsp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Canonical RIFF/WAVE PCM 16-bit reader/writer. Pure Java IO — unit testable on the JVM.
 * Mono, [SAMPLE_RATE] Hz by default (16 kHz keeps DSP cheap on-device).
 */
object WavIO {
    const val SAMPLE_RATE = 16_000
    const val BITS_PER_SAMPLE = 16
    const val CHANNELS = 1

    private const val HEADER_BYTES = 44

    fun writeTo(out: OutputStream, pcm: ByteArray, channels: Int = CHANNELS, sampleRate: Int = SAMPLE_RATE) {
        val byteRate = sampleRate * channels * BITS_PER_SAMPLE / 8
        val blockAlign = channels * BITS_PER_SAMPLE / 8
        val dos = DataOutputStream(out)
        dos.writeBytes("RIFF")
        dos.writeIntLE(36 + pcm.size)
        dos.writeBytes("WAVE")
        dos.writeBytes("fmt ")
        dos.writeIntLE(16)                 // fmt chunk size
        dos.writeShortLE(1)                // PCM
        dos.writeShortLE(channels)
        dos.writeIntLE(sampleRate)
        dos.writeIntLE(byteRate)
        dos.writeShortLE(blockAlign)
        dos.writeShortLE(BITS_PER_SAMPLE)
        dos.writeBytes("data")
        dos.writeIntLE(pcm.size)
        dos.write(pcm)
        dos.flush()
    }

    data class WavData(val pcm: ByteArray, val channels: Int, val sampleRate: Int)

    /** Parse a WAV stream; tolerates extra chunks before data. Throws on malformed input. */
    fun read(`in`: InputStream): WavData {
        fun readN(n: Int): ByteArray {
            val b = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = `in`.read(b, got, n - got)
                if (r < 0) throw IllegalArgumentException("unexpected EOF after $got/$n bytes")
                got += r
            }
            return b
        }
        fun le32(b: ByteArray, off: Int) =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
        fun le16(b: ByteArray, off: Int) =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        val riff = readN(4)
        require(String(riff) == "RIFF") { "not a RIFF file" }
        readN(4) // size
        require(String(readN(4)) == "WAVE") { "not a WAVE file" }

        var channels = 1
        var sampleRate = SAMPLE_RATE
        var bits = BITS_PER_SAMPLE
        var format = 1
        var pcm: ByteArray? = null

        while (true) {
            val id = try { readN(4) } catch (e: IllegalArgumentException) { break }
            val szB = try { readN(4) } catch (e: IllegalArgumentException) { break }
            val size = le32(szB, 0)
            val body = readN(size + (size and 1)) // chunks are word-aligned
            when (String(id)) {
                "fmt " -> {
                    format = le16(body, 0)
                    channels = le16(body, 2)
                    sampleRate = le32(body, 4)
                    bits = le16(body, 14)
                }
                "data" -> pcm = body.copyOf(size)
            }
            if (pcm != null && format != 1) throw IllegalArgumentException("only PCM supported, got $format")
            if (pcm != null) break
        }
        require(bits == 16) { "only 16-bit PCM supported, got $bits" }
        require(channels >= 1)
        val data = pcm ?: throw IllegalArgumentException("no data chunk")
        return WavData(data, channels, sampleRate)
    }

    /** Convenience: floats -> WAV bytes. */
    fun floatsToWav(samples: FloatArray, sampleRate: Int = SAMPLE_RATE, channels: Int = CHANNELS): ByteArray {
        val bos = ByteArrayOutputStream()
        writeTo(bos, Dsp.monoFloatsToPcmBytes(samples, channels), channels, sampleRate)
        return bos.toByteArray()
    }

    /** Convenience: WAV bytes -> mono floats. */
    fun wavToFloats(wav: ByteArray): Pair<FloatArray, Int> {
        val d = read(java.io.ByteArrayInputStream(wav))
        return Dsp.pcmBytesToMonoFloats(d.pcm, d.channels) to d.sampleRate
    }
}

// Little-endian helpers over OutputStream (DataOutputStream is big-endian).
internal fun DataOutputStream.writeIntLE(v: Int) {
    write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
}

internal fun DataOutputStream.writeShortLE(v: Int) {
    write(v and 0xFF); write((v shr 8) and 0xFF)
}
