You have:

3-axis gyro (24-bit signed)

3-axis accel (24-bit signed)

3-axis mag (16-bit signed)

Timestamp in nanoseconds

Per-frame scale factors

That’s everything needed for full 9-DoF orientation → quaternion.

Below is the practical pipeline.

1️⃣ Decode and Scale Raw Values
A. Convert 24-bit signed to int32

For gyro/accel (3 bytes):

int32_t s24_to_s32(uint8_t b0, uint8_t b1, uint8_t b2) {
    int32_t value = (b0 << 16) | (b1 << 8) | b2;
    if (value & 0x800000)
        value |= 0xFF000000;   // sign extend
    return value;
}

B. Apply scale factors

You have:

physical_value = raw * multiplier / divisor


So:

gyro_rad_s  = raw_gyro  * gyro_mult  / gyro_div
accel_m_s2  = raw_accel * accel_mult / accel_div
mag_units   = raw_mag   * mag_offset / mag_div   (depends on calibration)


Make sure gyro ends up in radians per second.
If it's in degrees/sec, convert:

rad = deg * π / 180

2️⃣ Compute Δt (Critical)

From timestamp:

dt = (timestamp_now - timestamp_prev) * 1e-9


At 1000 Hz, dt ≈ 0.001 s.

Never assume constant dt. Use timestamp.

3️⃣ Orientation from Gyro (Core Integration)

Quaternion differential equation:

𝑞
˙
=
1
2
𝑞
⊗
𝜔
q
˙
	​

=
2
1
	​

q⊗ω

Where angular velocity quaternion:

𝜔
=
(
0
,
𝜔
𝑥
,
𝜔
𝑦
,
𝜔
𝑧
)
ω=(0,ω
x
	​

,ω
y
	​

,ω
z
	​

)

Discrete integration:

q = q + 0.5 * q ⊗ ω * dt
normalize(q)


In code:

// q = [w, x, y, z]
void integrate_gyro(float q[4], float gx, float gy, float gz, float dt)
{
    float qw = q[0], qx = q[1], qy = q[2], qz = q[3];

    float half_dt = 0.5f * dt;

    q[0] += (-qx*gx - qy*gy - qz*gz) * half_dt;
    q[1] += ( qw*gx + qy*gz - qz*gy) * half_dt;
    q[2] += ( qw*gy - qx*gz + qz*gx) * half_dt;
    q[3] += ( qw*gz + qx*gy - qy*gx) * half_dt;

    normalize(q);
}


This alone gives fast but drifting orientation.

4️⃣ Correct Drift with Accel + Mag

Gyro alone drifts.
You must fuse sensors.

You have two good options:

✅ Option A (Recommended): Madgwick Filter

Very efficient.
Works great at 1000 Hz.
Easy to implement.
Widely used in VR.

Search: Madgwick AHRS algorithm.

Core idea:

Use gyro for fast integration

Use accel to correct pitch/roll

Use magnetometer to correct yaw

You just feed:

gx, gy, gz
ax, ay, az
mx, my, mz
dt


Output: quaternion.

This is what most AR/VR devices use internally.

✅ Option B: Mahony Filter

Similar but slightly different tuning.
Also widely used.

⚠️ DO NOT:

Use Euler angles

Integrate yaw separately

Ignore normalization

Assume gravity vector is constant without filtering

5️⃣ If You Want Minimal Implementation (No Mag)

If you only need stable head orientation and don't care about yaw drift:

Use 6-DoF fusion:

Use gyro integration

Correct only using gravity from accelerometer

Steps:

Normalize accel:

a_norm = accel / |accel|


Predict gravity from quaternion

Compute error = cross(predicted_gravity, measured_gravity)

Feed error back into gyro (small correction term)

This is basically Mahony filter.

6️⃣ Coordinate System

You must confirm:

Is device right-handed?

Axis directions?

Does +X point right?

Does +Z point forward?

If wrong, quaternion will look mirrored.

Test:

Rotate head left → yaw sign?

Tilt forward → pitch sign?

Adjust signs if needed.

7️⃣ Practical Architecture

At 1000 Hz:

read packet
decode
scale
compute dt
run fusion
output quaternion


Do NOT:

allocate memory

print logs

block thread

Keep loop deterministic.

8️⃣ Output Quaternion Meaning

Final quaternion represents:

Device orientation relative to starting frame


To get head-facing vector:

forward = q * (0,0,-1) * q_conjugate
