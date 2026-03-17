# Beeeye Mod Specification
## Stereo Rendering for Minecraft

**Mod ID:** `beeeye`
**Mod Name:** Beeeye
**Version:** 1.1.2
**Minecraft Version:** 1.21.11
**Mod Loader:** NeoForge  
**Package:** `com.beeeye`

---

## 1. Overview

Beeeye is a client-side Minecraft mod that enables stereoscopic 3D rendering. The mod produces side-by-side stereo output for AR glasses (X-Real 2), 3D monitors, or cross-eye viewing.

---

## 2. Modes

### 2.1 Normal Mode (Default)
- Pass-through rendering, mod does nothing
- Standard Minecraft single-view output

### 2.2 Stereo Mode (Toggle with `\`)
- Side-by-side split screen
- Left half: left eye view
- Right half: right eye view
- World rendered twice via off-axis projection (asymmetric frustum)
- HUD alpha-composited onto both eyes
- Automatically disabled when leaving world (disconnect/quit to menu)

---

## 3. Configuration

### 3.1 Config File
Location: `config/beeeye-client.toml`

### 3.2 Config Options

| Option | Type | Default | Range | Description |
|--------|------|---------|-------|-------------|
| `eyeDistance` | double | 0.25 | 0.01-1.0 | Distance between eyes in blocks (IPD) |
| `convergence` | double | 5.0 | 1.0-50.0 | Convergence distance in blocks (zero parallax). Fallback when dynamic has no target. |
| `dynamicConvergence` | boolean | true | — | Auto-adjust convergence to crosshair target distance |
| `convergenceSpeed` | int | 4 | 1-40 | Time in minecraft ticks to converge to new target distance. 1=instant, higher=smoother. |
| `oscPort` | int | 8001 | 1024-65535 | UDP port to listen for OSC face tracking data. |
| `headDeadzone` | double | 2.0 | 0.0-15.0 | Head tracking dead zone in degrees. |

### 3.3 Config Persistence

All config parameters (except `enabled`/stereo toggle) are **written through** to the config file
when changed at runtime via `/beeeye set` command or configuration UI. Changes persist across
game restarts. The stereo toggle is session-only — mod always starts in normal mode.

Implementation: call `SPEC.save()` after any `ConfigValue.set()` call.

---

## 4. Rendering Algorithm

### 4.1 Stereo Method: Off-Axis Projection

Shift projection matrix `m20` element to create asymmetric frustum per eye.
Camera position stays the same — avoids chunk cache invalidation issues.

- Convergence distance: 5.0 blocks (zero parallax at this depth)
- Shift = `(eyeDistance/2) / convergenceDistance * eyeSign`
- Applied in MixinProjectionMatrix on `getProjectionMatrix(float fov)` RETURN

### 4.2 Dynamic Convergence

When `dynamicConvergence` is enabled, convergence distance auto-adjusts each frame:

1. **Center ray hit**: If `minecraft.hitResult` hits a block or entity, converge to that distance.
2. **Eye-ray fallback**: If center ray misses (sky), cast two additional rays — one from each
   eye position (±IPD/2 along camera local X) toward the current convergence point.
   Each eye ray checks both blocks (`level.clip`) and entities (`ProjectileUtil.getEntityHitResult`).
   The closest hit across all 4 raycasts wins.
3. **Static fallback**: If all rays miss, fall back to static `convergence` config value.

Eye-ray picking ensures convergence snaps to nearby entities or blocks visible to either eye,
even when the center crosshair points at empty sky. Important for combat — a mob slightly
off-center still triggers proper depth convergence.

### 4.3 World Rendering (MixinGameRenderer, renderLevel HEAD)

1. Cancel original `renderLevel()` call
2. Set LEFT eye → `updateCamera()` → `renderLevel()` → blit center region to leftFbo
3. Set RIGHT eye → `updateCamera()` → `renderLevel()` → blit center region to rightFbo

### 4.4 HUD Rendering (MixinWindow + MixinMinecraft)

After stereo world capture, enter HUD phase:
1. Clear hudFbo (half-width, transparent)
2. Fake all window width methods to half (MixinWindow)
3. Redirect `getMainRenderTarget()` to hudFbo (MixinMinecraft)
4. Minecraft + third-party mods draw HUD onto half-width transparent buffer
5. HUD phase ends

### 4.5 Compositing (MixinGameRenderer, render TAIL)

