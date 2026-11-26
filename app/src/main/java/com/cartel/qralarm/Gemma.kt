package com.cartel.qralarm

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs a Gemma model file (.litertlm, the same files Google AI Edge Gallery uses) on the phone
 * with LiteRT-LM. Image-capable ones: Gemma 4 E2B/E4B, Gemma 3n E2B/E4B.
 */
object Gemma {
    private var engine: Engine? = null
    private val lock = Mutex()

    fun file(c: Context) = File(c.filesDir, "model.litertlm")
    fun installed(c: Context) = file(c).let { it.exists() && it.length() > 100_000_000 }
    fun name(c: Context): String = Alarm.prefs(c).getString("gemma_name", null) ?: "Gemma model"
    fun sizeGb(c: Context) = "%.1f GB".format(file(c).length() / 1e9)

    /** Copies a model the user picked into the app's private storage. Reports progress 0..1. */
    suspend fun import(c: Context, uri: Uri, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        var name = "model"
        var size = -1L
        c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                cur.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cur.getString(it) }
                cur.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = cur.getLong(it) }
            }
        }
        require(name.endsWith(".litertlm", ignoreCase = true)) {
            "That's \"$name\". Pick a .litertlm file (e.g. gemma-4-E2B-it.litertlm)."
        }
        if (size > 0) require(c.filesDir.usableSpace > size + 500_000_000) { "Not enough free storage to copy the model." }

        close()
        val tmp = File(c.filesDir, "model.tmp")
        c.contentResolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (size > 0 && done - lastReport > 50_000_000) {
                        lastReport = done
                        withContext(Dispatchers.Main) { progress(done.toFloat() / size) }
                    }
                }
            }
        }
        file(c).delete()
        check(tmp.renameTo(file(c))) { "Couldn't save the model" }
        Alarm.prefs(c).edit().putString("gemma_name", name).apply()
    }

    fun delete(c: Context) {
        close()
        file(c).delete()
        Alarm.prefs(c).edit().remove("gemma_name").apply()
    }

    /** Loads the model (can take 10+ seconds the first time). Safe to call repeatedly. */
    suspend fun load(c: Context): Engine = lock.withLock {
        engine?.let { return it }
        withContext(Dispatchers.IO) {
            val path = file(c).absolutePath
            // GPU first (Edge Gallery notes vision must be on GPU for Gemma 3n); CPU as a fallback.
            val e = try {
                Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), visionBackend = Backend.GPU(),
                    maxNumTokens = 2048, cacheDir = c.cacheDir.path)).also { it.initialize() }
            } catch (gpu: Exception) {
                Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), visionBackend = Backend.GPU(),
                    maxNumTokens = 2048, cacheDir = c.cacheDir.path)).also { it.initialize() }
            }
            engine = e
            e
        }
    }

    fun close() {
        runCatching { engine?.close() }
        engine = null
    }

    suspend fun check(c: Context, frame: Bitmap, goal: String): Nano.Verdict {
        val e = load(c)
        return withContext(Dispatchers.IO) {
            val img = File(c.cacheDir, "frame.jpg")
            img.outputStream().use { Nano.scaled(frame).compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val cfg = ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.1))
            val reply = e.createConversation(cfg).use { conv ->
                conv.sendMessage(Contents.of(Content.ImageFile(img.absolutePath), Content.Text(Nano.prompt(goal)))).toString()
            }
            Nano.parse(reply)
        }
    }
}
