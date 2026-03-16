## Beeeye Development Notes

### Current Implementation: Off-Axis Projection Stereo + HUD Alpha Compositing

**Status**: v1.1.2. Config-driven IPD and convergence. HUD alpha composited to both eyes.
Head tracking via OSC with raw passthrough and dual dead zone (100ms anchor settle).
Head-tracked interaction picking. Stereo mouse on both halves.
Duplicate instance detection (static flag in constructor → IllegalStateException).

#### Stereo Approaches Tried

1. **Camera offset** (abandoned): Move camera left/right between eye renders
   - Entities render correctly with parallax
   - Blocks/chunks FREEZE — cached in SectionOcclusionGraph per frame
   - `occlusionGraph.invalidate()` between renders did not fix it
   - Conclusion: Minecraft's chunk rendering cache is too deep to invalidate mid-frame

2. **Off-axis projection** (current): Shift projection matrix horizontally
   - Camera position stays the SAME for both eyes
   - Projection matrix `m20` element shifted to create asymmetric frustum
   - Chunks use same frustum culling → no cache issues
   - This is how professional 3D cinema and VR systems work
   - Convergence distance = 5.0 blocks (objects at this distance have zero parallax)

#### HUD Approaches Tried

1. **Legacy GL alpha blend** (failed): `glMatrixMode` → FATAL on macOS core profile
2. **Fake width only** (failed): Ortho projection half-width but framebuffer full → 2x stretch
3. **Copy left half to right** (failed): Overwrites right-eye world
4. **Render HUD only on left eye** (partial): Only left half of HUD visible, no right eye

5. **Alpha compositing via blitAndBlendToTexture** (current):
   - Redirect render target to half-width hudFbo during HUD phase
   - Fake all window width methods to half (MixinWindow)
   - After HUD drawn: restore stereo world, alpha-blend HUD onto both halves
   - Uses ENTITY_OUTLINE_BLIT pipeline (SRC_ALPHA, ONE_MINUS_SRC_ALPHA)
   - Full-width composite buffer as intermediary for blitAndBlendToTexture

#### Architecture

**Rendering pipeline** (8-step plan from TODO.md):
1. Render left and right eyes into raw GL FBOs (MixinGameRenderer, renderLevel HEAD)
2. Clear hudFbo to transparent
3. Enable HUD phase — fake width to half, redirect render target to hudFbo
4. Minecraft and mods draw HUD onto half-width transparent hudFbo
5. HUD phase ends (render TAIL)
6. Restore stereo world: left eye → left half, right eye → right half of main target
7. Alpha-composite HUD onto left eye (composite buffer + blitAndBlendToTexture)
8. Alpha-composite HUD onto right eye (composite buffer + blitAndBlendToTexture)

**Projection shift** (MixinProjectionMatrix):
- Hooks `getProjectionMatrix(float fov)` at RETURN
- Modifies `matrix.m20()` based on eye and eye distance
- Shift = `(eyeDistance/2) / convergenceDistance * eyeSign`
- No camera position change needed

**Width faking** (MixinWindow):
- `getWidth()` → `framebufferWidth / 2`
- `getScreenWidth()` → `width / 2` (field is `width`, NOT `screenWidth`)
- `getGuiScaledWidth()` → `guiScaledWidth / 2`
- All guarded by `StereoRenderer.isHudPhase()`

**Render target redirect** (MixinMinecraft):
- During hudPhase, `getMainRenderTarget()` returns hudFbo
- Makes `guiRenderer.render()` draw to half-width buffer
- Third-party mods (JourneyMap etc.) also draw to hudFbo

**Alpha compositing** (MixinGameRenderer render TAIL):
- Uses full-width composite RenderTarget from StereoRenderer
- Clear composite → blit HUD into left half → `blitAndBlendToTexture` onto main
- Clear composite → blit HUD into right half → `blitAndBlendToTexture` onto main
- Transparent pixels (alpha=0) leave stereo world untouched: `0 + dst * 1 = dst`

#### FBO Layout

| FBO | Size | Depth | Purpose |
|-----|------|-------|---------|
| leftEyeFbo | halfW x H | yes | Left eye world capture (TextureTarget) |
| rightEyeFbo | halfW x H | yes | Right eye world capture (TextureTarget) |
| hudFbo | halfW x H | yes | HUD capture, transparent bg (TextureTarget) |
| compositeTarget | fullW x H | no | Intermediary for alpha blending (TextureTarget) |
| beeeye$leftFbo | halfW x H | no | Raw GL FBO for eye blit (glBlitFramebuffer) |
| beeeye$rightFbo | halfW x H | no | Raw GL FBO for eye blit (glBlitFramebuffer) |

