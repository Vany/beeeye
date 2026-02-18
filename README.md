# Beeeye

Stereoscopic 3D rendering mod for Minecraft 1.21.1 (NeoForge).
Compatible with all other mods done right.
Renders the game in side-by-side stereo format for use with AR glasses, 3D monitors, or cross-eye/parallel viewing.

## Features

- True stereoscopic 3D with depth parallax for blocks, entities, and particles
- Side-by-side output (left eye | right eye)
- HUD and GUI rendered correctly in both eyes
- Dynamic convergence with binocular eye-ray picking
- Head tracking via OSC (data OSC app or Rust `ht` bridge for Xreal Air 2) with dual dead zones
- Head-tracked interactions: break/place/hit follows where you look, not where mouse points
- Stereo mouse: GUI/crafting works on both eye halves

## Requirements

- Minecraft 1.21.11
- NeoForge 21.1+
- Java 21

## Installation

1. Install NeoForge for Minecraft 1.21.11
2. Download the latest Beeeye release
3. Place the `.jar` file in your `mods` folder
4. Launch the game

## Usage

Press `\` (backslash) to toggle stereo mode on/off.

The keybind can be changed in Options > Controls > Key Binds > Beeeye.

### Commands

| Command | Description |
|---------|-------------|
| `/beeeye` | Show current status and settings |
| `/beeeye toggle` | Toggle stereo on/off |
| `/beeeye set eyedistance <value>` | Set eye distance (0.01-1.0 blocks) |
| `/beeeye set convergence <value>` | Set convergence distance (1.0-50.0 blocks) |
| `/beeeye set dynamicconvergence <true\|false>` | Toggle dynamic convergence |
| `/beeeye set speed <ticks>` | Set convergence speed (1-40 ticks) |
| `/beeeye set oscport <port>` | Set OSC listening port (1024-65535) |
| `/beeeye set deadzone <degrees>` | Set head tracking dead zone (0.0-15.0) |
| `/beeeye calibrate` | Re-calibrate head tracking neutral position |

All settings (except stereo toggle) are saved to disk immediately and persist across restarts.

## Configuration

Config file: `.minecraft/config/beeeye-client.toml`

### eyeDistance (default: 0.25, range: 0.01-1.0)

Inter-pupillary distance in blocks. Each eye is offset by `eyeDistance / 2` in the projection matrix.

Controls the **strength of the 3D effect**. Higher values produce more pronounced depth separation between near and far objects. Lower values produce a subtler, more comfortable effect.

- **0.05-0.15**: Subtle, comfortable for long sessions
- **0.20-0.30**: Natural, good starting point
- **0.40-1.00**: Exaggerated 3D, can cause eye strain

**Interaction with convergence**: The ratio `eyeDistance / convergence` determines the maximum parallax angle. If `eyeDistance` is large relative to `convergence`, nearby objects will have extreme separation between left/right images, which can be uncomfortable or impossible to fuse. Rule of thumb: keep `eyeDistance` below `convergence / 10` for comfortable viewing.

### convergence (default: 5.0, range: 1.0-50.0)

The zero-parallax distance in blocks. Objects at this distance appear exactly at screen depth. Objects closer appear to pop out in front of the screen. Objects farther appear behind the screen.

When `dynamicConvergence` is enabled, this value is used as the **fallback** when all rays miss (looking at empty sky).

- **1.0-3.0**: Very close convergence. Strong pop-out for nearby objects, but faraway terrain has large parallax that may be hard to fuse. Good for indoor/cave builds.
- **4.0-8.0**: General purpose. Comfortable for mixed near/far content.
- **10.0-50.0**: Far convergence. Most objects appear behind the screen. Minimal pop-out. Good for panoramic views.

**Interaction with eyeDistance**: If convergence is too low relative to eyeDistance, objects at medium distance will have excessive parallax. Example: `eyeDistance=0.5` + `convergence=1.0` means an object 10 blocks away has 5x the parallax of the convergence point, which is likely unfusable.

### dynamicConvergence (default: true)

When enabled, the convergence distance automatically adjusts to what you're looking at:

1. **Center crosshair hit**: If the crosshair points at a block or entity, convergence moves to that distance.
2. **Eye-ray fallback**: If the center crosshair misses (looking past an entity, at sky), two additional rays are cast from each eye's position toward the current convergence point. Both blocks and entities are checked per eye ray. The closest hit across all rays wins. This catches entities or blocks visible to either eye even when the crosshair misses.
3. **Static fallback**: If all rays miss, falls back to the `convergence` config value.

When disabled, convergence is fixed at the `convergence` value.

**Interaction with convergenceSpeed**: Dynamic convergence with `speed=1` causes instant jumps when looking between near/far objects, which is jarring. Use `speed=3-6` for smooth transitions.

### convergenceSpeed (default: 4, range: 1-40)

How fast dynamic convergence transitions to a new target distance, in minecraft ticks (1 tick = 50ms).

The value is the approximate time to reach 95% of the target distance. Internally converted to an exponential lerp factor: `1 - exp(-2.2 / speed)`.

- **1**: Nearly instant (~89% per tick). Snappy but can feel jarring when rapidly looking between near/far objects.
- **3-6**: Smooth, natural feel. Eyes adjust comfortably.
- **10-20**: Slow drift. Cinematic, but convergence lags behind fast head movements.
- **30-40**: Very slow. Convergence barely moves. Only useful for mostly-static scenes.

Only has effect when `dynamicConvergence` is enabled.

**Interaction with gameplay**: In combat, lower values (2-4) help the eyes converge quickly on approaching mobs. For exploration/building, higher values (5-10) feel more relaxed.

### oscPort (default: 8001, range: 1024-65535)

UDP port to listen for OSC face-tracking data. Configure your OSC source app (data OSC) to stream to this port. The listener starts automatically at mod init.

### headDeadzone (default: 2.0, range: 0.0-15.0)

Head tracking dead zone in degrees. Two dead zones work together:

1. **Neutral dead zone**: When head is within this many degrees of calibration center, camera snaps to body direction (zero offset) **instantly**. Provides a stable "look straight ahead" rest position.
2. **Anchored dead zone**: At any other angle, jitter is suppressed around the last stable head position. The dead zone follows where your head last came to rest, not just the calibration center. Anchor locks after **100ms** settle time to prevent snapping when passing through.

- **0**: No dead zone — every micro-movement tracked (jittery)
- **1-2**: Low jitter suppression, responsive
- **3-5**: Good balance of stability and responsiveness
- **5-15**: Very stable, requires deliberate head movement

### Parameter clash summary

| Scenario | Problem | Fix |
|----------|---------|-----|
| High `eyeDistance` + low `convergence` | Extreme parallax at medium distance, unfusable images | Keep `eyeDistance < convergence / 10` |
| `dynamicConvergence` on + `speed=1` | Jarring convergence jumps when looking around | Increase speed to 3-6 |
| `dynamicConvergence` off + low `convergence` | Everything far away has large parallax | Either enable dynamic convergence or increase convergence |
| High `speed` (30+) + fast movement | Convergence lags behind, depth appears wrong | Lower speed for active gameplay |

## Head Tracking

Beeeye supports head tracking via OSC protocol, using the [data OSC](https://apps.apple.com/us/app/data-osc/id6447833736) app.

### Setup

1. Install [data OSC](https://apps.apple.com/us/app/data-osc/id6447833736) from the App Store
2. Configure the app to send to your computer's IP on port 8001 (or change with `/beeeye set oscport`)
3. Enable stereo mode with `\` — head tracking calibrates automatically
4. Look straight ahead when toggling stereo to set the neutral position
5. Use `/beeeye calibrate` to re-calibrate without toggling stereo off/on

### How it works

- **Body** (mouse) controls movement direction and player rotation
- **Head** (OSC tracking) controls camera view and interactions (break/place/hit)
- A `< >` body crosshair shows where the mouse points when head is turned away
- When body crosshair is outside the viewport, a chevron arrow on the edge points toward it
- Head tracking is only active in stereo mode
- No smoothing applied — OSC source is expected to provide filtered data

## How It Works

Beeeye uses **off-axis projection stereo**:

1. Each eye renders to a separate half-width framebuffer
2. Camera position stays the same (avoids chunk cache invalidation)
3. Projection matrix `m20` element is shifted per eye to create an asymmetric frustum
4. Objects at convergence distance have zero parallax; nearer/farther objects diverge
5. Both eye images are composited side-by-side to the screen
6. HUD renders once at half-width, then alpha-composited identically to both eyes

This matches how professional 3D cinema and VR systems work.

## Compatibility

Works with most mods. Tested with JourneyMap.

## License

MIT
