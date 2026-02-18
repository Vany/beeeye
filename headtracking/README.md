# ht

Head tracking bridge for Xreal Air 2 AR glasses. Reads IMU sensor data from the glasses, runs it through a quaternion Extended Kalman Filter, and streams the orientation over OSC to the [Beeeye](https://github.com/Vany/beeeye) Minecraft stereoscopic 3D mod.

Put the glasses on, launch Minecraft with Beeeye, run `ht` — you get real head tracking in-game.

## How it works

```
Xreal Air 2 (USB) → ar-drivers → EKF sensor fusion → OSC UDP → Beeeye mod
```

- **IMU at 1000 Hz** — accelerometer + gyroscope read via [ar-drivers-rs](https://github.com/badicsalex/ar-drivers-rs)
- **7-state EKF** — quaternion orientation (4) + gyro bias estimation (3), accelerometer gravity correction with adaptive gating
- **OSC output** — quaternion sent as 4 float messages to `localhost:8001`

## Usage

```
cargo run
```

Options:
```
--host <HOST>   OSC target host [default: 127.0.0.1]
--port <PORT>   OSC target port [default: 8001]
```

## Requirements

- Xreal Air 2 glasses connected via USB-C
- Rust toolchain
- macOS (tested), Linux (should work)
