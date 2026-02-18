# Head Orientation Fusion System (EKF-Based)

## Visual--Inertial Quaternion Fusion for 3D Glasses Head Tracking

------------------------------------------------------------------------

## 1. System Overview

This system fuses:

-   High-rate IMU data (\~1000 Hz, gyro + accelerometer)
-   Face tracker quaternion data (50 Hz)

Goal: Produce a low-latency (\<10 ms), stable, drift-corrected head
orientation quaternion for real-time 2D rendering in 3D glasses.

The IMU provides high-speed relative motion. The face tracker provides
absolute orientation reference within a trust region.

------------------------------------------------------------------------

## 2. Design Constraints

IMU rate: \~1000 Hz\
Face rate: 50 Hz\
Magnetometer: None\
Threading: Single-thread\
Face latency: Assumed 0 ms\
Max allowed output latency: 10 ms\
Blend time on re-acquire: 50 ms

Face reliability: - Fully trusted within 30° radius - Face usually lost
at \~45°

------------------------------------------------------------------------

## 3. Coordinate Frames

Define:

Q_world_head → Final output quaternion\
Q_face → Absolute orientation from face tracker\
Q_imu → Relative orientation from IMU integration

We compute calibration offset:

Q_offset = Q_face_initial⁻¹ ⊗ Q_imu_initial

Output reference is "direct view" --- when user looks at monitor.

------------------------------------------------------------------------

## 4. State Vector (Extended Kalman Filter)

State:

x = \[ q_w, q_x, q_y, q_z, (orientation quaternion) b_gx, b_gy, b_gz
(gyro bias)\]

Total dimension: 7

Quaternion is always normalized.

------------------------------------------------------------------------

## 5. Prediction Step (1000 Hz)

Correct gyro:

ω = ω_raw - b_g

Quaternion propagation:

q_dot = 0.5 \* q ⊗ \[0, ω\] q_pred = q + q_dot \* dt normalize(q_pred)

Bias assumed slowly varying:

b_pred = b_previous

------------------------------------------------------------------------

## 6. Accelerometer Update (Gravity Correction)

When acceleration magnitude ≈ g:

Compute gravity direction error:

g_expected = rotate(q_pred, \[0, 0, -1\]) error = cross(g_measured,
g_expected)

Apply small-angle correction inside EKF update.

This corrects pitch and roll drift.

------------------------------------------------------------------------

## 7. Face Tracker Update (50 Hz)

Compute angular difference:

Q_error = q_pred⁻¹ ⊗ Q_face angle = 2 \* acos(Q_error.w)

Trust function:

if angle \<= 30°: trust_weight = 1 elif angle \>= 45°: trust_weight = 0
else: trust_weight = linear interpolation

Measurement update:

Innovation = log(Q_error) Apply EKF correction weighted by trust_weight.

Bias is adapted continuously during trusted intervals.

------------------------------------------------------------------------

## 8. Pure IMU Mode

When trust_weight = 0:

-   No face correction
-   Continue IMU propagation
-   Continue accelerometer correction
-   Bias frozen or slowly adapted

------------------------------------------------------------------------

## 9. Face Reacquire Blending

When trust transitions 0 → 1:

Blend over 50 ms:

q_output = slerp(q_previous, q_corrected, t / 0.05)

Prevents visual snap.

------------------------------------------------------------------------

## 10. Micro Tremor Filtering

Micro tremor detected via angular velocity magnitude:

if \|ω\| \< tremor_threshold:

    Increase smoothing gain
    Apply output low-pass via quaternion SLERP

Adaptive smoothing:

α = clamp(\|ω\| / ω_threshold, α_min, α_max) q_output =
slerp(q_prev_output, q_estimated, α)

------------------------------------------------------------------------

## 11. Static Detection Mode

If:

\|ω\| \< small_threshold AND acceleration stable

Then:

-   Increase bias adaptation gain
-   Increase smoothing
-   Reduce noise significantly

------------------------------------------------------------------------

## 12. Output

Final output:

Q_world_head = normalize(q_output)

Stream immediately (drop older queued samples).

------------------------------------------------------------------------

## 13. Performance Characteristics

Latency: \<10 ms\
IMU-driven response: Immediate\
Face drift correction: ≤50 ms blend\
Yaw drift corrected only via face tracker

------------------------------------------------------------------------

## 14. Failure Modes

Face lost → Pure IMU\
IMU drift → Corrected when face re-enters trust\
Rapid head turn → Automatic trust reduction\
Static mode → Noise suppression

------------------------------------------------------------------------

## 15. Tuning Parameters

FACE_TRUST_START = 30°\
FACE_TRUST_END = 45°\
BLEND_TIME = 50 ms\
MAX_LATENCY = 10 ms\
IMU_RATE = 1000 Hz\
FACE_RATE = 50 Hz

------------------------------------------------------------------------

## 16. Summary

This EKF-based system:

-   Uses IMU as high-speed predictor
-   Uses face quaternion as bounded absolute reference
-   Adapts gyro bias continuously
-   Filters micro tremor
-   Maintains low latency (\<10 ms)
-   Provides stable quaternion output for 3D glasses rendering
