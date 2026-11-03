# POCO X7 Pro validation — 2026-11-03

This fork targets motion and optical quality. It does not trade lower resolution
or fewer blur samples for performance, and does not claim a measured frame-rate
improvement.

## Connected hardware

Read directly using ADB, SensorManager and SurfaceFlinger:

| Item | Observed |
| --- | --- |
| Model / device | 2412DPC0AG / rodin |
| Firmware | Android 16, OS3.0.302.0.WOJMIXM |
| IMU | ST lsm6dsvq_acc / lsm6dsvq_gyro |
| Fusion | MediaTek game_rotvec, max 200 Hz |
| Magnetometer | QST qmc6308; not used by the selected game rotation vector |
| Display | 1220 × 2712; physical 445.61404 dpi; logical 520 dpi |
| Display modes | 30, 60, 90, 120 Hz |
| GPU | ARM Mali-G720 MC7, OpenGL ES 3.2 r49p1 |
| Memory page size | 4096 bytes |

The measured pixel pitch agrees with the manufacturer's 6.67-inch native panel
specification. There is no evidence that upstream contains Samsung-specific
sensor calibration. It already selects game rotation vector, which is appropriate
for this device; the corrections concern its downstream motion processing.

## Tests

`testDebugUnitTest`: ten deterministic regression tests passed:

- Relative orientation subtracts calibration and preserves left/right signs.
- Vertical relative orientation subtracts calibration and preserves up/down signs.
- Filtering over equal elapsed time agrees at 50, 100 and 200 Hz.
- Zero smoothing time updates instantly.
- Gyro prediction agrees with yaw and handles an edge-on singularity.
- Vertical gyro prediction agrees with pitch and handles its singularity.
- Prediction follows delivery age / refresh interval and is bounded.
- Prediction honours a custom cap.
- Angle wrapping follows the short path across the discontinuity.
- Simultaneous yaw and pitch report both axes without one hiding the other.

On-device instrumentation: three tests passed on the connected phone:

- AGSL compiles and accepts every uniform for single-axis and diagonal tilt pairs at perspective **0, 0.6 and 1** in the phone's Skia runtime.
- `game_rotvec` delivered **200.1 Hz** over a three-second trace, with
  **4.17 ms** mean delivery age and **5.00–5.00 ms** timestamp intervals at the
  report's two-decimal precision. This measures delivery, not fusion latency
  relative to physical ground truth.
- `HardwareRenderer` rendered the shader into native-resolution GPU surfaces
  at **0°**, small angles (**5°** on each axis and **3°/2°** diagonally),
  larger angles (**±30°** on each axis) and diagonal pairs **30°/20°**,
  **−30°/−20°**, **30°/−20°** and **45°/45°**. Extra cases cover **30°/0°** and
  **30°/20°** at perspective **1.0**, plus **0°/30°** at perspective **0** (frost
  only). Readback checks confirmed visible effects and non-black centre pixels.
  The renders were visually inspected: small inputs stay nearly straight,
  full-strength diagonal tilt shows the expected cross-axis shear and
  foreshortening, frost-only keeps the blur without moving the content, and
  off-plane regions are opaque black.

The initial window-based capture could not acquire a surface with the phone's
screen off. The final test uses a dedicated ImageReader GPU surface and passes
without requiring a lock-screen change. It exercises hardware rendering, not a
software bitmap approximation. Test-generated PNGs stay local with the build
evidence; they contain only synthetic colour stripes.

## What remains a physical/user assessment

The IMU does not measure viewer position. Set viewing distance to approximately
the distance between the phone and your eyes, hold it in your preferred neutral
pose, and calibrate. Then turn left/right, forward/back and diagonally, hold
each angle for 15 seconds, return to neutral, and repeat in landscape. A held
tilt should remain held with drift correction disabled. Reopening the app
establishes a fresh reference. Set one manual slider to zero to isolate a single
axis when comparing directions. **Perspective** scales the geometric
compensation from 0 (frost only) to 1 (exact inverse perspective); the default
0.6 reduces the warp for small hand movements while keeping it a projection.
Because the glass rotates about the screen centre, a few degrees should produce
only a slight symmetric shift; if it does not, check that the viewing distance
roughly matches the real eye distance.

Absolute angular accuracy, long-term drift, perceived realism and end-to-end
motion-to-photon latency require a measured physical reference or user feedback.
The recorded sensor trace and synthetic renders do not establish those results.
No universal or “perfect on every firmware” compatibility claim is made.

## Sources

- [POCO official hardware specifications](https://www.po.co/global/product/poco-x7-pro/specs/)
- [Android sensor overview and sampling limits](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- [Android sensor coordinate and fusion contracts](https://source.android.com/docs/core/interaction/sensors/sensor-types)
- [RuntimeShader / RenderEffect usage](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl)
- [HardwareRenderer surface rendering](https://developer.android.com/reference/android/graphics/HardwareRenderer)

Phone serial numbers, accounts and unrelated package/activity logs are omitted
from this public report.
