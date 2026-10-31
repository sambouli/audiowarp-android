package com.sambouli.audiowarp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Neon waveform canvas: bars from the peak envelope, playhead line, tap-to-seek.
 * Also doubles as the live level meter while recording (bars animate from [level]).
 */
@Composable
fun WaveformCanvas(
    samples: List<Float>,
    progress: Float,          // 0..1 playhead
    level: Float,             // live rms when recording
    recording: Boolean,
    accent: Color,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(150.dp)
            .padding(horizontal = 4.dp)
            .background(BgPanel, RoundedCornerShape(14.dp))
            .pointerInput(samples.size, recording) {
                detectTapGestures { offset ->
                    if (!recording && samples.isNotEmpty()) {
                        onSeekFraction((offset.x / size.width).coerceIn(0f, 1f))
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            val w = size.width
            val h = size.height
            val midY = h / 2f

            // grid lines
            val grid = GridLine
            drawLine(grid, Offset(0f, midY), Offset(w, midY), 1f)
            for (gx in 0..w.toInt() step (w / 8f).toInt().coerceAtLeast(1)) {
                drawLine(grid.copy(alpha = 0.4f), Offset(gx.toFloat(), 0f), Offset(gx.toFloat(), h), 1f)
            }

            if (recording) {
                // live symmetric level bar
                val amp = level.coerceIn(0f, 1f) * midY
                drawLine(
                    color = if (level > 0.7f) DangerRed else accent,
                    start = Offset(0f, midY),
                    end = Offset(w, midY),
                    strokeWidth = (amp * 2f).coerceAtLeast(2f),
                )
            } else if (samples.isNotEmpty()) {
                val n = samples.size
                val barW = w / n
                val playedFrac = progress.coerceIn(0f, 1f)
                for (i in 0 until n) {
                    val amp = samples[i] * midY * 0.96f
                    val x = i * barW + barW * 0.15f
                    val played = (i + 0.5f) / n <= playedFrac
                    val color = if (played) accent else accent.copy(alpha = 0.32f)
                    drawLine(
                        color = color,
                        start = Offset(x, midY - amp),
                        end = Offset(x, midY + amp),
                        strokeWidth = (barW * 0.7f).coerceAtLeast(1.5f),
                    )
                }
                // playhead
                val px = playedFrac * w
                drawLine(Color.White, Offset(px, 0f), Offset(px, h), 2f)
            }
        }
    }
}
