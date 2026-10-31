package com.sambouli.audiowarp.data

import android.content.Context
import com.sambouli.audiowarp.dsp.Effect
import com.sambouli.audiowarp.dsp.WavIO
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clip store: WAV files under filesDir/clips plus a JSON index.
 * File IO is real; index logic is pure enough to unit test with Robolectric's temp dirs.
 */
class ClipStore(private val context: Context) {

    data class Clip(
        val id: String,
        val name: String,
        val file: File,
        val effect: Effect,
        val createdAtMs: Long,
        val durationSec: Double,
        val sourceId: String?,
    )

    private val clipsDir: File get() = File(context.filesDir, "clips").apply { mkdirs() }
    private val indexFile: File get() = File(clipsDir, "index.json")

    fun list(): List<Clip> = readIndex().sortedByDescending { it.createdAtMs }

    fun save(wavBytes: ByteArray, effect: Effect, sourceId: String? = null): Clip {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val id = "${stamp}-${effect.id}-${(Math.random() * 1e6).toLong()}"
        val file = File(clipsDir, "$id.wav")
        file.writeBytes(wavBytes)
        val (_, sr) = WavIO.wavToFloats(wavBytes)
        val frames = wavBytes.size.let { (it - 44).coerceAtLeast(0) } / (2 * WavIO.CHANNELS)
        val clip = Clip(
            id = id,
            name = "${effect.label} · ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}",
            file = file,
            effect = effect,
            createdAtMs = System.currentTimeMillis(),
            durationSec = frames.toDouble() / sr,
            sourceId = sourceId,
        )
        val all = readIndex().toMutableList()
        all += clip
        writeIndex(all)
        return clip
    }

    fun delete(id: String): Boolean {
        val all = readIndex().toMutableList()
        val victim = all.firstOrNull { it.id == id } ?: return false
        victim.file.delete()
        all.remove(victim)
        writeIndex(all)
        return true
    }

    fun find(id: String): Clip? = readIndex().firstOrNull { it.id == id }

    private fun readIndex(): List<Clip> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Clip(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    file = File(o.getString("file")),
                    effect = Effect.fromId(o.getString("effect")),
                    createdAtMs = o.getLong("createdAt"),
                    durationSec = o.getDouble("duration"),
                    sourceId = if (o.isNull("source")) null else o.optString("source", null),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeIndex(clips: List<Clip>) {
        val arr = JSONArray()
        clips.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("file", c.file.absolutePath)
                put("effect", c.effect.id)
                put("createdAt", c.createdAtMs)
                put("duration", c.durationSec)
                put("source", c.sourceId ?: JSONObject.NULL)
            })
        }
        indexFile.writeText(arr.toString(2))
    }
}
