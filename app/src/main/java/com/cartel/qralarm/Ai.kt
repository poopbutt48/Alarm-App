package com.cartel.qralarm

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import kotlinx.coroutines.flow.Flow

/** Picks whichever on-device AI is set up: your Gemma file first, else built-in Gemini Nano. */
object Vision {
    /** null = ready; otherwise a reason it isn't. */
    suspend fun problem(c: Context): String? {
        if (Gemma.installed(c)) return null
        val s = runCatching { Nano.status() }.getOrDefault(FeatureStatus.UNAVAILABLE)
        return if (s == FeatureStatus.AVAILABLE) null else "no AI model is set up (Gemini Nano: ${Nano.statusName(s)})"
    }

    suspend fun check(c: Context, frame: Bitmap, goal: String): Nano.Verdict =
        if (Gemma.installed(c)) Gemma.check(c, frame, goal) else Nano.check(frame, goal)

    /** Loads the Gemma model ahead of time so the first CHECK is faster. */
    suspend fun warmUp(c: Context) { if (Gemma.installed(c)) runCatching { Gemma.load(c) } }
}

/**
 * Built-in Gemini Nano (through Android AICore). Nothing leaves the phone.
 * Docs: https://developers.google.com/ml-kit/genai/prompt/android/get-started
 */
object Nano {
    data class Verdict(val pass: Boolean, val saw: String)

    suspend fun status(): Int {
        val m = Generation.getClient()
        return try { m.checkStatus() } finally { m.close() }
    }

    fun statusName(s: Int) = when (s) {
        FeatureStatus.AVAILABLE -> "ready"
        FeatureStatus.DOWNLOADABLE -> "needs download"
        FeatureStatus.DOWNLOADING -> "downloading…"
        else -> "not available on this phone"
    }

    /** Caller collects; the client stays open until the flow finishes. */
    fun download(): Flow<DownloadStatus> = Generation.getClient().download()

    fun prompt(goal: String) = """
        You are the strict judge for an alarm clock. To turn the alarm off, the person must show the camera: "$goal".
        Look carefully at this camera image. Answer YES only if it clearly shows that, for real.
        Answer NO if it is missing, unclear, only partly visible, or appears to be a photo shown on another screen.
        Reply in this exact format:
        Line 1: YES or NO
        Line 2: a few words describing what you actually see
    """.trimIndent()

    fun parse(reply: String): Verdict {
        val text = reply.trim()
        val lines = text.lines().map { it.trim().removePrefix("Line 1:").removePrefix("Line 2:").trim() }
            .filter { it.isNotEmpty() }
        val first = lines.firstOrNull().orEmpty().uppercase().trimStart('*', ' ')
        val pass = first.startsWith("YES")
        val saw = lines.drop(1).joinToString(" ").ifEmpty { text }
        return Verdict(pass, saw)
    }

    suspend fun check(frame: Bitmap, goal: String): Verdict {
        val m = Generation.getClient()
        try {
            val req = GenerateContentRequest.Builder(ImagePart(scaled(frame)), TextPart(prompt(goal))).apply {
                temperature = 0f
                topK = 1
                maxOutputTokens = 40
            }.build()
            return parse(m.generateContent(req).candidates.firstOrNull()?.text.orEmpty())
        } finally {
            m.close()
        }
    }

    fun scaled(b: Bitmap, max: Int = 768): Bitmap {
        val s = max.toFloat() / maxOf(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }
}