1. Restore stereo world: leftFbo → left half, rightFbo → right half of main target
2. Alpha-composite HUD onto left eye via full-width composite buffer + `blitAndBlendToTexture`
3. Alpha-composite HUD onto right eye via same method
4. `blitAndBlendToTexture` uses ENTITY_OUTLINE_BLIT pipeline (SRC_ALPHA, ONE_MINUS_SRC_ALPHA)
5. Transparent HUD pixels (alpha=0) leave stereo world untouched

### 4.6 Crosshair (MixinGui + BodyCrosshair)

The vanilla crosshair (`+`) is suppressed during HUD capture (MixinGui cancels `Gui.renderCrosshair`
at HEAD when `isHudPhase()`). A `+` crosshair is drawn at the center of each eye half during compositing:

1. No per-eye pixel shift — off-axis projection + camera eye offset already places objects at
   convergence distance at zero parallax (screen center in each eye).
2. Drawn via glScissor + glClear — two rectangles forming `+`.
3. White with 80% opacity. Scaled by GUI scale to match vanilla crosshair proportions.

Rendered after HUD alpha-compositing, before body crosshair overlay.

---

## 5. Head Tracking

### 5.1 Architecture: Body vs Head

The player has two independent orientations:

- **Body** — controlled by mouse. Determines movement direction and player entity rotation.
- **Head** — body direction + OSC head delta. The camera renders what the head sees.
  **Interactions (break/place/hit) follow head direction** via head-tracked picking.

WASD movement uses body direction. The rendered view and interactions follow the physical head.
A `< >` body crosshair shows the body (mouse) direction when head is turned away.

### 5.2 OSC Receiver

A UDP socket listens on `liveLinkPort` (default 8001) for OSC messages using the
data OSC face tracking protocol:

| OSC Address | Type | Description |
|-------------|------|-------------|
| `/data/faceTracking/face/rotation/x` | float | Quaternion X component |
| `/data/faceTracking/face/rotation/y` | float | Quaternion Y component |
| `/data/faceTracking/face/rotation/z` | float | Quaternion Z component |
| `/data/faceTracking/face/rotation/w` | float | Quaternion W component |

The listener starts at mod init, runs on a daemon thread, uses a built-in OSC 1.0 parser
(no external dependencies). Blend shapes and other addresses are received but currently unused.

### 5.3 Thread Safety

HeadTracker uses immutable `Quat` records — JVM guarantees atomic reference assignment.
OSC thread writes new quaternions via `update()`, render thread reads via `getDelta()`.
No locks needed; no component tearing possible.

### 5.4 Signal Processing

Incoming quaternions are passed through raw — no smoothing or filtering applied.
The OSC source (Rust `ht` app or data OSC) is expected to provide already-filtered data.

### 5.5 Calibration

Calibration captures the current head quaternion as "neutral" (aligned with body direction).
The inverse of the neutral quaternion is stored; delta rotation = current * neutralInverse.

