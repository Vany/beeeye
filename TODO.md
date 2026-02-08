## Backlog

- dynamic convergence testing (close block, sky fallback, eye-ray entity/block)
- crosshair convergence offset
- verify config write-through: change param via `/beeeye set`, restart game, value persists

## Done

- [x] stereo render pipeline (off-axis projection)
- [x] HUD alpha compositing to both eyes
- [x] mouse coord translation for right eye
- [x] `/beeeye` command for runtime config
- [x] dynamic convergence (raycast-driven)
- [x] eye-ray entity/block convergence (binocular picking)
- [x] config write-through (`BeeeyeConfig.save()` after each set)
- [x] replace `convergenceSmoothing` with `convergenceSpeed` (tick-based)
- [x] disable stereo on world quit (LoggingOut event)
- [x] head tracking via OSC (quaternion from data OSC app)
- [x] head tracking smoothing (nlerp factor 0.4)
- [x] anchored dead zone (configurable, default 2 degrees)
- [x] head tracking disabled in mono mode
- [x] stereo mouse: both eye halves interactive (GUI/crafting)
- [x] window resize fix: getWidth() only faked during EYE_RENDER/HUD_CAPTURE
- [x] code optimization: immutable Quat record, RenderPhase state machine
- [x] code extraction: BodyCrosshair, GlTextureUtil classes
- [x] GlFboCache boxing elimination (int[] arrays)
- [x] rename LIVELINK_PORT → OSC_PORT everywhere
