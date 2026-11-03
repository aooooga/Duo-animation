package com.example.duofold

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HardwareTest {
    @Test fun shaderCompilesWithDeviceSkiaAndAllUniforms() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = context.resources.openRawResource(R.raw.duo_fold).bufferedReader().use { it.readText() }
        // Single-axis and diagonal pairs; every combination must bind all uniforms.
        for ((horizontal, vertical) in listOf(30f to 0f, 0f to 30f, 30f to 20f, -30f to -20f)) {
            for (perspective in listOf(0f, 0.6f, 1f)) {
                val shader = RuntimeShader(source)
                shader.setFloatUniform("resolution", 1220f, 2712f)
                shader.setFloatUniform("tiltHorizontalDegrees", horizontal)
                shader.setFloatUniform("tiltVerticalDegrees", vertical)
                shader.setFloatUniform("eyeDistancePx", 7895f)
                shader.setFloatUniform("maxTiltDegrees", 45f)
                shader.setFloatUniform("perspective", perspective)
                shader.setFloatUniform("blurSpread", 0.12f)
                shader.setFloatUniform("darkening", 0.00513f)
                assertNotNull(RenderEffect.createRuntimeShaderEffect(shader, "content"))
            }
        }
    }

    @Test fun measureActualFusionDelivery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = instrumentation.targetContext.getSystemService(SensorManager::class.java)
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        assertNotNull(sensor)
        val worker = HandlerThread("sensor-test").apply { start() }
        val done = CountDownLatch(1)
        val timestamps = mutableListOf<Long>()
        val ages = mutableListOf<Float>()
        var finite = true
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                timestamps.add(event.timestamp)
                ages.add((SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1e6f)
                finite = finite && event.values.all { it.isFinite() }
                if (timestamps.size > 1 && event.timestamp - timestamps.first() >= 3_000_000_000L) done.countDown()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        try {
            assertTrue(manager.registerListener(listener, sensor, 5000, 0, Handler(worker.looper)))
            assertTrue("No 3-second sensor trace", done.await(10, TimeUnit.SECONDS))
        } finally {
            manager.unregisterListener(listener)
            worker.quitSafely()
            worker.join()
        }
        assertTrue(finite)
        val elapsed = (timestamps.last() - timestamps.first()) / 1e9
        val hz = (timestamps.size - 1) / elapsed
        val intervals = timestamps.zipWithNext { a, b -> (b - a) / 1e6 }
        val report = "${sensor!!.name}/${sensor.vendor}: %.1f Hz, mean delivery %.2f ms, interval %.2f..%.2f ms".format(
            hz, ages.average(), intervals.min(), intervals.max())
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$report\n") })
        assertTrue("Insufficient samples: $report", hz > 40.0)
    }
}