- **Auto-calibrate**: triggered every time stereo mode is toggled ON (`\` key)
- Head tracking only active after calibration AND while OSC data is arriving (500ms timeout)
- Head tracking disabled in mono mode

### 5.6 Dead Zones

Two dead zones suppress jitter:

1. **Neutral dead zone**: When head is within `headDeadzone` degrees of calibration center,
   output snaps to zero (identity quaternion) **instantly**. Provides a stable "look straight ahead" rest.

2. **Anchored dead zone**: At any other angle, dead zone is centered on the last stable position
   (anchor). Jitter is suppressed relative to where the head last came to rest, not just neutral.
   Anchor locks after head stays within dead zone for **100ms** (prevents snapping when passing through).

### 5.7 Coordinate Conversion

Quaternion-based. Delta quaternion = current * inverse(neutral).
Converted to Euler angles for Minecraft camera:
```
deltaYaw   = atan2(2(dw*dy + dx*dz), 1 - 2(dy² + dz²))
deltaPitch = asin(clamp(2(dw*dx - dy*dz), -1, 1))
```

Applied in MixinCamera after Minecraft sets body rotation, before eye offset.

### 5.8 Head-Tracked Interaction Picking

When stereo + head tracking are active, `GameRenderer.pick()` is overridden at TAIL
to re-raycast using the camera's head-tracked forward vector instead of the entity's
body direction. This makes all interactions (break/place/hit/buttons) follow where
the head is looking.

- Raycasts from `player.getEyePosition()` along `mainCamera.forwardVector()`
- Checks both blocks (`level.clip`) and entities (`ProjectileUtil.getEntityHitResult`)
- Picks closest hit; sets `minecraft.hitResult` and `minecraft.crosshairPickEntity`
- Uses `player.blockInteractionRange()` and `player.entityInteractionRange()` for distances

### 5.9 Body Crosshair

When head tracking is active, a `< >` bracket crosshair shows the body direction
(where the mouse points) in the head camera's view. Uses perspective-correct projection:
```
screenX = halfW - tan(deltaYaw) / tan(halfFov) * halfW
screenY = halfH + tan(deltaPitch) / tan(halfFov) * halfW
```

- **Light green**: MC crosshair (head center) is inside the brackets (head ≈ body)
- **Yellow**: head has turned away from body direction
- **Edge clamping**: when body crosshair would be outside the eye viewport, a single
  rotated chevron is drawn pinned to the edge, pointing toward the actual crosshair position.

Drawn onto main FBO after compositing, on both eye halves.

---

## 6. Head Tracking Bridge (`ht`)

Standalone Rust application that reads IMU data from Xreal Air 2 AR glasses and streams
orientation quaternions over OSC to the Beeeye mod. Replaces the data OSC iOS app with
direct USB connection for lower latency and no phone dependency.

### 6.1 Pipeline

```
Xreal Air 2 (USB-C) → ar-drivers-rs → EKF sensor fusion → OSC UDP → Beeeye mod
```

### 6.2 Sensor Input

- **Hardware:** Xreal Air 2 AR glasses via USB-C
- **Driver:** [ar-drivers-rs](https://github.com/badicsalex/ar-drivers-rs) community crate
- **IMU rate:** 1000 Hz — accelerometer + gyroscope events (`GlassesEvent::AccGyro`)
- **Magnetometer:** Received but unused (unreliable indoors)
- **Coordinate system:** ar-drivers RUB — X=Right, Y=Up, Z=Back

### 6.3 Sensor Fusion: 7-State Extended Kalman Filter

The EKF fuses gyroscope and accelerometer into a stable orientation quaternion.

**State vector (7 elements):**
- Quaternion orientation: `[q_w, q_x, q_y, q_z]`
- Gyroscope bias estimation: `[b_x, b_y, b_z]`

**Predict step (every IMU sample):**
- Bias-corrected angular velocity: `w = gyro - bias`
- Quaternion propagation via Omega matrix: `q_new = q + 0.5 * Omega(w) * q * dt`
- Covariance propagation: `P = F * P * F^T + Q`
- `dt` derived from `Instant::now()` deltas (not assumed fixed)

**Update step (accelerometer, gated):**
- Only applied when `|‖accel‖ - 9.81| < 2.0 m/s²` (rejects non-gravity accelerations)
- Predicted gravity in sensor frame: `h = C(q)^T * [0, 1, 0]` (Y-up convention)
- Innovation: `y = normalize(accel) - h`
- Standard Kalman update: `K = P * H^T * (H * P * H^T + R)^-1`, state += K * y
- Quaternion renormalized after correction
- Covariance symmetrized each step to prevent numerical drift

**Tuning constants:**

| Parameter | Value | Description |
|-----------|-------|-------------|
| `SIGMA_GYRO` | 0.01 rad/s | Gyroscope noise density |
| `SIGMA_BIAS` | 0.001 rad/s² | Gyro bias random walk |
| `SIGMA_ACCEL` | 0.5 | Accelerometer measurement noise |
| `ACCEL_GATE` | 2.0 m/s² | Reject accel update when ‖a‖ deviates from g |

**Initialization:** First accelerometer reading aligns orientation so gravity matches Y-up
via `UnitQuaternion::rotation_between(accel, up)`.

### 6.4 OSC Output

Quaternion sent as 4 separate OSC float messages per sample to `localhost:8001`:

| OSC Address | Value |
|-------------|-------|
| `/data/faceTracking/face/rotation/x` | `-q.i` (pitch negated) |
| `/data/faceTracking/face/rotation/y` | `q.j` (yaw) |
| `/data/faceTracking/face/rotation/z` | `-q.k` (z negated for handedness) |
| `/data/faceTracking/face/rotation/w` | `q.w` (triggers update in Beeeye) |

**Coordinate remapping:** ar-drivers RUB → Beeeye expects sign conventions matching
data OSC app output. Pitch (x) and z are negated to correct axis/handedness.

The `/w` message is sent last — Beeeye's `OscListener` buffers x/y/z and pushes
a complete quaternion on receiving w.

### 6.5 Usage

```
cargo run -- [--host <HOST>] [--port <PORT>]
```

| Option | Default | Description |
|--------|---------|-------------|
| `--host` | 127.0.0.1 | OSC target host |
| `--port` | 8001 | OSC target port (matches Beeeye `oscPort` config) |

Ctrl+C for graceful shutdown. Prints diagnostic telemetry every 200 samples.

### 6.6 Debug Client

`headtracking/client/` contains a standalone OSC listener that prints all received
messages to stdout. Useful for verifying quaternion output without running Minecraft.

```
cd client && cargo run
```

### 6.7 Dependencies

| Crate | Purpose |
|-------|---------|
| `ar-drivers` | Xreal Air 2 USB HID driver (git dep) |
| `nalgebra` | Linear algebra (quaternions, matrices) |
| `rosc` | OSC protocol encoding |
| `clap` | CLI argument parsing |
| `ctrlc` | Graceful shutdown on Ctrl+C |
| `anyhow` | Error handling |

### 6.8 Project Structure

```
headtracking/
├── Cargo.toml                # Workspace root
├── src/
│   ├── main.rs               # Event loop: ar-drivers → fusion → OSC
│   ├── fusion.rs             # 7-state EKF (quaternion + gyro bias)
│   └── osc.rs                # OSC UDP sender with coordinate remapping
└── client/
    ├── Cargo.toml
    └── src/main.rs           # Debug OSC listener (prints to stdout)
```

---

## 7. Keybindings

| Key | Action |
|-----|--------|
| `\` (backslash) | Toggle stereo mode on/off (auto-calibrates head tracking on enable) |

- **Category:** Beeeye in controls menu
- **Implementation:** NeoForge `RegisterKeyMappingsEvent`

---

## 8. Technical Approach

### 8.1 Mixin-Based Architecture

All rendering modifications use SpongePowered Mixin injections. No NeoForge events for core rendering — mixins provide precise control over the render pipeline.

### 8.2 FBO Layout

| FBO | Size | Purpose |
|-----|------|---------|
| leftEyeFbo / rightEyeFbo | halfW x H | Eye world capture (TextureTarget, with depth) |
| hudFbo | halfW x H | HUD capture, transparent bg (TextureTarget, with depth) |
| compositeTarget | fullW x H | Intermediary for alpha blending (TextureTarget, no depth) |
| Raw GL left/right FBOs | halfW x H | glBlitFramebuffer eye capture in MixinGameRenderer |

### 8.3 OpenGL Constraints (macOS)

- GL 4.1 max — no `glCopyImageSubData` (GL 4.3)
- No legacy fixed-function pipeline — `glMatrixMode` crashes in core profile
- `glBlitFramebuffer` (GL 3.0) for pixel copy, no alpha blend
- `blitAndBlendToTexture` (Minecraft API) for proper alpha compositing
- NeoForge wraps textures in ValidationGpuTexture — unwrap via reflection

---

## 9. Project Structure

```
beeeye/
├── build.gradle
├── settings.gradle
├── gradle.properties
├── src/main/java/com/beeeye/
│   ├── Beeeye.java                  # Main mod class
│   ├── BeeeyeCommand.java           # /beeeye client command
│   ├── BeeeyeConfig.java            # Configuration
│   ├── BeeeyeKeyBindings.java       # Keybind registration
│   ├── BodyCrosshair.java           # Body direction crosshair (< > brackets)
│   ├── GlFboCache.java              # GL FBO cache (int[] arrays, zero boxing)
│   ├── GlTextureUtil.java           # ValidationGpuTexture unwrapping
│   ├── HeadTracker.java             # Immutable Quat, raw passthrough, dual dead zone
│   ├── OscListener.java             # UDP OSC 1.0 receiver
│   ├── StereoRenderer.java          # RenderPhase state machine, FBOs, projection
│   └── mixin/
│       ├── MixinGameRenderer.java   # Stereo render loop + HUD compositing
│       ├── MixinProjectionMatrix.java # Off-axis projection shift
│       ├── MixinCamera.java         # Head tracking camera rotation
│       ├── MixinMinecraft.java      # Render target redirect during HUD
│       ├── MixinWindow.java         # Phase-gated width faking
│       ├── MixinGui.java            # Suppress crosshair during HUD capture
│       ├── MixinLevelRenderer.java  # Cancel doEntityOutline during HUD capture
│       └── MixinMouseHandler.java   # Both-eye mouse translation
└── src/main/resources/
    ├── META-INF/neoforge.mods.toml
    ├── beeeye.mixins.json
    └── assets/beeeye/lang/en_us.json
```

---

## 10. Future Scope

- Per-eye resolution scaling
- Shader compatibility layer
- Facial gesture detection (blend shape-based game actions)
- Anaglyph mode (red-cyan)

---

## 11. Build & Install

```bash
./gradlew build
```

Output: `build/libs/beeeye-1.1.2.jar`

Install: Copy JAR to `.minecraft/mods/` with NeoForge 1.21.11
