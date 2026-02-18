# Head Tracking Bridge (`ht`) — Specification

## 1. Overview

Standalone Rust application bridging Xreal Air 2 AR glasses IMU to the Beeeye Minecraft mod.
Uses iOS face tracker stream for drift correction via statistical center estimation.

## 2. Data Flow

```
iOS face tracker (phone) ──UDP:8002──→ ht ──UDP:8001──→ Beeeye mod (Minecraft)
Xreal Air 2 (USB-C) ──ar-drivers──→ ht ↗
```

- **Port 8001** — ht sends fused orientation quaternion to Beeeye mod
- **Port 8002** — ht listens for iOS face tracker quaternion (drift calibration reference)
- **USB** — ar-drivers-rs reads IMU at 1000 Hz from glasses

## 3. Sensor Input

- **Hardware:** Xreal Air 2 via USB-C
- **Driver:** [ar-drivers-rs](https://github.com/badicsalex/ar-drivers-rs) community crate
- **Events:** `GlassesEvent::AccGyro` — accelerometer + gyroscope at ~1000 Hz
- **Magnetometer:** received but unused (unreliable indoors)
- **Coordinate system:** ar-drivers RUB — X=Right, Y=Up, Z=Back

## 4. Sensor Fusion: Mahony Complementary Filter

Fuses gyroscope and accelerometer into orientation quaternion.

### 4.1 State

- `q` — orientation quaternion (UnitQuaternion)
- `neutral` — calibrated "looking at screen" IMU orientation

### 4.2 Algorithm

Each IMU sample:
1. **dt** from `Instant::now()` deltas (never assumed fixed)
2. **Accel tilt correction** — cross product of measured vs predicted gravity direction
   - Gated: only applied when `|‖accel‖ - 9.81| < 2.0 m/s²`
   - **Pitch gate**: suppressed when pitch > 70° to prevent gimbal lock corruption.
     Fades linearly from 49° to 70°.
3. **Corrected gyro** — `w = gyro + error * KP` (no bias integration — FT handles long-term)
4. **Quaternion integration** — axis-angle rotation from `w * dt`
5. **Continuous FT correction** (if data available, see §5)
6. **Output** — delta quaternion: `neutral⁻¹ * q` (rotation relative to calibrated center)

### 4.3 Output: Delta Quaternion

The fusion system outputs a **delta quaternion** — the rotation from the calibrated
"neutral" orientation to the current head position. Near identity when looking
straight ahead.

- `neutral` is set on first FT sample and continuously corrected on every FT sample
- Face tracker smoothly absorbs drift by slerping `neutral` toward its target
- Beeeye receives a clean delta that represents actual head movement, drift-free

### 4.4 Tuning Constants

| Parameter | Value | Description |
|-----------|-------|-------------|
| `KP` | 0.3 | Proportional gain — accel tilt correction |
| `GRAVITY` | 9.81 | Expected gravity magnitude (m/s²) |
| `ACCEL_GATE` | 2.0 m/s² | Reject accel when ‖a‖ deviates from g |
| `PITCH_GATE` | 70.0 deg | Suppress accel correction above this pitch |
| `DEFAULT_DT` | 0.001 s | Fallback dt for first sample |
| `FT_CORRECTION_ALPHA` | 0.02 | Slerp rate per FT sample (~1.2s time constant at 60Hz) |

### 4.5 Initialization

First accelerometer reading aligns orientation so gravity matches Y-up
via `UnitQuaternion::rotation_between(accel, [0,1,0])`. Initial `neutral` set to this orientation.

## 5. Face Tracker Drift Correction (Port 8002)

iOS face tracker app sends quaternion stream to UDP port 8002.
Face tracker provides continuous drift correction for all axes.
IMU provides fast response (1000 Hz), face tracker provides long-term stability (~60 Hz).

### 5.1 Listener (`logger.rs`)

- Daemon thread, binds `0.0.0.0:8002`
- 10-second receive timeout — logs warning if no data arrives
- Parses same OSC protocol as Beeeye (x, y, z, w — w triggers quaternion push)
- Latest quaternion shared with main thread via `Arc<Mutex<Option<[f32;4]>>>`
- Main loop polls via `take()` on each IMU sample (lock-free fast path when None)

### 5.2 Calibration Reference

On first face tracker sample, record reference pair:
- `ft_ref` = face tracker orientation at calibration
- `neutral` = IMU orientation at calibration (set to current `q`)

### 5.3 Continuous Correction

On every face tracker sample:
1. Compute FT delta: `ft_delta = ft_ref⁻¹ * ft_current` (what FT says head moved)
2. Compute target neutral: `target = q * ft_delta⁻¹` (where neutral should be so `delta = ft_delta`)
3. Slerp: `neutral = slerp(neutral, target, 0.02)` — smoothly absorbs drift

This runs on every FT sample (~60 Hz), not just on transitions. The slow slerp
(alpha=0.02, ~1.2s time constant) smooths out face tracker noise while still
correcting IMU drift within seconds.

When face tracker goes silent (face lost during fast turns), IMU runs free.
Drift accumulates but gets corrected as soon as FT returns.

### 5.4 Coordinate Convention

Face tracker quaternion [x, y, z, w] is converted to IMU frame by negating x, y, z
(inverse of the sign negation applied in OSC output, see §7).

## 6. Display Mode Auto-Switch

On startup and on `ProximityNear` event (glasses put on), `ht` checks the display mode.
If not already in a stereo mode, switches to full SBS stereo (`DisplayMode::Stereo`).

- Uses `ar_drivers::ARGlasses::get_display_mode()` / `set_display_mode()`
- Triggers: program start + every `ProximityNear` event
- Already-stereo modes (`Stereo`, `HighRefreshRateSBS`) are left untouched
- All other modes (`SameOnBoth`, `HalfSBS`, `HighRefreshRate`) trigger switch
- Glasses connection: retries every 2s if not found at startup
- Logs the mode transition or current mode

Available modes (Xreal Air 2):

| Mode | Resolution | Description |
|------|-----------|-------------|
| `SameOnBoth` | 1920x1080 | Mirror/2D (default) |
| `Stereo` | 3840x1080 | Full SBS, 60Hz |
| `HalfSBS` | 1920x1080 (960x540 per eye) | Half SBS, 60Hz |
| `HighRefreshRate` | 1920x1080 | Mirror, 120Hz |
| `HighRefreshRateSBS` | 3840x1080 | SBS, 90Hz |

## 7. OSC Output (Port 8001)

**Delta quaternion** sent as 4 separate OSC float messages per IMU sample to `--host:--port`.
The delta represents head rotation relative to calibrated neutral — near identity when
looking straight ahead, drift-corrected on each face tracker recalibration.

| OSC Address | Value |
|-------------|-------|
| `/data/faceTracking/face/rotation/x` | `-delta.i` |
| `/data/faceTracking/face/rotation/y` | `-delta.j` |
| `/data/faceTracking/face/rotation/z` | `-delta.k` |
| `/data/faceTracking/face/rotation/w` | `delta.w` (sent last, triggers update in Beeeye) |

All three spatial components negated — maps ar-drivers RUB to Beeeye's expected convention.

## 8. CLI

```
cargo run -- [--host <HOST>] [--port <PORT>] [--ft-port <PORT>] [--log]
```

| Option | Default | Description |
|--------|---------|-------------|
| `--host` | 127.0.0.1 | OSC target host |
| `--port` | 8001 | OSC target port (Beeeye mod) |
| `--ft-port` | 8002 | Face tracker listen port (0 to disable) |
| `--log` | off | Write sensor data CSV to stdout (~50 Hz) |

Ctrl+C for graceful shutdown.

## 9. Diagnostics

- Startup: prints connected glasses name, OSC target, face tracker listen port
- Every 10 seconds: delta quaternion + absolute quaternion + last face tracker quaternion
- Face tracker timeout: `no messages on :8002 for Ns` warning
- Face tracker recalibration: logs drift absorbed (degrees) and updated neutral
- Display mode: logs mode check and switch on startup / ProximityNear
- `--log` flag: writes CSV at ~50 Hz with columns:
  `t,dx,dy,dz,dw,abs_x,abs_y,abs_z,abs_w,ft_x,ft_y,ft_z,ft_w,gx,gy,gz,acx,acy,acz`

## 10. Project Structure

```
headtracking/
├── Cargo.toml
├── SPEC.md              # this file
├── src/
│   ├── main.rs          # event loop: ar-drivers → fusion → OSC
│   ├── fusion.rs        # Mahony filter + face tracker recalibration
│   ├── osc.rs           # OSC UDP sender with coordinate remapping
│   └── logger.rs        # face tracker OSC listener (port 8002)
└── client/
    ├── Cargo.toml
    └── src/main.rs      # debug OSC listener (prints to stdout)
```

## 11. Dependencies

| Crate | Purpose |
|-------|---------|
| `ar-drivers` | Xreal Air 2 USB HID driver (git dep) |
| `nalgebra` | Linear algebra (quaternions, matrices) |
| `rosc` | OSC protocol encoding/decoding |
| `clap` | CLI argument parsing |
| `ctrlc` | Graceful shutdown |
| `anyhow` | Error handling |
