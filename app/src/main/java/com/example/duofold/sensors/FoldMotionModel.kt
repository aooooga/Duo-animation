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
    private var filteredHorizontal = 0f
    private var filteredVertical = 0f
    private var baselineHorizontal = 0f
    private var baselineVertical = 0f
    private var maxTiltRad = Math.toRadians(45.0).toFloat()
    private var smoothingSeconds = 0.010f
    private var predictionSeconds = 0.025f
    private var recenter = false
    private var started = false
    private var enabled = true
    private var resumed = false
    private var count = 0
    private var reportTimestamp = 0L

    private val _horizontalDegrees = MutableStateFlow(0f)
    val horizontalDegrees = _horizontalDegrees.asStateFlow()
    private val _verticalDegrees = MutableStateFlow(0f)
    val verticalDegrees = _verticalDegrees.asStateFlow()
    private val _hasSensor = MutableStateFlow(rotationSensor != null)
    val hasSensor = _hasSensor.asStateFlow()
    private val _diagnostics = MutableStateFlow("Waiting for sensor samples")
    val diagnostics = _diagnostics.asStateFlow()

    val sensorDescription: String =
        "${rotationSensor?.name ?: "None"} (${rotationSensor?.vendor ?: "unknown"})\n" +
        "${gyroSensor?.name ?: "No gyro"} (${gyroSensor?.vendor ?: "unknown"})"

    fun setResumed(value: Boolean) { resumed = value; updateRegistration() }
    fun setEnabled(value: Boolean) { enabled = value; updateRegistration() }

    /** Live tuning from Settings; all values are clamped to safe ranges. */
    fun setTuning(maxTiltDegrees: Float, smoothingMs: Float, predictionMs: Float) {
        maxTiltRad = Math.toRadians(maxTiltDegrees.coerceIn(1f, 89f).toDouble()).toFloat()
        smoothingSeconds = (smoothingMs / 1000f).coerceIn(0f, 0.2f)
        predictionSeconds = (predictionMs / 1000f).coerceIn(0f, 0.1f)
    }

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
        baselineHorizontal = 0f
        baselineVertical = 0f
        filteredHorizontal = 0f
        filteredVertical = 0f
        _horizontalDegrees.value = 0f
        _verticalDegrees.value = 0f
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
            baselineHorizontal = 0f
            baselineVertical = 0f
            filteredHorizontal = 0f
            filteredVertical = 0f
            _horizontalDegrees.value = 0f
            _verticalDegrees.value = 0f
            return
        }
        MotionMath.relative(reference, screen, relative)

        // Each axis is undefined when the screen normal lies along it. Keep the
        // last valid angle for that axis instead of emitting a 90-degree spike.
        val horizontalValid = relative[2] * relative[2] + relative[8] * relative[8] >= 0.01f
        val verticalValid = relative[5] * relative[5] + relative[8] * relative[8] >= 0.01f
        val measuredHorizontal = MotionMath.tilt(relative)
        val measuredVertical = MotionMath.verticalTilt(relative)
        val gyroFresh = gyroTimestamp != 0L &&
            abs(event.timestamp - gyroTimestamp) <= 30_000_000L
        var predictedHorizontal = measuredHorizontal
        var predictedVertical = measuredVertical
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
            val horizon = MotionMath.predictionSeconds(age, refreshRate(), predictionSeconds)
            predictedHorizontal += MotionMath.tiltRate(relative, gx, gy) * horizon
            predictedVertical += MotionMath.verticalTiltRate(relative, gx, gy) * horizon
        }
        // Optional drift correction only near neutral, never erase a held tilt.
        if (recenter && gyroFresh && horizontalValid && verticalValid &&
            gyro.all { abs(it) < 0.025f } &&
            abs(MotionMath.wrap(measuredHorizontal - baselineHorizontal)) < Math.toRadians(2.0) &&
            abs(MotionMath.wrap(measuredVertical - baselineVertical)) < Math.toRadians(2.0)) {
            val factor = (dt / 30f).coerceIn(0f, 1f)
            baselineHorizontal += MotionMath.wrap(measuredHorizontal - baselineHorizontal) * factor
            baselineVertical += MotionMath.wrap(measuredVertical - baselineVertical) * factor
        }
        if (horizontalValid) {
            val target = MotionMath.wrap(predictedHorizontal - baselineHorizontal)
                .coerceIn(-maxTiltRad, maxTiltRad)
            filteredHorizontal += MotionMath.wrap(target - filteredHorizontal) *
                MotionMath.smoothing(dt, smoothingSeconds)
            _horizontalDegrees.value = Math.toDegrees(filteredHorizontal.toDouble()).toFloat()
        }
        if (verticalValid) {
            val target = MotionMath.wrap(predictedVertical - baselineVertical)
                .coerceIn(-maxTiltRad, maxTiltRad)
            filteredVertical += MotionMath.wrap(target - filteredVertical) *
                MotionMath.smoothing(dt, smoothingSeconds)
            _verticalDegrees.value = Math.toDegrees(filteredVertical.toDouble()).toFloat()
        }
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
}
