package com.sambouli.audiowarp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** On-device sanity: context, permissions declared, clip dir writable. */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {

    @Test fun contextAndPermissions() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.sambouli.audiowarp", ctx.packageName)
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.toList().contains(android.Manifest.permission.RECORD_AUDIO))
    }

    @Test fun filesDirWritable() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File(ctx.filesDir, "probe.txt")
        f.writeText("ok")
        assertEquals("ok", f.readText())
        f.delete()
    }
}
