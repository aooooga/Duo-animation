package com.example.duofold.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max

/** Uses the phone's calibrated fusion and gyro, never vendor axis/sign guesses. */
class FoldMotionModel(
    context: Context,
    private val displayRotation: () -> Int,
    private val refreshRate: () -> Float
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val rotationSensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroSensor = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val raw = FloatArray(9)
    private val screen = FloatArray(9)
    private val reference = FloatArray(9)
    private val relative = FloatArray(9)
    private val gyro = FloatArray(3)
    private var gyroTimestamp = 0L
    private var lastTimestamp = 0L
    private var lastRotation = -1
    private var needsReference = true
    private var filtered = 0f
    private var baseline = 0f
    private var recenter = false
    private var started = false
    private var enabled = true
    private var resumed = false
    private var count = 0
    private var reportTimestamp = 0L

    private val _tiltDegrees = MutableStateFlow(0f)
    val tiltDegrees = _tiltDegrees.asStateFlow()
    private val _hasSensor = MutableStateFlow(rotationSensor != null)
    val hasSensor = _hasSensor.asStateFlow()
    private val _diagnostics = MutableStateFlow("Waiting for sensor samples")
    val diagnostics = _diagnostics.asStateFlow()

    val sensorDescription: String =
        "${rotationSensor?.name ?: "None"} (${rotationSensor?.vendor ?: "unknown"})\n" +
        "${gyroSensor?.name ?: "No gyro"} (${gyroSensor?.vendor ?: "unknown"})"

    fun setResumed(value: Boolean) { resumed = value; updateRegistration() }
    fun setEnabled(value: Boolean) { enabled = value; updateRegistration() }

    private fun updateRegistration() {
        val wanted = resumed && enabled
        if (wanted == started) return
        if (!wanted) {
            manager.unregisterListener(this)
            started = false
            return
        }
        recalibrate()
        // rodin's MediaTek fusion supports 200 Hz; ask for that precision with
        // zero batching. Respect slower variants and Android's 200 Hz cap.
        val sensor = rotationSensor
        started = sensor != null && manager.registerListener(
            this, sensor, max(5_000, sensor.minDelay), 0
        )
        _hasSensor.value = started
        if (started) gyroSensor?.let {
            manager.registerListener(this, it, max(5_000, it.minDelay), 0)
        }
    }

    fun recalibrate() {
        needsReference = true
        gyroTimestamp = 0L
        lastTimestamp = 0L
        reportTimestamp = 0L
        count = 0
        baseline = 0f
        filtered = 0f
        _tiltDegrees.value = 0f
    }

    fun setAutoRecenterEnabled(value: Boolean) {
        recenter = value
        // Baseline stays intact when toggling: switching must not snap the glass.
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!started || event.values.any { !it.isFinite() }) return
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            event.values.copyInto(gyro, endIndex = 3)
            gyroTimestamp = event.timestamp
            return
        }
        if (event.sensor.type != rotationSensor?.type) return
        if (event.timestamp <= lastTimestamp) return
        val rotation = displayRotation()
        SensorManager.getRotationMatrixFromVector(raw, event.values)
        val axes = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(raw, axes.first, axes.second, screen)
        val dt = if (lastTimestamp == 0L) 0.005f else
            (event.timestamp - lastTimestamp) / 1e9f
        lastTimestamp = event.timestamp
        if (needsReference || rotation != lastRotation || dt > 0.25f) {
            screen.copyInto(reference)
            needsReference = false
            lastRotation = rotation
            baseline = 0f
            filtered = 0f
            _tiltDegrees.value = 0f
            return
        }
        MotionMath.relative(reference, screen, relative)
        // At edge-on pitch, heading is undefined: retain the last valid angle.
        if (relative[2] * relative[2] + relative[8] * relative[8] < 0.01f) return
        val measured = MotionMath.tilt(relative)
        val gyroFresh = gyroTimestamp != 0L &&
            abs(event.timestamp - gyroTimestamp) <= 30_000_000L
        var predicted = measured
        if (gyroFresh) {
            val gx = when (rotation) {
                Surface.ROTATION_90 -> gyro[1]
                Surface.ROTATION_180 -> -gyro[0]
                Surface.ROTATION_270 -> -gyro[1]
                else -> gyro[0]
            }
            val gy = when (rotation) {
                Surface.ROTATION_90 -> -gyro[0]
                Surface.ROTATION_180 -> -gyro[1]
                Surface.ROTATION_270 -> gyro[0]
                else -> gyro[1]
            }
            val age = (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1e9f
            predicted += MotionMath.tiltRate(relative, gx, gy) *
                MotionMath.predictionSeconds(age, refreshRate())
        }
        // Optional drift correction only near neutral, never erase a held tilt.
        if (recenter && gyroFresh && gyro.all { abs(it) < 0.025f } &&
            abs(MotionMath.wrap(measured - baseline)) < Math.toRadians(2.0)) {
            baseline += MotionMath.wrap(measured - baseline) * (dt / 30f).coerceIn(0f, 1f)
        }
        val target = MotionMath.wrap(predicted - baseline).coerceIn(-LIMIT_RAD, LIMIT_RAD)
        filtered += MotionMath.wrap(target - filtered) * MotionMath.smoothing(dt)
        _tiltDegrees.value = Math.toDegrees(filtered.toDouble()).toFloat()
        count++
        if (reportTimestamp == 0L) reportTimestamp = event.timestamp
        val interval = (event.timestamp - reportTimestamp) / 1e9f
        if (interval >= 0.5f) {
            val ageMs = (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1e6f
            _diagnostics.value = "%.0f Hz · %.1f ms delivery · %.0f Hz display".format(
                count / interval, ageMs, refreshRate())
            count = 0
            reportTimestamp = event.timestamp
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val MAX_TILT = 45f
        private val LIMIT_RAD = Math.toRadians(MAX_TILT.toDouble()).toFloat()
    }
}
