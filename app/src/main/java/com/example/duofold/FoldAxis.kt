package com.example.duofold

/** Which screen edge the glass is hinged on. */
enum class FoldAxis(val shaderValue: Float) {
    /** Hinge on the left or right edge; tilt follows left/right rotation. */
    Horizontal(0f),

    /** Hinge on the top or bottom edge; tilt follows forward/back rotation. */
    Vertical(1f)
}

/** How the active fold axis is chosen. */
enum class FoldAxisMode {
    Auto,
    Horizontal,
    Vertical
}
