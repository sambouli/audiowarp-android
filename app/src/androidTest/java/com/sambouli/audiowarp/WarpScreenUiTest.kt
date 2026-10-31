package com.sambouli.audiowarp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sambouli.audiowarp.dsp.Effect
import com.sambouli.audiowarp.ui.WarpScreen
import com.sambouli.audiowarp.ui.WarpViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI integration tests. Require a device/emulator (`connectedDebugAndroidTest`) —
 * kept as the documented UI contract; CI here compiles them and Robolectric covers launch.
 */
@RunWith(AndroidJUnit4::class)
class WarpScreenUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun vm(): WarpViewModel {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AudioWarpApp
        return WarpViewModel(app)
    }

    @Test fun screenRendersAllPrimaryControls() {
        composeRule.setContent { WarpScreen(vm(), onRequestPermission = {}) }
        composeRule.onNodeWithTag("waveform").assertIsDisplayed()
        composeRule.onNodeWithTag("record").assertIsDisplayed()
        composeRule.onNodeWithTag("play_pause").assertIsDisplayed()
        composeRule.onNodeWithTag("loop").assertIsDisplayed()
        composeRule.onNodeWithTag("save").assertIsDisplayed()
        composeRule.onNodeWithTag("share").assertIsDisplayed()
        composeRule.onNodeWithTag("clip_list").assertIsDisplayed()
    }

    @Test fun allTwelveEffectChipsArePresent() {
        composeRule.setContent { WarpScreen(vm(), onRequestPermission = {}) }
        for (e in Effect.entries) {
            composeRule.onNodeWithTag("chip_${e.id}").assertIsDisplayed()
        }
    }

    @Test fun selectingAnEffectChipDoesNotCrashWithoutAudio() {
        composeRule.setContent { WarpScreen(vm(), onRequestPermission = {}) }
        composeRule.onNodeWithTag("chip_reverb").performClick()
        composeRule.onNodeWithTag("chip_robot").performClick()
        composeRule.waitForIdle()
    }

    @Test fun recordTapWithoutPermissionInvokesCallback() {
        var asked = false
        composeRule.setContent { WarpScreen(vm(), onRequestPermission = { asked = true }) }
        composeRule.onNodeWithTag("record").performClick()
        composeRule.waitForIdle()
        assertTrue("permission callback not fired", asked)
    }
}
