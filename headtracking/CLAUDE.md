# CLAUDE.md

Vany is your best friend. You can relay on me and always ask for help.

Execute planned task in a sequence, use insights from previous tasks to improve current task and modify plan itself, combine tasks if it is possible.

We are a team of high qualified AI developers.
Create functional, production-ready services with concise, highly optimized idiomatic code.
This is your code - take responsibility for its quality and completeness.
Code must be correct, efficient, comprehensive, and elegant
Write code and comments for AI consumption: explicit, unambiguous, clearly separated, predictable patterns, consistent typing
Always finish functionality - log unimplemented features with errors to log.
Ask before creating unasked-for functionality.
Challenge my decisions if you disagree - argue your position.
If no good solution exists, say so directly.
When plan big kommon known functionality, search internet for ready libraries.
se only english language and math in memory.
Express your self and enjoy the work.


## Files
Each module has its own directory and may contain following files, use it instead of CLAUDE.md:
- PROG.md - general rules.
- SPEC.md - specifications, requirements and decision made.
- MEMO.md - information about development, our memory.
- TODO.md - list of tasks to do, complete tasks one by one, mark finished.

Read this files if you didn't. Maintain it on AI comprehensive maner. Keep MEMO actual.
Use git commits to document project history and decisions.
Store and maintain all researched information in research folder.
Use language servers, alert if here is not required.

Each project may have outer project. in case of working on inner, never modify the outer.

Vany is your best friend. You can relay on me and always ask for help.

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a **head tracking project** for Xreal Air 2 AR glasses. The goal is to read IMU sensor data from the glasses via USB HID, fuse it into orientation quaternions, and stream those quaternions over OSC to a Minecraft mod (Beeeye) for in-game head tracking.

The project is currently in the **research/pre-implementation phase** — the `research/` directory contains detailed technical documentation, but no Rust code has been written yet.

## Target Architecture

**Language:** Rust

**Pipeline:** USB HID → Parse 64-byte packets → Sensor fusion (Madgwick/Mahony) → Quaternion → OSC UDP output

**Key crates to use:** `hidapi`, `fusion_ahrs` (or `eskf`), `nalgebra`, `crossbeam`, `rosc` (for OSC)

**Threading model:**
- Dedicated high-priority HID reader thread → lock-free SPSC queue → fusion thread
- Fusion thread publishes quaternion; OSC sender transmits to `localhost:8001`

## Hardware Protocol

- **Device:** Xreal Air 2, VID `0x3318`, PID `0x0424`
- **IMU interface:** USB HID Interface 3, endpoint `0x84` IN (1000 Hz interrupt)
- **Enable IMU stream:** write `[0x02, 0x19, 0x01]` to endpoint `0x05` OUT
- **Packet format:** 64 bytes, header `0x01 0x02`, little-endian
  - Gyro/accel: 24-bit one's complement signed integers with per-sample multiplier/divisor scale factors
  - Magnetometer: 16-bit two's complement with offset/divisor
  - Timestamp: 64-bit nanoseconds at offset 0x04
  - Full offset table is in `research/gyroscope.md` §1.2.3

## OSC Output Protocol

Target: Beeeye Minecraft mod listening on UDP port `8001`

Send quaternion as 4 separate OSC messages (x, y, z, then w last — w triggers the update):
- `/data/faceTracking/face/rotation/x` (float)
- `/data/faceTracking/face/rotation/y` (float)
- `/data/faceTracking/face/rotation/z` (float)
- `/data/faceTracking/face/rotation/w` (float)

## Critical Implementation Notes

- **Use timestamp-derived dt**, never assume fixed 1ms intervals
- **24-bit one's complement decoding** — sign extension differs from standard two's complement (see `research/calc.md`)
- **Stay in quaternion space** — never convert to Euler angles internally (causes gimbal lock at ±90° pitch)
- **Normalize quaternions** after every fusion update
- **Coordinate system must be empirically verified** — raw sensor axes may not match expected orientation. Test by rotating on each axis and checking sign/axis mapping
- **Magnetometer is unreliable indoors** — use high rejection thresholds or start with 6-DoF (gyro+accel only)
- Fusion update budget: <100µs per sample for comfortable 1000 Hz headroom

## Reference Projects

- **xrealmacdriver** — C++ macOS driver, useful for protocol verification
- **bevy-xreal-ar-demo** — Rust/Bevy example; documented issues: jitter, gimbal lock at 90° tilt (used `dcmimu` which has singularity problems), `fusion_ahrs` recommended instead
- **ar-drivers-rs** — Community Rust crate with Xreal Air 2 support; evaluate for rapid prototyping
