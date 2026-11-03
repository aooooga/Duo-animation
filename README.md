# DuoFold for POCO X7 Pro

A quality-focused fork of [Atomicx7/Duo-animation](https://github.com/Atomicx7/Duo-animation),
based on upstream commit `8e5be09463d05018b2716065ec913c8cdd697f11`.
The goal is accurate, stable motion and correctly scaled glass on the owner's
POCO X7 Pro, rather than lowering rendering quality for a higher frame rate.
The fold responds on both axes at once and combines the two measured tilts into
one exact rotation about the screen centre, matching a rigid phone turned in the
hand. The projection is a true inverse perspective, with a **Perspective**
strength control that scales the compensation without turning it into a
translation. Every optical and motion value is adjustable in Settings.

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
| Fold only around a vertical edge (left/right motion) | Horizontal and vertical tilt fold simultaneously about the screen centre, so the near and far halves shift in opposite directions |
| Edge-hinged geometry enlarged every small rotation and flipped the hinge as tilt crossed zero | Centre rotation keeps the displacement proportional to the angle and continuous through neutral |
| Independent per-axis `cos`/`sin` scaling did not compose into a rigid rotation, so diagonal tilts were geometrically wrong | The two measured angles are combined into one exact 3D rotation before projection |
| The projective warp could only be accepted at full strength or replaced with a translation | Perspective scales the same projective transform from frost-only (0) to exact compensation (1) |

Physical xdpi scaling was already present upstream and is retained. The rodin
fallback uses its measured physical DPI only if firmware supplies an invalid
value. Viewing distance is adjustable from 200 to 2000 mm because correct
perspective also depends on where the viewer is, not just the phone hardware.
The wider optical ranges (blur spread 0–1.50, darkening up to 0.30, pixel
pitch up to 40 px/mm, maximum tilt up to 80°) let the effect be pushed well past
the upstream defaults; blur spread 0 disables the frost blur entirely, and
extreme combined tilt intentionally dims toward black as the blur radius grows.

## Build and use

Requires JDK 17+ and Android SDK Platform 36 / Build Tools 36.0.0.
AGP 8.10.1, Gradle 8.11.1, Kotlin/Compose compiler 2.0.20 are pinned.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **DuoFold**, hold the phone in the desired neutral pose, and tap **Calibrate**.
Tilt it along either axis or diagonally to open the glass. Settings expose every
value: manual horizontal and vertical tilt, viewing distance, perspective
compensation, blur spread, darkening, physical pixel pitch (auto or manual),
maximum tilt, smoothing, prediction, sensor/manual mode and optional
near-neutral drift correction, plus live sensor diagnostics and a reset button.
Each manual slider can be left at zero to isolate a single axis. **Perspective**
ranges from 0 (frost only, no geometric warp) to 1 (exact inverse perspective);
the default 0.6 keeps the compensation visible without exaggerating small hand
movements. **Blur spread** can be set to 0 to turn the frost blur off completely.
Settings open as a bottom panel over the live effect, so the background stays
visible and undimmed. The window requests up to 120 Hz; HyperOS retains control
over refresh rate, power and thermal policy.

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
