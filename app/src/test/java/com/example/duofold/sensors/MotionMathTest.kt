package com.example.duofold.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class MotionMathTest {
    private fun y(angle: Float): FloatArray {
        val c = cos(angle); val s = sin(angle)
        return floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
    }

    @Test fun calibrationSubtractsReferenceAndPreservesSign() {
        val out = FloatArray(9)
        for (degrees in listOf(-45, -20, 0, 20, 45)) {
            val reference = y(0.8f)
            val delta = Math.toRadians(degrees.toDouble()).toFloat()
            MotionMath.relative(reference, y(0.8f + delta), out)
            assertEquals(delta, MotionMath.tilt(out), 0.00001f)
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

    @Test fun gyroPredictionMatchesYawAndRejectsEdgeOnSingularity() {
        assertEquals(0.8f, MotionMath.tiltRate(y(0.7f), 0f, 0.8f), 0.00001f)
        val singular = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        assertEquals(0f, MotionMath.tiltRate(singular, 0f, 2f), 0f)
    }

    @Test fun predictionUsesDeliveryAgeAndRefreshWithoutFortyMillisecondOvershoot() {
        assertEquals(0.006166667f, MotionMath.predictionSeconds(0.002f, 120f), 0.00001f)
        assertEquals(0.025f, MotionMath.predictionSeconds(0.5f, 60f), 0f)
        assertTrue(MotionMath.predictionSeconds(0f, 60f) > MotionMath.predictionSeconds(0f, 120f))
    }

    @Test fun wrapTakesShortPathAcrossDiscontinuity() {
        assertEquals(-0.02f, MotionMath.wrap((2 * Math.PI - 0.02).toFloat()), 0.00001f)
    }
}
