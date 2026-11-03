package com.example.duofold

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import android.view.Surface
import com.example.duofold.sensors.FoldMotionModel
import com.example.duofold.ui.ContentView

class MainActivity : ComponentActivity() {
    private lateinit var motionModel: FoldMotionModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fullscreen edge-to-edge: transparent status + nav bars with the UI
        // drawing behind them. Light style = dark icons, matching our light UI.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        motionModel = FoldMotionModel(
            applicationContext,
            displayRotation = { display?.rotation ?: Surface.ROTATION_0 },
            refreshRate = { display?.refreshRate ?: 60f }
        )
        // A window preference, not a global HyperOS setting. The OS retains
        // control under battery saver / thermal limits.
        val bestRate = display?.supportedModes?.filter {
            it.physicalWidth == display?.mode?.physicalWidth &&
                it.physicalHeight == display?.mode?.physicalHeight
        }?.maxOfOrNull { it.refreshRate.coerceAtMost(120f) } ?: 60f
        window.attributes = window.attributes.apply { preferredRefreshRate = bestRate }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ContentView(motionModel = motionModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        motionModel.setResumed(true)
    }

    override fun onPause() {
        motionModel.setResumed(false)
        super.onPause()
    }
}
