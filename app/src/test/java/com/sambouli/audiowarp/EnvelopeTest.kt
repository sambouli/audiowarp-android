package com.sambouli.audiowarp

import com.sambouli.audiowarp.ui.WarpViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeTest {

    @Test fun envelopeBucketsPeaks() {
        val samples = FloatArray(1600)
        samples[0] = 0.2f
        samples[800] = 0.9f
        val env = WarpViewModel.envelope(samples, 2)
        assertEquals(2, env.size)
        assertEquals(0.2f, env[0], 1e-6f)
        assertEquals(0.9f, env[1], 1e-6f)
    }

    @Test fun envelopeOfSilenceIsZero() {
        val env = WarpViewModel.envelope(FloatArray(500), WarpViewModel.BARS)
        assertTrue(env.all { it == 0f })
        assertEquals(WarpViewModel.BARS, env.size)
    }

    @Test fun envelopeClampsOverRangeInput() {
        val env = WarpViewModel.envelope(floatArrayOf(2f, -3f, 0.5f), 1)
        assertEquals(1f, env[0], 1e-6f)
    }

    @Test fun envelopeEmptyInputReturnsZeros() {
        assertEquals(8, WarpViewModel.envelope(FloatArray(0), 8).size)
    }
}
