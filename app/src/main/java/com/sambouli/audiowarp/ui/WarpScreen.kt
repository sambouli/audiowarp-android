package com.sambouli.audiowarp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import com.sambouli.audiowarp.data.ClipStore
import com.sambouli.audiowarp.dsp.Effect

private fun effectAccent(e: Effect): Color = when (e) {
    Effect.NONE -> TextDim
    Effect.REVERB, Effect.GHOST, Effect.ALIEN -> NeonMagenta
    Effect.ROBOT, Effect.RADIO -> NeonCyan
    Effect.CHIPMUNK, Effect.SLOWMO -> NeonLime
    else -> NeonAmber
}

@Composable
fun WarpScreen(vm: WarpViewModel, onRequestPermission: () -> Unit) {
    val state by vm.state.collectAsState()
    val snackbar = SnackbarHostState()
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    LaunchedEffect(Unit) { vm.refreshClips() }

    val accent = effectAccent(state.selectedEffect)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = BgDeep,
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.GraphicEq, null, tint = NeonCyan, modifier = Modifier.size(26.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    "AUDIO//WARP",
                    color = NeonCyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    letterSpacing = 3.sp,
                )
                Spacer(Modifier.weight(1f))
                if (state.busy) CircularProgressIndicator(color = NeonMagenta, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }

            WaveformCanvas(
                samples = state.waveform,
                progress = if (state.durationSec > 0) state.positionSec / state.durationSec else 0f,
                level = state.rmsLevel,
                recording = state.recording,
                accent = accent,
                onSeekFraction = { f -> vm.seek(f * state.durationSec) },
                modifier = Modifier.testTag("waveform"),
            )

            // Transport row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    fmtTime(state.positionSec) + " / " + fmtTime(state.durationSec),
                    color = TextDim, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    modifier = Modifier.testTag("timecode"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.toggleLoop() }, modifier = Modifier.testTag("loop")) {
                        Icon(
                            Icons.Filled.Repeat, "Loop",
                            tint = if (state.loop) NeonLime else TextDim,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(BgPanelHi)
                            .border(2.dp, accent, CircleShape)
                            .clickable(enabled = !state.recording && state.sourceWav != null) { vm.togglePlay() }
                            .testTag("play_pause"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            "Play/Pause",
                            tint = accent,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    IconButton(
                        onClick = { vm.saveClip() },
                        enabled = state.sourceWav != null,
                        modifier = Modifier.testTag("save"),
                    ) {
                        Icon(Icons.Filled.Save, "Save", tint = if (state.sourceWav != null) NeonCyan else TextDim)
                    }
                    IconButton(
                        onClick = {
                            val intent = vm.shareIntent()
                            if (intent != null) {
                                val app = vm.getApplication<android.app.Application>()
                                app.startActivity(Intent.createChooser(intent, "Share clip"))
                            }
                        },
                        enabled = state.sourceWav != null,
                        modifier = Modifier.testTag("share"),
                    ) {
                        Icon(Icons.Filled.Share, "Share", tint = if (state.sourceWav != null) NeonMagenta else TextDim)
                    }
                }
            }

            // Effect chips
            Text("MODULATION MATRIX", color = TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("effect_row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Effect.entries.forEach { e ->
                    EffectChip(e, selected = state.selectedEffect == e, onClick = { vm.selectEffect(e) })
                }
            }

            // Record button
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val recColor = animateColorAsState(if (state.recording) DangerRed else NeonMagenta, label = "rec")
                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .background(BgPanel)
                        .border(3.dp, recColor.value, CircleShape)
                        .clickable { vm.toggleRecord(onRequestPermission) }
                        .testTag("record"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Mic, "Record",
                        tint = recColor.value,
                        modifier = Modifier.size(38.dp),
                    )
                }
            }
            Text(
                if (state.recording) "● REC — tap to stop" else "tap the mic to record",
                color = if (state.recording) DangerRed else TextDim,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.CenterHorizontally).testTag("record_hint"),
            )

            // Clip library
            Text("CLIP VAULT", color = TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("clip_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.clips, key = { it.id }) { clip ->
                    ClipRow(
                        clip = clip,
                        active = clip.id == state.activeClipId,
                        onClick = { vm.openClip(clip.id) },
                        onDelete = { vm.deleteClip(clip.id) },
                    )
                }
                if (state.clips.isEmpty()) {
                    item { Text("no clips yet — record and save", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun EffectChip(e: Effect, selected: Boolean, onClick: () -> Unit) {
    val accent = effectAccent(e)
    val bg by animateColorAsState(if (selected) accent.copy(alpha = 0.22f) else BgPanel, label = "chipbg")
    val fg by animateColorAsState(if (selected) accent else TextDim, label = "chipfg")
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, if (selected) accent else GridLine, RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("chip_${e.id}"),
    ) {
        Text(e.label.uppercase(), color = fg, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun ClipRow(clip: ClipStore.Clip, active: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val accent = effectAccent(clip.effect)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) BgPanelHi else BgPanel)
            .border(1.dp, if (active) accent else GridLine, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(clip.name, color = TextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Text("%.1fs".format(clip.durationSec), color = TextDim, fontSize = 11.sp)
        }
        IconButton(onClick = onDelete, modifier = Modifier.testTag("delete_${clip.id}")) {
            Icon(Icons.Filled.Delete, "Delete", tint = DangerRed, modifier = Modifier.size(18.dp))
        }
    }
}

private fun fmtTime(sec: Float): String {
    val s = sec.coerceAtLeast(0f)
    return "%d:%04.1f".format(s.toInt() / 60, s - (s.toInt() / 60) * 60)
}
