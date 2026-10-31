package com.sambouli.audiowarp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sambouli.audiowarp.ui.BgDeep
import com.sambouli.audiowarp.ui.DangerRed
import com.sambouli.audiowarp.ui.WarpScreen
import com.sambouli.audiowarp.ui.WarpViewModel

class MainActivity : ComponentActivity() {

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Immersive AFTER the content view exists (window.insetsController before DecorView NPEs).
        setContent {
            Box(Modifier.fillMaxSize().background(BgDeep)) {
                val crash = AudioWarpApp.lastCrash
                if (crash != null) {
                    CrashOverlay(crash)
                } else {
                    val vm: WarpViewModel = viewModel()
                    WarpScreen(vm, onRequestPermission = { requestMic() })
                }
            }
            LaunchedEffect(Unit) { applyImmersive() }
        }
    }

    private fun requestMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun applyImmersive() {
        try {
            window.decorView // force DecorView creation first
            window.insetsController?.let {
                it.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(android.view.WindowInsets.Type.systemBars())
            }
        } catch (_: Exception) {
        }
    }

    @Composable
    private fun CrashOverlay(crash: String) {
        Box(Modifier.fillMaxSize().background(BgDeep).padding(16.dp), contentAlignment = Alignment.TopStart) {
            Text(
                "LAST CRASH\n\n" + crash.take(4000),
                color = DangerRed,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.testTag("crash_overlay"),
            )
        }
    }
}
