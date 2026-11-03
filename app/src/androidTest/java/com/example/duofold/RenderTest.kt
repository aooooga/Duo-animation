package com.example.duofold

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real GPU rendering at native panel resolution, including with display off. */
@RunWith(AndroidJUnit4::class)
class RenderTest {
    private data class Shot(
        val horizontal: Float,
        val vertical: Float,
        val name: String,
        val perspective: Float = 0.6f
    )

    @Test fun rendersBothAxesOnDeviceGpu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val width = 1220
        val height = 2712
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = HardwareRenderer()
        val scene = RenderNode("quality-test").apply { setPosition(0, 0, width, height) }
        val canvas = scene.beginRecording()
        canvas.drawColor(Color.WHITE)
        val paint = Paint()
        for (i in 0..11) {
            paint.color = if (i % 2 == 0) Color.rgb(25, 100, 235) else Color.rgb(240, 150, 30)
            canvas.drawRect(i * width / 12f, height * 0.1f,
                (i + 1) * width / 12f, height * 0.9f, paint)
        }
        scene.endRecording()
        val root = RenderNode("root").apply { setPosition(0, 0, width, height) }
        root.beginRecording().drawRenderNode(scene)
        root.endRecording()
        renderer.setSurface(reader.surface)
        renderer.setContentRoot(root)
        val shots = listOf(
            Shot(0f, 0f, "horizontal_0"),
            Shot(5f, 0f, "horizontal_5"),
            Shot(-30f, 0f, "horizontal_-30"),
            Shot(30f, 0f, "horizontal_30"),
            Shot(30f, 0f, "horizontal_30_full", perspective = 1f),
            Shot(0f, 5f, "vertical_5"),
            Shot(0f, 30f, "vertical_30"),
            Shot(0f, -30f, "vertical_-30"),
            Shot(0f, 30f, "vertical_30_frost_only", perspective = 0f),
            Shot(3f, 2f, "diagonal_3_2"),
            Shot(30f, 20f, "diagonal_30_20"),
            Shot(30f, 20f, "diagonal_30_20_full", perspective = 1f),
            Shot(-30f, -20f, "diagonal_-30_-20"),
            Shot(30f, -20f, "diagonal_30_-20"),
            Shot(45f, 45f, "diagonal_45_45")
        )
        val images = mutableListOf<Bitmap>()
        try {
            for (shot in shots) {
                val source = context.resources.openRawResource(R.raw.duo_fold)
                    .bufferedReader().use { it.readText() }
                val shader = RuntimeShader(source)
                shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
                shader.setFloatUniform("tiltHorizontalDegrees", shot.horizontal)
                shader.setFloatUniform("tiltVerticalDegrees", shot.vertical)
                shader.setFloatUniform("eyeDistancePx", 450f * 445.61404f / 25.4f)
                shader.setFloatUniform("maxTiltDegrees", 45f)
                shader.setFloatUniform("perspective", shot.perspective)
                shader.setFloatUniform("blurSpread", 0.12f)
                shader.setFloatUniform("darkening", 0.015f * 6f * 25.4f / 445.61404f)
                scene.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                val image = checkNotNull(reader.acquireNextImage()) { "GPU produced no image" }
                val bitmap = image.use {
                    checkNotNull(it.hardwareBuffer).use { buffer ->
                        val hardware = checkNotNull(
                            Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
                        )
                        val copy = checkNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false))
                        hardware.recycle()
                        copy
                    }
                }
                val directory = File(context.getExternalFilesDir(null), "validation").apply { mkdirs() }
                File(directory, "tilt_${shot.name}.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                images.add(bitmap)
            }
            val original = images.first()
            for (image in images.drop(1)) {
                var changed = 0
                for (x in 100 until width - 100 step 40) {
                    for (y in 100 until height - 100 step 40) {
                        if (image.getPixel(x, y) != original.getPixel(x, y)) changed++
                    }
                }
                assertTrue("Effect did not render", changed > 20)
                assertTrue("Effect rendered a black screen",
                    (image.getPixel(width / 2, height / 2) and 0xffffff) != 0)
            }
        } finally {
            images.forEach { it.recycle() }
            renderer.destroy()
            reader.close()
            scene.discardDisplayList()
            root.discardDisplayList()
        }
    }
}