Note: Raw GL FBOs in MixinGameRenderer are separate from TextureTargets in StereoRenderer.
The raw GL FBOs capture from main target via glBlitFramebuffer. The TextureTargets are
used for HUD rendering (hudFbo) and alpha compositing (compositeTarget).

#### Key Files

| File | Purpose |
|------|---------|
| `StereoRenderer.java` | State, FBOs, GL FBO cache, RenderPhase state machine |
| `MixinGameRenderer.java` | Stereo render loop, HUD alpha compositing |
| `MixinProjectionMatrix.java` | Off-axis projection shift on `getProjectionMatrix()` |
| `MixinCamera.java` | Head tracking application (guarded by stereo enabled) |
| `MixinMinecraft.java` | Redirects `getMainRenderTarget()` to eye/HUD FBO |
| `MixinWindow.java` | Fakes width/guiScaledWidth to half (phase-gated) |
| `MixinMouseHandler.java` | Translates mouse X for both eye halves (xpos + getScaledXPos) |
| `HeadTracker.java` | Immutable Quat record, raw passthrough, dual dead zone |
| `OscListener.java` | UDP OSC receiver, buffers quaternion components |
| `BodyCrosshair.java` | Body direction crosshair via scissor+clear |
| `GlTextureUtil.java` | ValidationGpuTexture unwrapping via reflection |
| `GlFboCache.java` | GL FBO management with int[] arrays (zero boxing) |
| `BeeeyeKeyBindings.java` | Key bindings (`\` backslash toggle stereo) |
| `BeeeyeConfig.java` | Configuration: eyeDistance, convergence, oscPort, deadzone |

#### Head Tracking

- **OSC protocol**: Receives quaternion rotation from data OSC app
- **OSC paths**: `/data/faceTracking/face/rotation/{x,y,z,w}` — buffered, pushed on /w
- **Immutable Quat record**: Atomic reference swap eliminates tearing between OSC and render threads
- **Raw passthrough**: No nlerp smoothing — OSC source provides filtered data
- **Dual dead zone**: Neutral (instant snap to zero) + anchored (100ms settle before lock)
  - When moving and settling within dead zone for 100ms → anchor locks, output frozen
  - When stationary and breaking out of dead zone → start moving again
  - Eliminates jitter at any head angle, not just neutral
- **Calibration**: `HeadTracker.calibrate()` saves current as neutral, resets anchor

#### RenderPhase State Machine

Replaces 4 independent booleans with single enum: `INACTIVE, EYE_RENDER, HUD_CAPTURE, COMPOSITING`
- `isInStereoPass()` → `phase == EYE_RENDER` (NOT `!= INACTIVE` — that breaks HUD)
- `isHudPhase()` → `phase == HUD_CAPTURE`
- Critical: finally block in render loop only resets `beeeye$inStereoRender`, NOT the phase

#### Configuration

- `eyeDistance`: 0.25 blocks (default), range 0.01-1.0 — IPD (inter-pupillary distance)
- `convergence`: 5.0 blocks (default), range 1.0-50.0 — zero parallax distance (fallback for dynamic)
- `dynamicConvergence`: true (default) — auto-adjust convergence to crosshair target distance
- `convergenceSpeed`: 4 ticks (default), range 1-40 — time in minecraft ticks to reach new target distance. Replaces old `convergenceSmoothing` lerp factor. Internally converted to per-tick lerp: `1 - exp(-2.2 / speed)` for smooth exponential approach.

#### Dynamic Convergence Flickering Fix

**Problem**: Convergence oscillated rapidly between near/far when head tracking was active.

**Root causes** (two reinforcing issues):
1. `minecraft.hitResult` uses **body direction** (mouse), not head-tracked camera direction.
   With head turned, player sees a close block but hitResult points at sky → convergence
   jumps to static fallback → next frame snaps back → flicker.
2. `BinocularPicker` cast eye rays toward **current convergence point**, creating a feedback
   loop: convergence far → rays aim far → miss close block → stay far; convergence near →
   rays aim near → hit → stay near. Any perturbation caused oscillation.

**Fix**:
1. Moved convergence update to **after LEFT eye camera setup** — `mainCamera` now includes
   head tracking rotation, so rays match what the player actually sees.
2. Replaced `minecraft.hitResult` with own center ray using camera's head-tracked forward vector.
3. BinocularPicker now casts all rays **forward along look direction at fixed 128-block range**
   instead of toward current convergence point. Eliminates feedback loop.
4. Three-tier picking: center ray → binocular eye rays → static fallback.
- `oscPort`: 9001 (default), range 1024-65535 — UDP port for OSC head tracking data
- `headDeadzone`: 2.0 degrees (default), range 0.0-15.0 — angular dead zone for head tracking

#### Config Persistence

All parameters except `enabled` (stereo toggle) are written through to `config/beeeye-client.toml`
when changed via `/beeeye set` or config UI. Call `SPEC.save()` after `ConfigValue.set()`.

#### OpenGL Constraints

- **macOS GL 4.1 limit**: No `glCopyImageSubData` (GL 4.3), no legacy fixed-function pipeline
- **No `glMatrixMode`**: Crashes on macOS core profile — use modern render pipeline only
- **`glBlitFramebuffer`**: GL 3.0, copies pixels but does NOT alpha blend
- **`blitAndBlendToTexture`**: Minecraft method, ENTITY_OUTLINE_BLIT pipeline, proper alpha blend
- **ValidationGpuTexture**: NeoForge wraps textures; unwrap via `getRealTexture()` → `glId()`
- **Temp GL FBOs**: Created per-frame in render TAIL to wrap RenderTarget textures for glBlitFramebuffer

#### Recent Fixes (v1.1.2)

- **MixinLevelRenderer not registered**: The class existed but was missing from `beeeye.mixins.json`.
  `doEntityOutline()` cancel during HUD_CAPTURE was never running. Now registered.
- **Anchor drift bug**: `anchor = current` ran unconditionally inside the `moving` block, including
  inside the dead zone. The anchor chased the head → anchorDelta always near zero → settle timer
  never fired. Fixed: anchor only advances when OUTSIDE the dead zone.
- **ZUPT gyro bias** (fusion.rs): When stationary, raw gyro ≈ bias. EMA estimate (α=0.001)
  subtracted each sample. Thresholds: gyro < 0.1 rad/s AND |accel_norm - 9.81| < 1 m/s².
- **Jade black screen** (v1.1.2): Jade called `glClear()` via `getMainRenderTarget()` redirect
  during HUD_CAPTURE with default clear color (alpha=1) → opaque black hudFbo → wipes world.
  Fix: `GL11.glClearColor(0,0,0,0)` before HUD phase so any mod's glClear is transparent.
  Also: `GL_SCISSOR_TEST` left enabled by mods corrupted `glBlitFramebuffer` compositing —
  save/restore scissor state around all blits in render TAIL.

#### Sophisticated Storage multi-pass rendering (FIXED)

**Root cause**: SS enables `GL_SCISSOR_TEST` for its scrollable item list (clips rendering
to the list area) and never disables it before returning from `Screen.render()`. Our compositing
code correctly disabled scissor for `glBlitFramebuffer`, but then **re-enabled it before
`blitAndBlendToTexture`**. That shader respects GL scissor, so only the portion of hudFbo
within SS's item-list box got blended onto the screen — the rest was clipped.

**Why tooltip fixed it**: `GuiGraphics.disableScissor()` inside tooltip rendering popped SS's
scissor off the stack and disabled `GL_SCISSOR_TEST` before compositing ran.

**Fix**: moved `if (scissorWasEnabled) GL11.glEnable(GL11.GL_SCISSOR_TEST)` to AFTER
`compositeRT.blitAndBlendToTexture()`. Also added defensive scissor disable at HUD_CAPTURE
entry (in case EYE_RENDER ever leaves scissor enabled).

#### Testing Checklist

- [x] Toggle stereo with `\` key (backslash)
- [x] Entities render with stereo parallax
- [x] Blocks render with stereo parallax (off-axis projection)
- [x] Both eyes show world with parallax
- [x] HUD appears on both halves (alpha composited)
- [x] JourneyMap minimap visible in stereo
- [x] Inventory/screens work correctly
- [ ] Dynamic convergence: look at close block → convergence decreases
- [ ] Dynamic convergence: look at sky → falls back to static config
- [ ] Eye-ray entity convergence: entity between eyes triggers convergence even when center crosshair misses
- [ ] Crosshair has convergence offset
- [x] No crashes on window resize (phase-gated getWidth fix)
- [x] Stereo disabled on world quit (ClientPlayerNetworkEvent.LoggingOut)
- [x] Head tracking via OSC with anchored dead zone
- [x] Mouse works on both eye halves in GUI/crafting
- [x] Jade / HUD mods: no black screen (glClearColor transparent before HUD phase)
- [x] Entity outlines: doEntityOutline() runs per-eye, cancelled in HUD_CAPTURE
- [x] Sophisticated Storage: full GUI renders without tooltip
