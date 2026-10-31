package com.sambouli.audiowarp.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
import com.sambouli.audiowarp.dsp.Dsp
import com.sambouli.audiowarp.dsp.WavIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Low-latency mic capture on a dedicated audio-priority thread.
 * Accumulates mono float frames; exposes a live RMS level for the visualizer.
 */
class Recorder(private val context: Context) {

    @Volatile
    var rmsLevel: Float = 0f
        private set

    @Volatile
    var isRecording: Boolean = false
        private set

    private val frameCount = ByteArrayOutputStream()
    private var job: Job? = null
    private var record: AudioRecord? = null

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Start capture. Returns false when permission is missing or AudioRecord fails to init. */
    fun start(scope: CoroutineScope): Boolean {
        if (isRecording) return true
        if (!hasPermission) return false
        val minBuf = AudioRecord.getMinBufferSize(
            WavIO.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false
        val ar = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WavIO.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, 4096),
            )
        } catch (e: SecurityException) {
            return false
        }
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            return false
        }
        record = ar
        frameCount.reset()
        rmsLevel = 0f
        isRecording = true
        ar.startRecording()
        job = scope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val shortBuf = ShortArray(512)
            while (isActive && isRecording) {
                val n = ar.read(shortBuf, 0, shortBuf.size)
                if (n <= 0) continue
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    bytes[i * 2] = (shortBuf[i].toInt() and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((shortBuf[i].toInt() shr 8) and 0xFF).toByte()
                }
                synchronized(frameCount) { frameCount.write(bytes) }
                var sum = 0.0
                for (i in 0 until n) sum += (shortBuf[i] / 32768.0) * (shortBuf[i] / 32768.0)
                rmsLevel = sqrt(sum / n).toFloat()
            }
        }
        return true
    }

    /** Stop capture and return WAV bytes (empty buffer => silent clip of 0.1 s). */
    fun stop(): ByteArray {
        isRecording = false
        job?.cancel()
        job = null
        record?.let {
            try { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } catch (_: Exception) {}
            it.release()
        }
        record = null
        rmsLevel = 0f
        val pcm = synchronized(frameCount) { frameCount.toByteArray() }
        val safe = if (pcm.isEmpty()) ByteArray((WavIO.SAMPLE_RATE * 0.1).toInt() * 2) else pcm
        val bos = ByteArrayOutputStream()
        WavIO.writeTo(bos, safe)
        return bos.toByteArray()
    }

    fun cancel() {
        if (isRecording) stop()
    }
}
