use std::time::Instant;

use nalgebra::{UnitQuaternion, Vector3};

// Mahony complementary filter gains
const KP: f64 = 0.3; // proportional gain — accel tilt correction

const GRAVITY: f64 = 9.81;
const ACCEL_GATE: f64 = 2.0; // reject accel when |‖a‖ - g| exceeds this (m/s²)
const PITCH_GATE: f64 = 70.0; // degrees — suppress accel correction above this pitch
const DEFAULT_DT: f64 = 0.001;

// Continuous face tracker drift correction
// Each FT sample (~60 Hz), slerp neutral toward where it should be.
// 0.02 per sample → ~1.2s time constant for full correction.
const FT_CORRECTION_ALPHA: f64 = 0.02;

// ZUPT (Zero-Velocity Update) — gyro bias estimation
// When stationary, raw gyro = bias. EMA converges in ~1000 samples (~1s at 1kHz).
const GYRO_STATIONARY_THRESH: f64 = 0.1; // rad/s (~6°/s) — below = stationary
const BIAS_ALPHA: f64 = 0.001; // EMA weight per sample

/// Output of the fusion system
pub struct FusionOutput {
    /// Delta quaternion: rotation from calibrated neutral to current head orientation.
    /// Near identity when looking straight ahead. This is what gets sent over OSC.
    pub delta: UnitQuaternion<f64>,
    /// Absolute IMU quaternion (for logging/diagnostics only)
    pub absolute: UnitQuaternion<f64>,
}

pub struct Fusion {
    // Mahony filter state
    q: UnitQuaternion<f64>,
    last_time: Option<Instant>,
    initialized: bool,

    // Calibration: "neutral" IMU orientation (looking straight at screen)
    // delta = neutral.inverse() * q
    neutral: UnitQuaternion<f64>,

    // Face tracker reference pair (recorded on first FT sample)
    ft_calibrated: bool,
    ft_ref: UnitQuaternion<f64>, // FT orientation at calibration

    // ZUPT gyro bias estimate (EMA, updated only when stationary)
    bias: Vector3<f64>,
}

impl Fusion {
    pub fn new() -> Self {
        Self {
            q: UnitQuaternion::identity(),
            last_time: None,
            initialized: false,
            neutral: UnitQuaternion::identity(),
            ft_calibrated: false,
            ft_ref: UnitQuaternion::identity(),
            bias: Vector3::zeros(),
        }
    }

    /// Reset filter state after device reconnect. Clears integration history so
    /// stale orientation doesn't corrupt the new session.
    pub fn reset(&mut self) {
        *self = Self::new();
        eprintln!("fusion: reset after reconnect");
    }

    fn init_from_accel(&mut self, accel: &Vector3<f64>) {
        let a = accel.normalize();
        let up = Vector3::new(0.0, 1.0, 0.0);
        if let Some(r) = UnitQuaternion::rotation_between(&a, &up) {
            self.q = r;
            self.neutral = r;
        }
        self.initialized = true;
    }

    /// Returns delta quaternion (head rotation relative to neutral) and absolute orientation.
    pub fn update(
        &mut self,
        gyro: [f32; 3],
        accel: [f32; 3],
        ft_quat: Option<[f32; 4]>,
    ) -> FusionOutput {
        let a = Vector3::new(accel[0] as f64, accel[1] as f64, accel[2] as f64);

        if !self.initialized {
            self.init_from_accel(&a);
        }

        let now = Instant::now();
        let dt = if let Some(prev) = self.last_time {
            let d = now.duration_since(prev).as_secs_f64();
            if d > 0.0 && d < 0.1 {
                d
            } else {
                DEFAULT_DT
            }
        } else {
            DEFAULT_DT
        };
        self.last_time = Some(now);

        let gyro = Vector3::new(gyro[0] as f64, gyro[1] as f64, gyro[2] as f64);
        let a_norm = a.norm();

        // ZUPT: when stationary, raw gyro ≈ bias. Estimate via EMA.
        let stationary = gyro.norm() < GYRO_STATIONARY_THRESH
            && (a_norm - GRAVITY).abs() < 1.0;
        if stationary {
            self.bias = self.bias * (1.0 - BIAS_ALPHA) + gyro * BIAS_ALPHA;
        }
        let gyro = gyro - self.bias;

        // --- Mahony filter (KP only, long-term yaw drift handled by ZUPT + FT) ---
        let mut error = Vector3::zeros();
        if (a_norm - GRAVITY).abs() < ACCEL_GATE && a_norm > 1e-6 {
            let q = self.q.quaternion();
            let sin_pitch = 2.0 * (q.w * q.i - q.j * q.k);
            let pitch_deg = sin_pitch.asin().to_degrees().abs();

            if pitch_deg < PITCH_GATE {
                let a_dir = a / a_norm;
                let g_pred = self.q.inverse() * Vector3::new(0.0, 1.0, 0.0);
                error = a_dir.cross(&g_pred);

                if pitch_deg > PITCH_GATE * 0.7 {
                    let fade = 1.0 - (pitch_deg - PITCH_GATE * 0.7) / (PITCH_GATE * 0.3);
                    error *= fade;
                }
            }
        }

        let w = gyro + error * KP;

        let angle = w.norm() * dt;
        if angle > 1e-12 {
            let axis = w.normalize();
            let dq = UnitQuaternion::from_axis_angle(&nalgebra::Unit::new_normalize(axis), angle);
            self.q = self.q * dq;
        }

        // --- Continuous face tracker correction ---
        if let Some(ft) = ft_quat {
            self.process_face_tracker(ft);
        }

        let delta = self.neutral.inverse() * self.q;

        FusionOutput {
            delta,
            absolute: self.q,
        }
    }

    fn ft_to_quat(ft_raw: [f32; 4]) -> UnitQuaternion<f64> {
        UnitQuaternion::new_normalize(nalgebra::Quaternion::new(
            ft_raw[3] as f64,
            -ft_raw[0] as f64,
            -ft_raw[1] as f64,
            -ft_raw[2] as f64,
        ))
    }

    fn process_face_tracker(&mut self, ft_raw: [f32; 4]) {
        let ft_q = Self::ft_to_quat(ft_raw);

        if !self.ft_calibrated {
            self.ft_ref = ft_q;
            self.neutral = self.q;
            self.ft_calibrated = true;
            eprintln!(
                "fusion: ft calibrated — ft=({:.3},{:.3},{:.3},{:.3}) imu=({:.3},{:.3},{:.3},{:.3})",
                ft_q.i, ft_q.j, ft_q.k, ft_q.w,
                self.q.i, self.q.j, self.q.k, self.q.w,
            );
            return;
        }

        // What face tracker says head moved since calibration
        let ft_delta = self.ft_ref.inverse() * ft_q;
        // Where neutral should be so that delta = ft_delta
        // delta = neutral⁻¹ * q = ft_delta → neutral = q * ft_delta⁻¹
        let target_neutral = self.q * ft_delta.inverse();
        // Slerp neutral toward target — smooths out FT noise
        self.neutral = self.neutral.slerp(&target_neutral, FT_CORRECTION_ALPHA);
    }
}
