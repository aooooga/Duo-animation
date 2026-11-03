package com.example.duofold.sensors

import kotlin.math.atan2
import kotlin.math.exp

/** Pure math shared by real sensor processing and deterministic regression tests. */
object MotionMath {
    fun wrap(angle: Float): Float {
        val pi = Math.PI.toFloat()
        var result = angle % (2f * pi)
        if (result > pi) result -= 2f * pi
        if (result < -pi) result += 2f * pi
        return result
    }

    /** Exponential smoothing factor for elapsed time; 0 ms means no filtering. */
    fun smoothing(dtSeconds: Float, timeConstantSeconds: Float = 0.010f): Float {
        if (timeConstantSeconds <= 0f) return 1f
        return 1f - exp(-dtSeconds.coerceIn(0f, 0.1f) / timeConstantSeconds)
    }

    /** reference transpose * current, with screen axes in matrix columns. */
    fun relative(reference: FloatArray, current: FloatArray, out: FloatArray) {
        for (r in 0..2) for (c in 0..2) {
            out[r * 3 + c] = reference[r] * current[c] +
                reference[3 + r] * current[3 + c] + reference[6 + r] * current[6 + c]
        }
    }

    /** Left/right tilt: rotation about the screen Y axis. */
    fun tilt(relative: FloatArray): Float = atan2(relative[2], relative[8])

    /** Forward/back tilt: rotation about the screen X axis. */
    fun verticalTilt(relative: FloatArray): Float = atan2(-relative[5], relative[8])

    /** Derivative of atan2(normal.x, normal.z), including simultaneous pitch/roll. */
    fun tiltRate(relative: FloatArray, screenGyroX: Float, screenGyroY: Float): Float {
        val x = relative[2]
        val z = relative[8]
        val denominator = x * x + z * z
        if (denominator < 0.01f) return 0f
        val dx = relative[0] * screenGyroY - relative[1] * screenGyroX
        val dz = relative[6] * screenGyroY - relative[7] * screenGyroX
        return (z * dx - x * dz) / denominator
    }

    /** Derivative of atan2(-normal.y, normal.z), including simultaneous yaw/roll. */
    fun verticalTiltRate(relative: FloatArray, screenGyroX: Float, screenGyroY: Float): Float {
        val y = relative[5]
        val z = relative[8]
        val denominator = y * y + z * z
        if (denominator < 0.01f) return 0f
        val dy = relative[3] * screenGyroY - relative[4] * screenGyroX
        val dz = relative[6] * screenGyroY - relative[7] * screenGyroX
        return (y * dz - z * dy) / denominator
    }

    fun predictionSeconds(
        ageSeconds: Float,
        refreshHz: Float,
        maxSeconds: Float = 0.025f
    ): Float = (ageSeconds.coerceAtLeast(0f) + 0.5f / refreshHz.coerceIn(30f, 240f))
        .coerceAtMost(maxSeconds.coerceAtLeast(0f))
}
