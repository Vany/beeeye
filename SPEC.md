# Beeeye Mod Specification
## Stereo Rendering for Minecraft

**Mod ID:** `beeeye`  
**Mod Name:** Beeeye  
**Version:** 1.0.2  
**Minecraft Version:** 1.21.1  
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

---

## 5. Head Tracking

### 5.1 Architecture: Body vs Head

The player has two independent orientations:

- **Body** — controlled by mouse. Determines crosshair position, movement direction, and
  player entity rotation. Unchanged by head tracking.
- **Head** — body direction + OSC head delta. The camera renders what the head sees.

Crosshair always stays at body direction. WASD movement uses body direction.
The rendered view follows the physical head via camera rotation offset.

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

### 5.4 Smoothing

Incoming quaternions are smoothed via nlerp (normalized linear interpolation) with factor 0.4.
This is a fast slerp approximation, sufficient at high OSC sample rates (~60Hz).

### 5.5 Calibration

Calibration captures the current head quaternion as "neutral" (aligned with body direction).
The inverse of the neutral quaternion is stored; delta rotation = current * neutralInverse.

- **Auto-calibrate**: triggered every time stereo mode is toggled ON (`\` key)
- Head tracking only active after calibration AND while OSC data is arriving (500ms timeout)
- Head tracking disabled in mono mode

### 5.6 Dead Zones

Two dead zones suppress jitter:

1. **Neutral dead zone**: When head is within `headDeadzone` degrees of calibration center,
   output snaps to zero (identity quaternion). Provides a stable "look straight ahead" rest.

2. **Anchored dead zone**: At any other angle, dead zone is centered on the last stable position
   (anchor). Jitter is suppressed relative to where the head last came to rest, not just neutral.

Settle detection uses time-based hysteresis:
- Moving → settled: head must stay within dead zone of a settle candidate for
  `convergenceSpeed × 50ms` before movement is considered finished.
- Settled → moving: head must break out of dead zone around anchor to start tracking again.

### 5.7 Coordinate Conversion

Quaternion-based. Delta quaternion = current * inverse(neutral).
Converted to Euler angles for Minecraft camera:
```
deltaYaw   = atan2(2(dw*dy + dx*dz), 1 - 2(dy² + dz²))
deltaPitch = asin(clamp(2(dw*dx - dy*dz), -1, 1))
```

Applied in MixinCamera after Minecraft sets body rotation, before eye offset.

### 5.5 Body Crosshair

When head tracking is active, a `< >` bracket crosshair shows the body direction
(where the mouse points) in the head camera's view. Uses perspective-correct projection:
```
screenX = halfW - tan(deltaYaw) / tan(halfFov) * halfW
screenY = halfH + tan(deltaPitch) / tan(halfFov) * halfW
```

- **Light green**: MC crosshair (head center) is inside the brackets (head ≈ body)
- **Yellow**: head has turned away from body direction

Drawn onto HUD FBO after all other HUD rendering, before compositing to both eyes.

---

## 6. Keybindings

| Key | Action |
|-----|--------|
| `\` (backslash) | Toggle stereo mode on/off (auto-calibrates head tracking on enable) |

- **Category:** Beeeye in controls menu
- **Implementation:** NeoForge `RegisterKeyMappingsEvent`

---

## 7. Technical Approach

### 7.1 Mixin-Based Architecture

All rendering modifications use SpongePowered Mixin injections. No NeoForge events for core rendering — mixins provide precise control over the render pipeline.

### 7.2 FBO Layout

| FBO | Size | Purpose |
|-----|------|---------|
| leftEyeFbo / rightEyeFbo | halfW x H | Eye world capture (TextureTarget, with depth) |
| hudFbo | halfW x H | HUD capture, transparent bg (TextureTarget, with depth) |
| compositeTarget | fullW x H | Intermediary for alpha blending (TextureTarget, no depth) |
| Raw GL left/right FBOs | halfW x H | glBlitFramebuffer eye capture in MixinGameRenderer |

### 7.3 OpenGL Constraints (macOS)

- GL 4.1 max — no `glCopyImageSubData` (GL 4.3)
- No legacy fixed-function pipeline — `glMatrixMode` crashes in core profile
- `glBlitFramebuffer` (GL 3.0) for pixel copy, no alpha blend
- `blitAndBlendToTexture` (Minecraft API) for proper alpha compositing
- NeoForge wraps textures in ValidationGpuTexture — unwrap via reflection

---

## 8. Project Structure

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
│   ├── HeadTracker.java             # Immutable Quat, nlerp smoothing, anchored dead zone
│   ├── OscListener.java             # UDP OSC 1.0 receiver
│   ├── StereoRenderer.java          # RenderPhase state machine, FBOs, projection
│   └── mixin/
│       ├── MixinGameRenderer.java   # Stereo render loop + HUD compositing
│       ├── MixinProjectionMatrix.java # Off-axis projection shift
│       ├── MixinCamera.java         # Head tracking camera rotation
│       ├── MixinMinecraft.java      # Render target redirect during HUD
│       ├── MixinWindow.java         # Phase-gated width faking
│       ├── MixinGui.java            # GUI rendering hooks
│       ├── MixinGuiCrosshair.java   # Crosshair convergence offset
│       └── MixinMouseHandler.java   # Both-eye mouse translation
└── src/main/resources/
    ├── META-INF/neoforge.mods.toml
    ├── beeeye.mixins.json
    └── assets/beeeye/lang/en_us.json
```

---

## 9. Future Scope

- Per-eye resolution scaling
- Shader compatibility layer
- Facial gesture detection (blend shape-based game actions)
- Anaglyph mode (red-cyan)

---

## 10. Build & Install

```bash
./gradlew build
```

Output: `build/libs/beeeye-1.0.2.jar`

Install: Copy JAR to `.minecraft/mods/` with NeoForge 1.21.1
