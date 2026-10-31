package com.sambouli.audiowarp

import android.app.Application
import java.io.File

/**
 * Global crash recorder: writes the trace to filesDir/crash.txt so the user can screenshot it
 * on next launch — no adb needed on a device we never have plugged in.
 */
class AudioWarpApp : Application() {

    override fun onCreate() {
        super.onCreate()
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        val previous = try { File(filesDir, "crash.txt").takeIf { it.exists() }?.readText() } catch (_: Exception) { null }
        if (!previous.isNullOrBlank()) lastCrash = previous
        Thread.setDefaultUncaughtExceptionHandler { _, t ->
            try {
                val sw = java.io.StringWriter()
                t.printStackTrace(java.io.PrintWriter(sw))
                File(filesDir, "crash.txt").writeText(sw.toString())
            } catch (_: Exception) {
            } finally {
                // Chain to the default handler so the process still dies normally (no double-kill).
                defaultHandler?.uncaughtException(Thread.currentThread(), t)
            }
        }
    }

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    companion object {
        @Volatile
        var lastCrash: String? = null
    }
}
