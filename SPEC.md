# Beeeye Mod Specification
## Stereo Rendering for Minecraft

**Mod ID:** `beeeye`  
**Mod Name:** Beeeye  
**Version:** 0.1.0  
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

---

## 3. Configuration

### 3.1 Config File
Location: `config/beeeye-client.toml`

### 3.2 Config Options

| Option | Type | Default | Range | Description |
|--------|------|---------|-------|-------------|
| `eyeDistance` | double | 0.25 | 0.01-1.0 | Distance between eyes in blocks |
| `screenShift` | int | 0 | -500 to 500 | Horizontal shift of split line in pixels |
| `interfaceShift` | int | 0 | -500 to 500 | GUI placement shift |

---

## 4. Rendering Algorithm

### 4.1 Stereo Method: Off-Axis Projection

Shift projection matrix `m20` element to create asymmetric frustum per eye.
Camera position stays the same — avoids chunk cache invalidation issues.

- Convergence distance: 5.0 blocks (zero parallax at this depth)
- Shift = `(eyeDistance/2) / convergenceDistance * eyeSign`
- Applied in MixinProjectionMatrix on `getProjectionMatrix(float fov)` RETURN

### 4.2 World Rendering (MixinGameRenderer, renderLevel HEAD)

1. Cancel original `renderLevel()` call
2. Set LEFT eye → `updateCamera()` → `renderLevel()` → blit center region to leftFbo
3. Set RIGHT eye → `updateCamera()` → `renderLevel()` → blit center region to rightFbo

### 4.3 HUD Rendering (MixinWindow + MixinMinecraft)

After stereo world capture, enter HUD phase:
1. Clear hudFbo (half-width, transparent)
2. Fake all window width methods to half (MixinWindow)
3. Redirect `getMainRenderTarget()` to hudFbo (MixinMinecraft)
4. Minecraft + third-party mods draw HUD onto half-width transparent buffer
5. HUD phase ends

### 4.4 Compositing (MixinGameRenderer, render TAIL)

1. Restore stereo world: leftFbo → left half, rightFbo → right half of main target
2. Alpha-composite HUD onto left eye via full-width composite buffer + `blitAndBlendToTexture`
3. Alpha-composite HUD onto right eye via same method
4. `blitAndBlendToTexture` uses ENTITY_OUTLINE_BLIT pipeline (SRC_ALPHA, ONE_MINUS_SRC_ALPHA)
5. Transparent HUD pixels (alpha=0) leave stereo world untouched

---

## 5. Keybinding

- **Key:** `\` (backslash)
- **Action:** Toggle stereo mode on/off
- **Category:** Misc in controls menu
- **Implementation:** NeoForge `RegisterKeyMappingsEvent`

---

## 6. Technical Approach

### 6.1 Mixin-Based Architecture

All rendering modifications use SpongePowered Mixin injections. No NeoForge events for core rendering — mixins provide precise control over the render pipeline.

### 6.2 FBO Layout

| FBO | Size | Purpose |
|-----|------|---------|
| leftEyeFbo / rightEyeFbo | halfW x H | Eye world capture (TextureTarget, with depth) |
| hudFbo | halfW x H | HUD capture, transparent bg (TextureTarget, with depth) |
| compositeTarget | fullW x H | Intermediary for alpha blending (TextureTarget, no depth) |
| Raw GL left/right FBOs | halfW x H | glBlitFramebuffer eye capture in MixinGameRenderer |

### 6.3 OpenGL Constraints (macOS)

- GL 4.1 max — no `glCopyImageSubData` (GL 4.3)
- No legacy fixed-function pipeline — `glMatrixMode` crashes in core profile
- `glBlitFramebuffer` (GL 3.0) for pixel copy, no alpha blend
- `blitAndBlendToTexture` (Minecraft API) for proper alpha compositing
- NeoForge wraps textures in ValidationGpuTexture — unwrap via reflection

---

## 7. Project Structure

```
beeeye/
├── build.gradle
├── settings.gradle
├── gradle.properties
├── src/main/java/com/beeeye/
│   ├── Beeeye.java                  # Main mod class
│   ├── BeeeyeConfig.java            # Configuration
│   ├── BeeeyeKeyBindings.java       # Keybind registration
│   ├── StereoRenderer.java          # State, FBOs, projection offset
│   ├── ScreenRenderHandler.java     # Screen/inventory duplication
│   └── mixin/
│       ├── MixinGameRenderer.java   # Stereo render loop + HUD compositing
│       ├── MixinProjectionMatrix.java # Off-axis projection shift
│       ├── MixinCamera.java         # Camera offset (disabled by default)
│       ├── MixinMinecraft.java      # Render target redirect during HUD
│       ├── MixinWindow.java         # Width faking during HUD phase
│       ├── MixinGui.java            # GUI rendering hooks
│       ├── MixinGuiCrosshair.java   # Crosshair convergence offset
│       └── MixinMouseHandler.java   # Mouse handling adjustments
└── src/main/resources/
    ├── META-INF/neoforge.mods.toml
    ├── beeeye.mixins.json
    └── assets/beeeye/lang/en_us.json
```

---

## 8. Future Scope

- Configurable convergence distance
- Per-eye resolution scaling
- Shader compatibility layer
- Head tracking integration (X-Real SDK)
- Anaglyph mode (red-cyan)

---

## 9. Build & Install

```bash
./gradlew build
```

Output: `build/libs/beeeye-0.1.0.jar`

Install: Copy JAR to `.minecraft/mods/` with NeoForge 1.21.1
