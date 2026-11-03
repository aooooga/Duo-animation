package com.example.duofold.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class MotionMathTest {
    private fun yaw(angle: Float): FloatArray {
        val c = cos(angle); val s = sin(angle)
        return floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
    }

    private fun pitch(angle: Float): FloatArray {
        val c = cos(angle); val s = sin(angle)
        return floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c)
    }

    @Test fun calibrationSubtractsReferenceAndPreservesSign() {
        val out = FloatArray(9)
        for (degrees in listOf(-45, -20, 0, 20, 45)) {
            val reference = yaw(0.8f)
            val delta = Math.toRadians(degrees.toDouble()).toFloat()
            MotionMath.relative(reference, yaw(0.8f + delta), out)
            assertEquals(delta, MotionMath.tilt(out), 0.00001f)
        }
    }

    @Test fun verticalCalibrationSubtractsReferenceAndPreservesSign() {
        val out = FloatArray(9)
        for (degrees in listOf(-45, -20, 0, 20, 45)) {
            val reference = pitch(0.3f)
            val delta = Math.toRadians(degrees.toDouble()).toFloat()
            MotionMath.relative(reference, pitch(0.3f + delta), out)
            assertEquals(delta, MotionMath.verticalTilt(out), 0.00001f)
        }
    }

    @Test fun equalElapsedTimeHasEqualSmoothingAtAllSamplingRates() {
        val results = listOf(50, 100, 200).map { hz ->
            var filtered = 0f
            repeat(hz / 10) { filtered += (1f - filtered) * MotionMath.smoothing(1f / hz) }
            filtered
        }
        results.forEach { assertEquals(results.first(), it, 0.00001f) }
    }

    @Test fun zeroSmoothingTimeIsInstant() {
        assertEquals(1f, MotionMath.smoothing(0.005f, 0f), 0f)
    }

    @Test fun gyroPredictionMatchesYawAndRejectsEdgeOnSingularity() {
        assertEquals(0.8f, MotionMath.tiltRate(yaw(0.7f), 0f, 0.8f), 0.00001f)
        val singular = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        assertEquals(0f, MotionMath.tiltRate(singular, 0f, 2f), 0f)
    }

    @Test fun verticalGyroPredictionMatchesPitchAndRejectsSingularity() {
        assertEquals(0.8f, MotionMath.verticalTiltRate(pitch(0.3f), 0.8f, 0f), 0.00001f)
        val singular = floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f, -1f, 0f, 0f)
        assertEquals(0f, MotionMath.verticalTiltRate(singular, 2f, 0f), 0f)
    }

    @Test fun predictionUsesDeliveryAgeAndRefreshWithoutFortyMillisecondOvershoot() {
        assertEquals(0.006166667f, MotionMath.predictionSeconds(0.002f, 120f), 0.00001f)
        assertEquals(0.025f, MotionMath.predictionSeconds(0.5f, 60f), 0f)
        assertTrue(MotionMath.predictionSeconds(0f, 60f) > MotionMath.predictionSeconds(0f, 120f))
    }

    @Test fun predictionHonoursCustomCap() {
        assertEquals(0.010f, MotionMath.predictionSeconds(0.5f, 60f, 0.010f), 0f)
        assertEquals(0.010f, MotionMath.predictionSeconds(0.008f, 120f, 0.010f), 0.00001f)
    }

    @Test fun wrapTakesShortPathAcrossDiscontinuity() {
        assertEquals(-0.02f, MotionMath.wrap((2 * Math.PI - 0.02).toFloat()), 0.00001f)
    }

    @Test fun combinedTiltKeepsBothAxesIndependent() {
        // Simultaneous yaw+pitch must report both angles; Euler extraction
        // couples them by a fraction of a degree, so allow 0.05 rad.
        val out = FloatArray(9)
        val yawAngle = Math.toRadians(30.0).toFloat()
        val pitchAngle = Math.toRadians(-20.0).toFloat()
        val reference = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val current = mul(pitch(pitchAngle), yaw(yawAngle))
        MotionMath.relative(reference, current, out)
        assertTrue(abs(MotionMath.tilt(out) - yawAngle) < 0.05f)
        assertTrue(abs(MotionMath.verticalTilt(out) - pitchAngle) < 0.05f)
        assertTrue(MotionMath.tilt(out) > 0.1f)
        assertTrue(MotionMath.verticalTilt(out) < -0.1f)
    }

    private fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (r in 0..2) for (c in 0..2) {
            out[r * 3 + c] = a[r * 3] * b[c] +
                a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }
        return out
    }
}
