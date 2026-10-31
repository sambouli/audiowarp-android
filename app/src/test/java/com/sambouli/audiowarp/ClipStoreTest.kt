package com.sambouli.audiowarp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sambouli.audiowarp.data.ClipStore
import com.sambouli.audiowarp.dsp.Effect
import com.sambouli.audiowarp.dsp.WavIO
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
class ClipStoreTest {

    private fun wav(nSamples: Int = 8000): ByteArray =
        WavIO.floatsToWav(FloatArray(nSamples) { (0.5 * kotlin.math.sin(it * 0.1)).toFloat() })

    @Test fun saveListDeleteRoundTrip() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = ClipStore(ctx)
        assertTrue(store.list().isEmpty())

        val clip = store.save(wav(), Effect.CHIPMUNK, sourceId = "src-1")
        assertEquals(Effect.CHIPMUNK, clip.effect)
        assertEquals("src-1", clip.sourceId)
        assertEquals(0.5, clip.durationSec, 0.01)
        assertTrue(clip.file.exists())

        val listed = store.list()
        assertEquals(1, listed.size)
        assertEquals(clip.id, listed[0].id)
        assertEquals(clip.name, listed[0].name)

        // index survives re-open (new instance reads same JSON)
        val again = ClipStore(ctx).find(clip.id)
        assertNotNull(again)
        assertEquals(Effect.CHIPMUNK, again!!.effect)

        assertTrue(ClipStore(ctx).delete(clip.id))
        assertTrue(ClipStore(ctx).list().isEmpty())
        assertTrue(!clip.file.exists())
        assertNull(ClipStore(ctx).find(clip.id))
    }

    @Test fun deleteUnknownIdReturnsFalse() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(!ClipStore(ctx).delete("nope"))
    }

    @Test fun corruptIndexDegradesToEmpty() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dir = java.io.File(ctx.filesDir, "clips").apply { mkdirs() }
        java.io.File(dir, "index.json").writeText("{ this is not json ]")
        assertTrue(ClipStore(ctx).list().isEmpty())
    }
}
