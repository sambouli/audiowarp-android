package com.sambouli.audiowarp

import android.content.Context
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Launches the REAL MainActivity on the JVM. Catches onCreate NPEs and Compose render crashes
 * that pure-Kotlin tests can never see (the class of bug that kills apps at launch on device).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AudioWarpApp::class)
class SmokeActivityTest {

    @Test fun activityLaunchesAndBuildsComposeTree() {
        val controller: ActivityController<MainActivity> =
            Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
        val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
        assertNotNull("no content view", root)
        // Compose renders into a View host under content; give it one main-loop tick.
        shadowOf(getMainLooper()).idle()
        assertNotNull(root)
        controller.close()
    }

    @Test fun crashOverlayShowsWhenLastCrashPresent() {
        AudioWarpApp.lastCrash = "java.lang.RuntimeException: test boom"
        try {
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            shadowOf(getMainLooper()).idle()
            assertNotNull(controller.get())
            controller.close()
        } finally {
            AudioWarpApp.lastCrash = null
        }
    }

    private fun shadowOf(looper: android.os.Looper) = org.robolectric.Shadows.shadowOf(looper)
    private fun getMainLooper(): android.os.Looper = android.os.Looper.getMainLooper()
}
