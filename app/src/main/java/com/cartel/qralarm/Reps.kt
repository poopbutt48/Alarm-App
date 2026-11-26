package com.cartel.qralarm

import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Counts push-ups or squats from ML Kit pose landmarks.
 * A rep = joint goes from straight ("up") to bent ("down") and back to straight,
 * with the body in the right orientation (flat for push-ups, upright for squats).
 */
class RepCounter(private val pushups: Boolean) {
    var reps = 0
        private set
    var hint = "Get your whole body in view"
        private set

    private var down = false

    // Joint angles in degrees (shoulder-elbow-wrist / hip-knee-ankle).
    private val bentBelow = if (pushups) 100.0 else 110.0
    private val straightAbove = if (pushups) 150.0 else 160.0

    /** Returns true if a new rep was just completed. */
    fun onPose(p: Pose): Boolean {
        val sides = listOf(
            listOf(PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST,
                PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE),
            listOf(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST,
                PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE),
        )
        // Pick the side of the body the camera sees best.
        val best = sides.map { ids -> ids.map { p.getPoseLandmark(it) } }
            .filter { lm -> lm.all { it != null } }
            .maxByOrNull { lm -> needed(lm).minOf { it!!.inFrameLikelihood } }
        if (best == null || needed(best).any { it!!.inFrameLikelihood < 0.6f }) {
            hint = if (pushups) "Can't see you — show your side, arms and hips" else "Can't see you — show your hips, knees and feet"
            return false
        }
        val (sh, el, wr, hip, kn, an) = best.map { it!! }

        // Torso direction: push-ups need it roughly horizontal, squats roughly vertical.
        val dx = abs(sh.position.x - hip.position.x)
        val dy = abs(sh.position.y - hip.position.y)
        val orientationOk = if (pushups) dx > dy * 1.2f else dy > dx * 1.2f
        if (!orientationOk) {
            hint = if (pushups) "Get into push-up position (body flat, side-on to the camera)" else "Stand up, side-on to the camera"
            return false
        }

        val a = if (pushups) angle(sh, el, wr) else angle(hip, kn, an)
        if (!down && a < bentBelow) {
            down = true
            hint = "Down ✓ — now push back up"
        } else if (down && a > straightAbove) {
            down = false
            reps++
            hint = "Nice! Keep going"
            return true
        } else if (!down) {
            hint = if (pushups) "Go down (bend your elbows)" else "Squat down (bend your knees)"
        }
        return false
    }

    private fun needed(lm: List<PoseLandmark?>) =
        if (pushups) listOf(lm[0], lm[1], lm[2], lm[3]) else listOf(lm[0], lm[3], lm[4], lm[5])

    private fun angle(a: PoseLandmark, mid: PoseLandmark, c: PoseLandmark): Double {
        val ax = (a.position.x - mid.position.x).toDouble(); val ay = (a.position.y - mid.position.y).toDouble()
        val cx = (c.position.x - mid.position.x).toDouble(); val cy = (c.position.y - mid.position.y).toDouble()
        val cos = (ax * cx + ay * cy) / (sqrt(ax * ax + ay * ay) * sqrt(cx * cx + cy * cy) + 1e-9)
        return Math.toDegrees(acos(cos.coerceIn(-1.0, 1.0)))
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
