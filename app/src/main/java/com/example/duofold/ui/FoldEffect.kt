package com.example.duofold.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.example.duofold.R

/** Resolve physical pixel pitch, never Android's user-adjustable logical DPI. */
internal fun pixelsPerMillimeter(xdpi: Float, device: String): Float = when {
    xdpi.isFinite() && xdpi in 200f..1000f -> xdpi / 25.4f
    // Measured via DisplayInfo on the owner's rodin; only a fallback if its
    // firmware stops reporting physical DPI. Do not apply to unrelated phones.
    device == "rodin" -> 445.61404f / 25.4f
    else -> 6f
}

fun Modifier.foldEffect(
    horizontalTiltDegrees: Float,
    verticalTiltDegrees: Float,
    maxTiltDegrees: Float = 45f,
    parameters: FoldParameters = FoldParameters(),
    onShaderError: (String?) -> Unit = {}
): Modifier = composed {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val pitch = remember(configuration) {
        pixelsPerMillimeter(context.resources.displayMetrics.xdpi, Build.DEVICE)
    }
    val pxPerMm = parameters.pixelsPerMillimeter.takeIf { it.isFinite() && it > 0f } ?: pitch
    // One shader per layer owner. Uniforms are mutated only on the UI thread.
    // RenderEffect snapshots uniforms, so recreate that wrapper after uploads,
    // but never recompile the RuntimeShader on every sensor event.
    val shader = remember(context) {
        runCatching {
            val source = context.resources.openRawResource(R.raw.duo_fold)
                .bufferedReader().use { it.readText() }
            RuntimeShader(source)
        }.onFailure { Log.e("FoldEffect", "AGSL compilation failed", it) }.getOrNull()
    }
    LaunchedEffect(shader) {
        onShaderError(if (shader == null) "Glass effect unavailable: shader compilation failed" else null)
    }
    graphicsLayer {
        clip = true
        val horizontal = horizontalTiltDegrees.takeIf { it.isFinite() } ?: 0f
        val vertical = verticalTiltDegrees.takeIf { it.isFinite() } ?: 0f
        if (shader == null || size.width <= 1f || size.height <= 1f ||
            (kotlin.math.abs(horizontal) < 0.001f && kotlin.math.abs(vertical) < 0.001f)) {
            renderEffect = null
        } else {
            shader.setFloatUniform("resolution", size.width, size.height)
            shader.setFloatUniform("tiltHorizontalDegrees", horizontal)
            shader.setFloatUniform("tiltVerticalDegrees", vertical)
            shader.setFloatUniform("eyeDistancePx", parameters.eyeDistanceMillimeters * pxPerMm)
            shader.setFloatUniform("maxTiltDegrees", maxTiltDegrees)
            shader.setFloatUniform("parallax", parameters.parallax)
            shader.setFloatUniform("blurSpread", parameters.blurSpread)
            shader.setFloatUniform("darkening", parameters.darkening * 6f / pxPerMm)
            renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect()
        }
    }
}
