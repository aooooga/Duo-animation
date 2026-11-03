# DuoFold for POCO X7 Pro

A quality-focused fork of [Atomicx7/Duo-animation](https://github.com/Atomicx7/Duo-animation),
based on upstream commit `8e5be09463d05018b2716065ec913c8cdd697f11`.
The goal is accurate, stable motion and correctly scaled glass on the owner's
POCO X7 Pro, rather than lowering rendering quality for a higher frame rate.
The fold responds on both axes: left/right rotation hinges on a vertical edge,
forward/back rotation hinges on a horizontal edge, and every optical and motion
value is adjustable in Settings.

## Hardware verified

The connected global POCO X7 Pro (`rodin`, 2412DPC0AG) reports:

- ST `lsm6dsvq_acc` accelerometer and `lsm6dsvq_gyro` calibrated gyroscope.
- MediaTek `game_rotvec` fusion, supporting 5–200 Hz. The app requests 200 Hz
  with zero batching and retains the calibrated gyro for short prediction.
- 1220 × 2712 panel, physical density 445.61404 dpi (~17.544 px/mm).
  Android's 520 dpi logical density is UI scaling, not the physical pixel pitch.
- Mali-G720 MC7; OpenGL ES 3.2, driver r49p1 on this firmware.
- Android 16 / HyperOS `OS3.0.302.0.WOJMIXM`, display modes 30/60/90/120 Hz.

Sensor vendors can change between production runs. Selection uses Android's
sensor types and reported minimum delays, not a forced vendor name. Android
already defines the device sensor coordinate system; no invented Xiaomi axis
swap, compass offset or gyro sensitivity multiplier is applied.

## Quality corrections

| Upstream issue | Fork behavior |
| --- | --- |
| 40 ms gyro prediction regardless of sample age or display cadence | Delivery-age prediction plus half a display interval, capped at 25 ms; stale gyro samples excluded |
| Smoothing closes 70% of the error per sample | Time-based 10 ms smoothing has the same response at 50, 100 and 200 Hz |
| Auto-recenter slowly cancels any held angle | Off by default; optional drift correction only within 2° of neutral while nearly stationary |
| Only the current screen-Y gyro component predicts heading | Differentiate the calibrated screen-normal angle, accounting for combined pitch/roll |
| Application context display lookup silently falls back to portrait | Activity's actual display rotation; new reference when rotation changes |
| Paused/manual mode retains sensor registrations and stale prediction state | Register only in resumed sensor mode; recalibrate on return |
| Internal angle can build past the 45° visual limit | Clamp the target before smoothing, so return motion responds immediately |
| Out-of-layer shader samples can clamp edge pixels | Explicit opaque-black miss samples match the documented optical model |
| Fractional blur tap count can put samples outside its intended radius | Full 32-point disk for every visible blur kernel, without a quality reduction |
| Shader compiled for every tilt update; failures silently show plain content | One shader per layer, new uniform snapshots; visible compilation error |
| Fold only around a vertical edge (left/right motion) | Vertical and horizontal hinge modes plus Auto, which follows the dominant tilt with hysteresis |

Physical xdpi scaling was already present upstream and is retained. The rodin
fallback uses its measured physical DPI only if firmware supplies an invalid
value. Viewing distance is adjustable from 250 to 600 mm because correct
perspective also depends on where the viewer is, not just the phone hardware.

## Build and use

Requires JDK 17+ and Android SDK Platform 36 / Build Tools 36.0.0.
AGP 8.10.1, Gradle 8.11.1, Kotlin/Compose compiler 2.0.20 are pinned.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **DuoFold**, hold the phone in the desired neutral pose, and tap **Calibrate**.
Turn it left/right or forward/back to open the glass. Settings expose every
value: fold axis (Auto / horizontal / vertical), manual horizontal and vertical
tilt, viewing distance, blur spread, darkening, physical pixel pitch (auto or
manual), maximum tilt, smoothing, prediction, sensor/manual mode and optional
near-neutral drift correction, plus live sensor diagnostics and a reset button.
The window requests up to 120 Hz; HyperOS retains control over refresh rate,
power and thermal policy.

This is an app-local demo, not a launcher/SystemUI modification. It requires no
root, privileged sensor permissions or global phone settings changes.

## Validation and limits

See [device validation](docs/DEVICE-VALIDATION.md). The tests cover calibrated
angle math, sample-rate-independent filtering, bounded prediction, real sensor
delivery, on-device shader compilation and actual hardware-window rendering.

A phone IMU cannot determine the viewer's eye position. This remains an optical
simulation with a chosen viewing distance, not eye-tracked glass. Sensor fusion
can drift over time; calibrate again if necessary. Static sensor/render tests
cannot establish absolute angular accuracy without a measured physical reference.
Compatibility is demonstrated for the recorded device/firmware, not guaranteed
for every ROM or future update.

## Upstream credit

The original animation, ray/plane optical model, Compose demo and AGSL port are
by Atomicx7. [Support the original author](https://ko-fi.com/xatomicx).
The inspected upstream snapshot contains no project-level license file; this
fork does not invent a license grant for the upstream code.
