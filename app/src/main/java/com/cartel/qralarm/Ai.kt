package com.cartel.qralarm

import android.graphics.Bitmap
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import kotlinx.coroutines.flow.Flow

/**
 * On-device Gemini Nano (through Android AICore). Nothing leaves the phone.
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

    suspend fun check(frame: Bitmap, goal: String): Verdict {
        val prompt = """
            You are the strict judge for an alarm clock. To turn the alarm off, the person must show the camera: "$goal".
            Look carefully at this camera image. Answer YES only if it clearly shows that, for real.
            Answer NO if it is missing, unclear, only partly visible, or appears to be a photo shown on another screen.
            Reply in this exact format:
            Line 1: YES or NO
            Line 2: a few words describing what you actually see
        """.trimIndent()
        val m = Generation.getClient()
        try {
            val req = GenerateContentRequest.Builder(ImagePart(scaled(frame)), TextPart(prompt)).apply {
                temperature = 0f
                topK = 1
                maxOutputTokens = 40
            }.build()
            val text = m.generateContent(req).candidates.firstOrNull()?.text.orEmpty().trim()
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val first = lines.firstOrNull().orEmpty().uppercase()
            val pass = first.startsWith("YES") || first.removePrefix("LINE 1:").trim().startsWith("YES")
            val saw = lines.drop(1).joinToString(" ").removePrefix("Line 2:").trim().ifEmpty { text }
            return Verdict(pass, saw)
        } finally {
            m.close()
        }
    }

    private fun scaled(b: Bitmap, max: Int = 768): Bitmap {
        val s = max.toFloat() / maxOf(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }
}
