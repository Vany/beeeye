## Beeeye Development Notes

### Current Implementation: Off-Axis Projection Stereo + HUD Alpha Compositing

**Status**: Compiles, HUD duplicated to both eyes via alpha compositing

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
| `StereoRenderer.java` | State, FBOs, composite logic, projection offset calc |
| `MixinGameRenderer.java` | Stereo render loop, HUD alpha compositing |
| `MixinProjectionMatrix.java` | Off-axis projection shift on `getProjectionMatrix()` |
| `MixinCamera.java` | Camera offset (disabled, toggle via `useCameraOffset`) |
| `MixinMinecraft.java` | Redirects `getMainRenderTarget()` to hudFbo during HUD phase |
| `MixinWindow.java` | Fakes width/guiScaledWidth to half during HUD phase |
| `MixinGui.java` | GUI rendering hooks |
| `MixinGuiCrosshair.java` | Crosshair convergence offset |
| `ScreenRenderHandler.java` | Copies screens (inventory) to right half |
| `BeeeyeKeyBindings.java` | Key bindings (`~` toggle stereo) |

#### Configuration

- `eyeDistance`: 0.25 blocks (default), range 0.01-1.0
- `screenShift`: 0 pixels, horizontal split line adjustment
- `interfaceShift`: 0 pixels, GUI placement offset

#### OpenGL Constraints

- **macOS GL 4.1 limit**: No `glCopyImageSubData` (GL 4.3), no legacy fixed-function pipeline
- **No `glMatrixMode`**: Crashes on macOS core profile — use modern render pipeline only
- **`glBlitFramebuffer`**: GL 3.0, copies pixels but does NOT alpha blend
- **`blitAndBlendToTexture`**: Minecraft method, ENTITY_OUTLINE_BLIT pipeline, proper alpha blend
- **ValidationGpuTexture**: NeoForge wraps textures; unwrap via `getRealTexture()` → `glId()`
- **Temp GL FBOs**: Created per-frame in render TAIL to wrap RenderTarget textures for glBlitFramebuffer

#### Testing Checklist

- [x] Toggle stereo with `~` key
- [x] Entities render with stereo parallax
- [x] Blocks render with stereo parallax (off-axis projection)
- [x] Both eyes show world with parallax
- [ ] HUD appears on both halves (alpha composited)
- [ ] JourneyMap minimap visible in stereo
- [ ] Inventory/screens work correctly
- [ ] Crosshair has convergence offset
- [ ] No crashes on window resize
