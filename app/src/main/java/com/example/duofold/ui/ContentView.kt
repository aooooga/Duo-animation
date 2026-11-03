package com.example.duofold.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.duofold.sensors.FoldMotionModel

/**
 * Composition root: [DemoContentView] wrapped in [foldEffect], with every
 * optical and motion value exposed in Settings. Both axes fold at once about
 * the screen centre as one rigid rotation, matching a phone turned in the hand;
 * Perspective scales the projective tilt compensation without replacing it.
 */
@Composable
fun ContentView(motionModel: FoldMotionModel) {
    val sensorHorizontal by motionModel.horizontalDegrees.collectAsState()
    val sensorVertical by motionModel.verticalDegrees.collectAsState()
    val diagnostics by motionModel.diagnostics.collectAsState()
    val hasSensor by motionModel.hasSensor.collectAsState()

    val context = LocalContext.current
    val displayPitch = remember(context) {
        pixelsPerMillimeter(context.resources.displayMetrics.xdpi, Build.DEVICE)
    }

    var manualHorizontal by rememberSaveable { mutableFloatStateOf(0f) }
    var manualVertical by rememberSaveable { mutableFloatStateOf(0f) }
    var viewingDistance by rememberSaveable { mutableFloatStateOf(450f) }
    var maxTilt by rememberSaveable { mutableFloatStateOf(45f) }
    var smoothingMs by rememberSaveable { mutableFloatStateOf(10f) }
    var predictionMs by rememberSaveable { mutableFloatStateOf(25f) }
    var blurSpread by rememberSaveable { mutableFloatStateOf(0.12f) }
    var darkening by rememberSaveable { mutableFloatStateOf(0.015f) }
    var perspective by rememberSaveable { mutableFloatStateOf(0.6f) }
    var autoPitch by rememberSaveable { mutableStateOf(true) }
    var pitchOverride by rememberSaveable { mutableFloatStateOf(displayPitch) }
    // Default to manual control when no rotation-vector sensor exists.
    var useSensor by remember(hasSensor) { mutableStateOf(hasSensor) }
    var showSettings by remember { mutableStateOf(false) }
    var shaderError by remember { mutableStateOf<String?>(null) }
    var autoRecenter by rememberSaveable { mutableStateOf(false) }

    val effectiveUseSensor = useSensor && hasSensor
    LaunchedEffect(effectiveUseSensor) { motionModel.setEnabled(effectiveUseSensor) }
    LaunchedEffect(autoRecenter) { motionModel.setAutoRecenterEnabled(autoRecenter) }
    LaunchedEffect(maxTilt, smoothingMs, predictionMs) {
        motionModel.setTuning(maxTilt, smoothingMs, predictionMs)
    }

    val horizontal = if (effectiveUseSensor) sensorHorizontal else manualHorizontal
    val vertical = if (effectiveUseSensor) sensorVertical else manualVertical
    val awayHorizontal = if (horizontal >= 0f) "right" else "left"
    val awayVertical = if (vertical >= 0f) "bottom" else "top"

    val params = remember(viewingDistance, autoPitch, pitchOverride, blurSpread, darkening, perspective) {
        FoldParameters(
            eyeDistanceMillimeters = viewingDistance,
            pixelsPerMillimeter = if (autoPitch) 0f else pitchOverride,
            blurSpread = blurSpread,
            darkening = darkening,
            perspective = perspective
        )
    }

    fun nudgeManual(horizontalValue: Float? = null, verticalValue: Float? = null) {
        if (effectiveUseSensor) {
            // Drop to manual without a jump: keep both current sensor angles.
            manualHorizontal = sensorHorizontal
            manualVertical = sensorVertical
            useSensor = false
        }
        horizontalValue?.let { manualHorizontal = it }
        verticalValue?.let { manualVertical = it }
    }

    Box(Modifier.fillMaxSize()) {
        // Folded content. On pre-33 (previews only — minSdk is 33) show it plain.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            DemoContentView(
                Modifier.foldEffect(
                    horizontalTiltDegrees = horizontal,
                    verticalTiltDegrees = vertical,
                    maxTiltDegrees = maxTilt,
                    parameters = params,
                    onShaderError = { shaderError = it }
                ),
                onCalibrateClick = { motionModel.recalibrate() },
                onSettingsClick = { showSettings = true }
            )
        } else {
            DemoContentView(
                Modifier,
                onCalibrateClick = { motionModel.recalibrate() },
                onSettingsClick = { showSettings = true }
            )
        }

        shaderError?.let { Text(it, color = Color.Red, modifier = Modifier.align(Alignment.BottomCenter)) }

        if (showSettings) {
            BackHandler { showSettings = false }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.34f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 12.dp
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Settings",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showSettings = false }) {
                            Text("Done")
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(motionModel.sensorDescription, style = MaterialTheme.typography.labelSmall)
                        Text(diagnostics, style = MaterialTheme.typography.labelSmall)
                        Text(
                            "Horizontal %+.1f° (%s side farther)  ·  Vertical %+.1f° (%s side farther)".format(
                                horizontal, awayHorizontal, vertical, awayVertical
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            "The glass rotates about its centre; both axes combine into one rigid rotation. Perspective scales how strongly the projection compensates the phone's tilt.",
                            style = MaterialTheme.typography.labelSmall
                        )

                        SectionTitle("Tilt")
                        TuningSlider(
                            "Horizontal tilt",
                            horizontal,
                            -maxTilt..maxTilt,
                            "%.1f°".format(horizontal)
                        ) { nudgeManual(horizontalValue = it) }
                        TuningSlider(
                            "Vertical tilt",
                            vertical,
                            -maxTilt..maxTilt,
                            "%.1f°".format(vertical)
                        ) { nudgeManual(verticalValue = it) }
                        Text(
                            "Manual sliders take over from the sensor when dragged.",
                            style = MaterialTheme.typography.labelSmall
                        )

                        SectionTitle("Optics")
                        TuningSlider(
                            "Viewing distance",
                            viewingDistance,
                            200f..2000f,
                            "%.0f mm".format(viewingDistance)
                        ) { viewingDistance = it }
                        TuningSlider(
                            "Perspective",
                            perspective,
                            0f..1f,
                            "%.2f".format(perspective)
                        ) { perspective = it }
                        Text(
                            "1.00 is full tilt compensation; 0 keeps only the frost.",
                            style = MaterialTheme.typography.labelSmall
                        )
                        TuningSlider(
                            "Blur spread",
                            blurSpread,
                            0f..1.50f,
                            "%.3f".format(blurSpread)
                        ) { blurSpread = it }
                        TuningSlider(
                            "Darkening",
                            darkening,
                            0f..0.30f,
                            "%.4f".format(darkening)
                        ) { darkening = it }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Auto pixel pitch (%.2f px/mm)".format(displayPitch),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(checked = autoPitch, onCheckedChange = { autoPitch = it })
                        }
                        if (!autoPitch) {
                            TuningSlider(
                                "Pixel pitch",
                                pitchOverride,
                                1f..40f,
                                "%.2f px/mm".format(pitchOverride)
                            ) { pitchOverride = it }
                        }

                        SectionTitle("Motion")
                        TuningSlider(
                            "Maximum tilt",
                            maxTilt,
                            5f..80f,
                            "%.0f°".format(maxTilt)
                        ) { maxTilt = it }
                        TuningSlider(
                            "Smoothing",
                            smoothingMs,
                            0f..50f,
                            "%.0f ms".format(smoothingMs)
                        ) { smoothingMs = it }
                        TuningSlider(
                            "Prediction",
                            predictionMs,
                            0f..50f,
                            "%.0f ms".format(predictionMs)
                        ) { predictionMs = it }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (effectiveUseSensor) "Sensor" else "Manual",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = effectiveUseSensor,
                                onCheckedChange = { useSensor = it },
                                enabled = hasSensor
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Auto-calibrate near neutral",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = autoRecenter,
                                onCheckedChange = { autoRecenter = it }
                            )
                        }

                        if (!hasSensor) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "No rotation sensor — manual mode",
                                color = Color(0xFFFDB022),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    viewingDistance = 450f
                                    maxTilt = 45f
                                    smoothingMs = 10f
                                    predictionMs = 25f
                                    blurSpread = 0.12f
                                    darkening = 0.015f
                                    perspective = 0.6f
                                    autoPitch = true
                                    pitchOverride = displayPitch
                                    autoRecenter = false
                                    manualHorizontal = 0f
                                    manualVertical = 0f
                                }
                            ) { Text("Reset tuning") }
                            Button(
                                onClick = {
                                    motionModel.recalibrate()
                                    manualHorizontal = 0f
                                    manualVertical = 0f
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Recalibrate")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun TuningSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit
) {
    Text("$label: $display", style = MaterialTheme.typography.labelMedium)
    Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
}
