

# Comprehensive AI Programmer Documentation: Minecraft 1.21.11 NeoForge Head Tracking & Face Gesture Mod

## 1. System Architecture Overview

### 1.1 Component Diagram

#### 1.1.1 iPhone with TrueDepth Camera (ARKit Face Tracking Source)

The foundation of this head tracking system is Apple's **TrueDepth camera system**, available on all iPhone models from iPhone X onward (including iPhone 12, 13, 14, 15, and 16 series, plus iPad Pro 3rd generation and later). This specialized hardware array combines an infrared camera, flood illuminator, dot projector, and proximity sensors to project and analyze over 30,000 invisible IR dots, creating a precise depth map of the user's face with sub-millimeter accuracy .

For developers new to iOS and ARKit, the critical insight is that **no custom iOS development is required**. The TrueDepth hardware and ARKit processing framework operate entirely within Apple's closed ecosystem—you cannot directly access the raw IR dot patterns or neural network outputs. Instead, you rely on applications like Face Cap that encapsulate ARKit's `ARFaceTrackingConfiguration` and expose the processed data through standardized network protocols. This abstraction dramatically simplifies your development scope: you need only understand ARKit's output conventions, not its internal implementation.

The tracking data available through this pipeline includes **52 facial blend shape coefficients** (muscle activation weights from 0.0 to 1.0), **6-degree-of-freedom head pose** (position in meters, orientation as Euler angles or quaternion), and **binocular eye gaze directions**. ARKit processes this at 60 Hz on standard iPhones and 120 Hz on Pro models with ProMotion displays, providing temporal resolution adequate for responsive gaming control .

#### 1.1.2 Face Cap App (Free OSC/UDP Streaming Bridge)

**Face Cap by Bannaflak** is the selected iPhone application for this architecture, chosen for its direct OSC/UDP streaming capability, professional motion capture pedigree, and accessible pricing structure . The application is purpose-built for animation and game development workflows, with a clean interface separating capture preview, recording, and live streaming functions.

The application's monetization model merits careful attention for project planning:

| Version | Cost | Streaming Duration | Suitability |
|---------|------|-------------------|-------------|
| Free | $0 | **5-second automatic cutoff** | Development, testing, proof-of-concept |
| Full Unlock | **$5.99** one-time | Unlimited | Production use, streaming, content creation |

The **5-second limitation in the free version** is a hard session timeout—not a data rate limit or feature restriction. After exactly 5 seconds of continuous streaming, the connection terminates and requires manual reactivation. This design enables complete technical validation before purchase: network configuration, OSC parsing, and mod integration can all be verified within the constrained window. However, the interruption frequency makes extended gameplay impractical without the unlock .

Face Cap's output encompasses all data categories needed for this project: head position (`/HT`), head rotation in Euler angles (`/HR`) and quaternion (`/HRQ`) formats, eye rotations (`/ELR`, `/ERR`), and the complete blend shape set via indexed `/W` messages. The application performs internal smoothing configurable through its Settings panel, reducing raw ARKit jitter before network transmission.

#### 1.1.3 Network Layer (Wi-Fi or USB Connection)

Two transport options connect the iPhone to your Minecraft-running computer, with substantially different performance characteristics:

| Method | Typical Latency | Jitter | Setup Complexity | Recommended Use |
|--------|---------------|--------|----------------|---------------|
| **Wi-Fi** | 5–20 ms | Moderate to high | Minimal (same network) | Casual play, development, creative building |
| **USB Tethering** | 1–3 ms | Negligible | Moderate (Personal Hotspot + USB) | Competitive play, PvP, precision aiming |

**Wi-Fi connectivity** requires both devices on the same local network infrastructure. Performance depends heavily on router quality, distance, interference from neighboring networks, and shared medium contention. Modern Wi-Fi 6/6E implementations with dedicated device-to-device protocols (like Apple's AirDrop underlying technology, though not directly used here) can achieve stable sub-10ms performance, but residential environments with multiple streaming devices may experience problematic variability.

**USB tethering** establishes a point-to-point network through the Lightning/USB-C cable. Enable Personal Hotspot in iPhone Settings, connect the USB cable to your computer, then join the iPhone's network from your computer's Wi-Fi settings. The iPhone typically assigns addresses in the 172.20.10.x range. This configuration eliminates radio frequency variability and provides deterministic latency, though it constrains device positioning and may prevent simultaneous charging without a powered hub .

Firewall configuration on the receiving computer is mandatory regardless of transport method. Windows Defender, macOS Application Firewall, and Linux iptables all default to blocking unsolicited inbound UDP packets. Create explicit allow rules for your selected port (default 9000) before attempting connection.

#### 1.1.4 Minecraft NeoForge Mod (Java 21 Receiver & Integration)

The terminal component is a **client-side NeoForge mod** targeting Minecraft 1.21.11 with Java 21 runtime compatibility. This mod implements three integrated subsystems:

1. **Network receiver thread**: Dedicated thread(s) for non-blocking UDP socket operations and OSC message parsing, isolated from Minecraft's main game loop to prevent frame stuttering
2. **Data processing pipeline**: Coordinate transformations, calibration offset application, smoothing filters, and gesture recognition algorithms
3. **Game integration layer**: Camera manipulation through NeoForge's client APIs, input event injection for gesture-triggered actions, and configuration persistence

The client-side-only architecture is critical: **no server modifications are required**, enabling use on any multiplayer server that permits client-side mods. The mod operates entirely within the local player's client, reading tracking data and applying visual/camera effects without affecting server-authoritative game state.

### 1.2 Data Flow Pipeline

#### 1.2.1 Face Capture → ARKit Processing → OSC Message Encoding

At 60–120 Hz, ARKit's neural engine processes TrueDepth camera input to update an `ARFaceAnchor` containing complete pose and expression state. Face Cap receives this through `ARSession` delegate callbacks, applies user-configured smoothing, and immediately encodes into OSC messages. The encoding overhead—address string construction, type tag generation, big-endian float serialization—adds approximately 0.5–1 ms, negligible compared to network latency .

The complete message set for a single frame includes: one position message (`/HT`, 12 bytes payload), one rotation message (`/HR` or `/HRQ`, 12 or 16 bytes), two eye rotation messages (`/ELR`, `/ERR`, 8 bytes each), and up to 52 blend shape messages (`/W`, 8 bytes each if all transmitted). Face Cap's actual bundling strategy is not exhaustively documented; empirical observation or packet capture analysis may be required for optimization.

#### 1.2.2 UDP Transmission → Mod OSC Listener → Game State Updates

UDP's connectionless semantics mean no handshake, no acknowledgment, and no retransmission—stale tracking data has no value, so reliable delivery protocols would introduce delay without benefit. The mod's network thread uses `DatagramSocket.receive()` (blocking) or `DatagramChannel` with selector (non-blocking) to acquire packets, parses OSC content, and deposits structured data in thread-safe containers.

The main game thread, executing at 20 ticks per second (50 ms intervals) for logic and 60+ Hz for rendering, samples the latest available tracking data each frame. This decoupled design—network reception at source frequency, game consumption at display frequency—naturally handles minor packet loss through interpolation and prevents network jitter from directly affecting frame timing.

#### 1.2.3 Separation of Concerns: Head Rotation vs. Facial Gesture Processing

Architectural separation between these two input modalities reflects their fundamentally different temporal characteristics and failure modes:

| Aspect | Head Rotation | Facial Gestures |
|--------|-------------|-----------------|
| **Update frequency** | 60–120 Hz (every frame) | 2–10 Hz (event-driven) |
| **Latency sensitivity** | Extreme (< 50 ms target) | Moderate (100–300 ms acceptable) |
| **Smoothing strategy** | Minimal (EMA α = 0.3–0.5) | Aggressive (hysteresis, debouncing) |
| **Failure mode** | Jitter, drift, motion sickness | False triggers, missed detection |
| **Primary processing** | Direct coordinate transformation | State machine, threshold detection |

Head rotation demands immediate, continuous application to camera orientation with minimal filtering to preserve responsiveness. Facial gestures, conversely, benefit from deliberate hysteresis—requiring sustained activation above threshold, enforcing cooldown periods between triggers, and using pattern recognition (velocity, oscillation) rather than instantaneous values to distinguish intentional gestures from natural expression variation.

---

## 2. iPhone App Setup: Face Cap Installation & Configuration

### 2.1 App Acquisition & Installation

#### 2.1.1 Downloading Face Cap from Apple App Store (Free Version)

Search "Face Cap" in the App Store, verifying developer "Bannaflak" and application description mentioning "ARKit face tracking" and "OSC live mode." The download is approximately 150–200 MB. Upon first launch, grant **Camera permission** when prompted—this is mandatory for ARKit initialization. Microphone permission is optional and only required if using video recording with audio .

#### 2.1.2 Understanding Free Version Limitations (5-Second Live Streaming Cap)

The free version's **5-second streaming limitation** fundamentally shapes development workflow. Plan for iterative test cycles: configure, connect, verify data reception, analyze, disconnect, repeat. For mod development, implement automatic reconnection detection and user notification. For production deployment, budget the **$5.99 unlock** as a necessary infrastructure cost—comparable to or less than a quality gaming mouse, and substantially cheaper than dedicated head tracking hardware.

#### 2.1.3 In-App Purchase Considerations ($5.99 for Unlimited Streaming)

The full unlock is **$5.99 USD** (regional pricing varies), one-time, non-consumable, and restorable across devices sharing your Apple ID. This purchase removes only the duration limit; all tracking capabilities, blend shape resolution, and protocol features are identical between versions. The pricing positions Face Cap as exceptionally accessible compared to professional motion capture systems costing hundreds or thousands of dollars .

### 2.2 Initial Face Tracking Setup

#### 2.2.1 Granting Camera Permissions for TrueDepth Access

If camera permission was previously denied, restore it via iOS Settings → Privacy & Security → Camera → Face Cap. The TrueDepth system requires no separate authorization—standard camera access encompasses depth sensing.

#### 2.2.2 Positioning Guidelines: Optimal Face-to-Camera Distance (30–50cm)

ARKit's effective tracking range is approximately 20–60 cm, with **optimal performance at 30–50 cm** . For desktop Minecraft setups, position the iPhone at the top center of your monitor, angled slightly downward, at approximately arm's length. This naturally places your face in the optimal range when seated normally. Dedicated phone stands or tripod mounts provide stability; handheld use introduces motion artifacts and fatigue.

#### 2.2.3 Lighting Requirements for Reliable ARKit Tracking

The TrueDepth camera's active IR illumination enables operation in darkness, but **moderate ambient lighting (100–500 lux)** improves tracking stability and user comfort. Avoid: direct sunlight or bright point sources (can saturate IR sensor), strong backlighting (causes exposure adjustment artifacts), and flickering light sources near 60/120 Hz (may beat with camera capture). The preview mesh color indicates quality: green/blue = excellent, yellow = marginal, red/absent = failed.

#### 2.2.4 Verifying Face Mesh Detection in Preview Mode

Launch Face Cap and confirm: (1) wireframe mesh overlays your face accurately, (2) mesh deforms with expressions (smile, open mouth, raise eyebrows), (3) mesh orientation follows head rotation smoothly. If mesh is absent or unstable, check: camera lens cleanliness, face fully visible (no hair/ hand occlusion), distance within range, and adequate lighting.

### 2.3 Live Mode OSC Configuration

#### 2.3.1 Accessing the Live Mode Panel (Go Live → Connect/Disconnect)

Tap **"Go Live"** in the main control bar to enter Live Mode. The panel displays connection status, target configuration, and the primary **Connect/Disconnect** control .

#### 2.3.2 Entering Target IP Address (Computer Running Minecraft)

Determine your computer's local IP address:
- **Windows**: `ipconfig` in Command Prompt, look for "IPv4 Address" under active adapter
- **macOS**: `ifconfig` in Terminal, or System Preferences → Network
- **Linux**: `ip addr` or `hostname -I`

Enter this address in dotted-decimal notation (e.g., `192.168.1.50`). For USB tethering, use the assigned address in the 172.20.10.x range. Common errors: entering router's external IP, including port number in IP field, or transposed digits.

#### 2.3.3 Setting the OSC Port Number (Default: 9000, Customizable)

Face Cap defaults to **port 9000**. This must match exactly in your mod configuration. Verify port availability with `netstat -an | findstr 9000` (Windows) or `lsof -i :9000` (macOS/Linux). If occupied, select any unprivileged port (1024–65535) and document your choice consistently.

#### 2.3.4 Connection Verification: Confirming Active Streaming Status

After tapping **Connect**, verify: (1) Face Cap shows green/connected status, (2) packet counter increments, (3) your mod or an OSC monitor (Protokol, TouchOSC Bridge) receives messages. The free version's 5-second cutoff provides natural verification—successful full-duration streaming confirms correct configuration .

#### 2.3.5 Wi-Fi vs. USB Tethering: Network Stability Considerations

For **USB tethering**: Enable Personal Hotspot, connect USB cable, join iPhone network from computer, verify with `ping` to iPhone's displayed IP. This provides sub-5ms latency ideal for competitive scenarios. For **Wi-Fi**: ensure both devices on same network band (5 GHz preferred), minimize distance and obstacles, and avoid high-contention periods (other devices streaming, downloading).

---

## 3. OSC/UDP Protocol Specification

### 3.1 Transport Layer Configuration

#### 3.1.1 UDP as Primary Transport (Low Latency, Connectionless)

Face Cap uses **UDP** for OSC transmission, prioritizing minimal latency over delivery guarantees. This is appropriate because: tracking data has temporal validity (stale samples are useless), 60 Hz update rate means single-packet loss is a 16.7 ms glitch rather than catastrophic failure, and retransmission delays would harm more than help. The mod must handle occasional loss through interpolation and timeout detection .

#### 3.1.2 Recommended Port Range & Firewall Configuration

Default **9000** is widely used for OSC. For alternatives, prefer 49152–65535 (dynamic range) to avoid registered service conflicts. Firewall rules must permit **inbound UDP** on your selected port. On Windows, approve the Java binary when prompted; manually create rules in Windows Defender Firewall with Advanced Security if needed.

#### 3.1.3 Packet Size Considerations for Real-Time Performance

Complete tracking data (all blend shapes) approaches 2 KB per frame. At 60 Hz, this is ~120 KB/s—well within Ethernet/Wi-Fi capacity. However, large UDP datagrams may fragment; Face Cap's actual bundling strategy should be verified. The mod should use receive buffers of 8192 bytes or larger to accommodate maximum expected bundles.

### 3.2 OSC Message Structure: Head Tracking Data

Face Cap's OSC address patterns and argument structures :

| Address | Arguments | Description | Coordinate System |
|---------|-----------|-------------|-----------------|
| `/HT` | 3× float32 | Head position (x, y, z) | Meters, ARKit right-handed |
| `/HR` | 3× float32 | Head rotation Euler (pitch, yaw, roll) | **Degrees**, intrinsic XYZ |
| `/HRQ` | 4× float32 | Head rotation quaternion (x, y, z, w) | Unit quaternion, Hamilton convention |
| `/ELR` | 2× float32 | Left eye rotation (horizontal, vertical) | Degrees relative to head |
| `/ERR` | 2× float32 | Right eye rotation (horizontal, vertical) | Degrees relative to head |

#### 3.2.1 `/HT` — Head Position (3 Floats: x, y, z in meters)

Position relative to session origin (arbitrary, drifts over time). **+X = right, +Y = up, +Z = backward** (away from camera). Typically unused for view direction control but enables future positional features.

#### 3.2.2 `/HR` — Head Rotation Euler Angles (3 Floats: x, y, z in degrees)

**Critical convention**: Intrinsic rotation order **X-Y-Z** (pitch, then yaw, then roll). **Positive pitch = nose up** (looking upward). **Positive yaw = nose left** (turning left). **Positive roll = right ear down** (tilting right). **Gimbal lock occurs at pitch = ±90°**—yaw and roll become indistinguishable. For typical gameplay ranges (±30° pitch), this is not problematic .

#### 3.2.3 `/HRQ` — Head Rotation Quaternion (4 Floats: x, y, z, w)

**Recommended for production use** due to gimbal lock immunity. Components are **x, y, z (imaginary/vector part), w (real/scalar part)** with x² + y² + z² + w² = 1. Direct forward vector extraction avoids trigonometric functions (see Section 5.3).

#### 3.2.4 `/ELR` — Left Eye Rotation (2 Floats: x, y)

Horizontal (x) and vertical (y) gaze angles in degrees, relative to head orientation. Enables gaze-aware UI or fine aiming assistance. Optional for core functionality.

#### 3.2.5 `/ERR` — Right Eye Rotation (2 Floats: x, y)

Corresponding right eye data. Binocular comparison enables vergence depth estimation and wink detection (one eye closed, other open).

### 3.3 OSC Message Structure: Facial Blend Shapes (Gestures)

#### 3.3.1 `/W` Message Format: Blend Shape Index + Value (Int + Float 0.0-1.0)

The `/W` address carries **individual blend shape activations**: int32 index (0–51), float32 value (0.0 = rest, 1.0 = maximum activation). Face Cap transmits changed shapes; the mod must maintain state for all 52 indices, updating on receipt and applying decay or hold-last-value for unstated shapes .

#### 3.3.2 Complete Blend Shape Index Reference (52 ARKit Parameters)

Face Cap documents these indices explicitly :

| Index | Name | Region | Gesture Application |
|-------|------|--------|---------------------|
| 0 | `browInnerUp` | Brow | Surprise, attention |
| 1 | `browDown_L` | Brow | Concentration, concern |
| 2 | `browDown_R` | Brow | Concentration, concern |
| 3 | `browOuterUp_L` | Brow | Skepticism |
| 4 | `browOuterUp_R` | Brow | Skepticism |
| 5–6 | `eyeBlink_L`, `eyeBlink_R` | Eye | **Blink, wink detection** |
| 17 | `cheekPuff` | Cheek | Exertion |
| 19–20 | `eyeSquint_L`, `eyeSquint_R` | Eye | Smile component |
| 21–22 | `eyeWide_L`, `eyeWide_R` | Eye | Fear, surprise |
| 25 | **`jawOpen`** | **Jaw/Mouth** | **Primary "mouth open" gesture** |
| 26 | `jawForward` | Jaw | Aggression |
| 27–28 | `jawLeft`, `jawRight` | Jaw | Skepticism |
| 29 | `mouthOpen` | Mouth | Redundant with jawOpen |
| 31–32 | `cheekSquint_L`, `cheekSquint_R` | Cheek | Smile component |
| 33–34 | `noseSneer_L`, `noseSneer_R` | Nose | Disgust |
| **35–36** | **`mouthSmile_L`**, **`mouthSmile_R`** | **Mouth** | **Happiness, confirmation** |
| 37–38 | `mouthFrown_L`, `mouthFrown_R` | Mouth | Sadness, negation |
| 43 | `mouthPucker` | Mouth | Whistle, kiss |
| 50–51 | `mouthShrugLower`, `mouthShrugUpper` | Mouth | Uncertainty |

*Note: Indices 7–16, 18, 23–24, 30, 39–42, 44–49 are not explicitly documented in  but complete the 52-shape ARKit set per Apple's specification.*

##### 3.3.2.1 Brow Region: browInnerUp (0), browDown_L/R (1-2), browOuterUp_L/R (3-4)

The brow region provides **reliable, voluntarily controllable signals**. `browInnerUp` (0) is particularly distinct—users can reliably produce this "surprised" expression without mirror feedback. Consider mapping to attention-grabbing actions: screenshot capture, enemy marking, or emote activation.

##### 3.3.2.2 Eye Region: eyeBlink_L/R (5-6), eyeSquint_L/R (19-20), eyeWide_L/R (21-22), eyeLookUp/Down_L/R (23-26)

**Blink detection** (indices 5–6) is the most robust eye-based gesture: require both eyes > 0.8 for 100–150 ms, with 300+ ms between blinks to distinguish from natural blink rate (~15/minute). Double-blink (two within 600 ms) provides distinct mapping without complex pattern recognition.

##### 3.3.2.3 Cheek/Nose Region: cheekPuff (17), cheekSquint_L/R (31-32), noseSneer_L/R (33-34)

Cheek squint (31–32) **correlates with genuine smiles** (Duchenne marker), distinguishing authentic positive affect from social/polite smiling. Combine with `mouthSmile` for high-confidence happiness detection.

##### 3.3.2.4 Jaw/Mouth Region: jawOpen (25), jawForward (26), jawLeft/Right (27-28), mouthOpen (29), mouthSmile_L/R (35-36), mouthFrown_L/R (37-38), mouthPucker (43), mouthShrugLower/Upper (50-51)

**`jawOpen` (25)** is the **primary mouth gesture signal**—reliable, high amplitude, and naturally sustained for intentional activation. Threshold at 0.6 for 300+ ms distinguishes deliberate "mouth open" from speech or breathing. `mouthSmile_L/R` (35–36) bilateral activation > 0.5 indicates positive valence for confirmation or emote triggers.

### 3.4 Coordinate System Conversions

#### 3.4.1 ARKit Coordinate System: Right-Handed, Y-Up, Z-Backward

**Critical convention**: **+Z points backward, away from the camera** . This inverts intuitive "forward" direction and is the most common source of integration errors. Right-handed: X × Y = Z. Positive rotations follow right-hand rule.

#### 3.4.2 Unity Conversion Notes: -Z Forward Handling

Face Cap's documentation notes Unity's left-handed system with **-Z forward** requires compensation . This illustrates the general principle: graphics engines vary, and direct formula application without verification causes subtle bugs. Always test with known orientations.

#### 3.4.3 Minecraft Coordinate System Mapping: Y-Up, Z-South, X-East

Minecraft's conventions differ substantially:

| Property | ARKit | Minecraft |
|----------|-------|-----------|
| Y-axis | Up (matches) | Up |
| Z-axis | Backward (away from camera) | **South** (positive) |
| X-axis | Right | **East** (positive) |
| Yaw zero | Forward (toward camera, -Z) | **South** (+Z) |
| Yaw positive | Left (counter-clockwise) | **Left** (clockwise from above) |
| Pitch zero | Level | Level |
| Pitch positive | Up | **Down** (inverted) |

**Conversion formulas** (ARKit → Minecraft camera):

```
minecraftYaw = 180° - arkitYaw    // Invert and offset for south-zero
minecraftPitch = -arkitPitch      // Invert for down-positive
```

For quaternion (`/HRQ`), extract forward vector and compute angles, or construct rotation matrix with appropriate column/row permutations and sign flips.

---

## 4. Minecraft Mod Implementation: OSC Receiver & Integration

### 4.1 Development Environment Setup

#### 4.1.1 NeoForge 1.21.11 MDK Installation & Configuration

Download the **NeoForge MDK for Minecraft 1.21.11** from https://neoforged.net/. The MDK includes Gradle wrapper, example mod structure, and build configuration. Extract and import into IntelliJ IDEA (recommended) or Eclipse. Execute `gradlew genIntellijRuns` to generate launch configurations. Verify with initial build: `gradlew build` should complete without errors.

#### 4.1.2 Java 21 Compatibility Verification

Minecraft 1.21.11 requires **Java 21**. Verify with `java -version`. Configure IDE project SDK and Gradle JVM to Java 21. NeoForge's plugin enforces version compatibility; mismatches produce clear error messages.

#### 4.1.3 IDE Setup: IntelliJ IDEA or Eclipse with Gradle Integration

IntelliJ IDEA provides superior Gradle integration, decompiled source navigation, and debugging. Post-import essentials: (1) run `genIntellijRuns`, (2) verify "Minecraft Client" configuration has adequate heap (4–6 GB), (3) install Minecraft Development plugin for enhanced support.

### 4.2 Java OSC Library Integration

#### 4.2.1 JavaOSC Library Selection (javaosc-core or similar)

**JavaOSC** (`com.illposed.osc:javaosc-core:0.9`) provides complete OSC 1.0 support with minimal dependencies. Alternative: **oscP5** for Processing integration (heavier). For minimal footprint, custom UDP + OSC parser is feasible given Face Cap's fixed message set.

#### 4.2.2 Gradle Dependency Configuration

```gradle
dependencies {
    implementation 'com.illposed.osc:javaosc-core:0.9'
}
```

Refresh Gradle, verify classes available (`OSCPortIn`, `OSCMessage`, `OSCListener`).

#### 4.2.3 Alternative: Custom UDP Socket Implementation for Minimal Dependencies

For dependency minimization, implement direct `DatagramSocket` reception with custom OSC parsing. Face Cap's limited address set (`/HT`, `/HR`, `/HRQ`, `/ELR`, `/ERR`, `/W`) permits simplified parser: check address prefix, extract type tag, read big-endian floats. Approximately 200 lines versus 50 KB library dependency.

### 4.3 OSC Listener Implementation

#### 4.3.1 Creating a Dedicated Network Thread (Non-Blocking I/O)

**Critical architecture**: Network operations must not block Minecraft's main thread. Implementation pattern with Java 21 virtual threads:

```java
public class HeadTrackingReceiver implements AutoCloseable {
    private final DatagramChannel channel;
    private final AtomicReference<HeadPose> latestPose = new AtomicReference<>();
    private volatile boolean running = true;
    
    public HeadTrackingReceiver(int port) throws IOException {
        channel = DatagramChannel.open()
            .setOption(StandardSocketOptions.SO_REUSEADDR, true)
            .bind(new InetSocketAddress(port))
            .configureBlocking(false);
    }
    
    public void start() {
        Thread.startVirtualThread(() -> {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (running) {
                try {
                    buffer.clear();
                    SocketAddress source = channel.receive(buffer);
                    if (source != null) {
                        buffer.flip();
                        processPacket(buffer);
                    }
                    Thread.sleep(1); // 1 ms polling = ~1000 Hz check rate
                } catch (Exception e) {
                    // Log and continue
                }
            }
        });
    }
    // ... processPacket, getLatestPose, close
}
```

Virtual threads (Project Loom) provide lightweight concurrency ideal for this polling pattern. For pre-Java-21 compatibility, use `ExecutorService` with fixed thread pool.

#### 4.3.2 OSC Message Parser: Address Pattern Matching & Type Coercion

With JavaOSC, implement `OSCListener`:

```java
dispatcher.addListener(new OSCListener() {
    public void acceptMessage(Date time, OSCMessage message) {
        String addr = message.getAddress();
        List<Object> args = message.getArguments();
        
        if ("/HR".equals(addr) && args.size() == 3) {
            float pitch = (Float) args.get(0);
            float yaw = (Float) args.get(1);
            float roll = (Float) args.get(2);
            latestPose.set(new HeadPose(pitch, yaw, roll, System.nanoTime()));
        }
        // Similar for /HRQ, /W, etc.
    }
});
```

Verify type tags match expectations; Face Cap uses `fff` for `/HR`, `ffff` for `/HRQ`, `if` for `/W`.

#### 4.3.3 Thread-Safe Data Transfer to Main Game Thread

Use `AtomicReference` for single latest-sample semantics, or `ConcurrentLinkedQueue` for brief history. Main thread samples in `ClientTickEvent`:

```java
@SubscribeEvent
public void onClientTick(ClientTickEvent event) {
    if (event.phase == TickEvent.Phase.START) {
        HeadPose pose = receiver.getLatestPose();
        if (pose != null && pose.isRecent(100)) { // 100 ms freshness
            applyToCamera(pose);
        }
    }
}
```

#### 4.3.4 Error Handling: Network Timeouts, Malformed Packets, Reconnection Logic

Implement freshness checking: if `latestPose.timestamp < now - timeoutMs`, enter degraded mode (mouse control resume, visual indicator). For Face Cap's 5-second limit, detect timeout and prompt reconnection. Exponential backoff for reconnection attempts: 1s, 2s, 4s, 8s, max 30s.

### 4.4 Game Integration Points

#### 4.4.1 Client-Side Only Mod Architecture (No Server Required)

Register with `@Mod("headtracking")` and ensure all functionality is client-side. Use `Dist.CLIENT` checks or separate client event handlers. No network packets to server—camera manipulation is purely visual.

#### 4.4.2 Camera Rotation Override via `Camera#setRotation` or Equivalent

NeoForge 1.21.11 camera access:

```java
Minecraft mc = Minecraft.getInstance();
Camera camera = mc.gameRenderer.getMainCamera();
// Direct modification restricted; use ViewEvent or tick-based update
```

Preferred: `EntityViewRenderEvent` or modify `LocalPlayer` rotation which drives camera. Direct `Camera` field access via reflection is fragile across versions.

#### 4.4.3 Player Entity Rotation Synchronization

Set `LocalPlayer.setYRot(yaw)` and `setXRot(pitch)`—these are server-authoritative but client-predicted for local player. The server accepts client rotation updates without validation for the controlling player.

#### 4.4.4 Event Bus Registration for Tick-Based Updates

```java
@Mod("headtracking")
public class HeadTrackingMod {
    public HeadTrackingMod() {
        FMLJavaModLoadingContext.get().getModEventBus()
            .addListener(this::clientSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }
    
    private void clientSetup(FMLClientSetupEvent event) {
        // Initialize receiver
    }
    
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        // Process and apply tracking data
    }
}
```

---

## 5. View Direction Vector Calculation

### 5.1 Rotation Data Selection Strategy

#### 5.1.1 Euler Angles (`/HR`) vs. Quaternion (`/HRQ`) Trade-offs

| Factor | Euler `/HR` | Quaternion `/HRQ` |
|--------|-----------|-------------------|
| **Gimbal lock** | Occurs at ±90° pitch | **Immune** |
| **Intuition** | Direct, human-readable | Opaque without conversion |
| **Computation** | Simple trigonometry | Matrix construction or direct rotation |
| **Interpolation** | Problematic near singularities | **Slerp, always smooth** |
| **Bandwidth** | 12 bytes | 16 bytes |
| **Typical use** | Constrained range (±30° pitch) | **Full spherical, production** |

**Recommendation**: Use `/HRQ` for production implementations; `/HR` acceptable for prototyping with pitch constraints.

#### 5.1.2 Gimbal Lock Avoidance: Quaternion Preference for Full 360° Freedom

Gimbal lock in `/HR` occurs when `pitch = ±90°`: the yaw and roll axes align, causing loss of one degree of freedom and potential numerical instability. For seated Minecraft gameplay, this is rarely encountered. However, quaternion representation eliminates this concern entirely and provides cleaner mathematics for all operations.

#### 5.1.3 Computational Efficiency: Euler for Simple Pitch/Yaw, Quaternion for Complex

Modern hardware makes quaternion overhead negligible. The direct forward vector extraction from quaternion (Section 5.3.3) avoids trigonometric functions entirely, potentially outperforming Euler-to-vector conversion.

### 5.2 Euler Angle to Direction Vector Conversion

#### 5.2.1 Extracting Pitch (X), Yaw (Y), Roll (Z) from `/HR` Message

```java
float pitchDeg = (Float) args.get(0);  // X: nose up positive
float yawDeg = (Float) args.get(1);     // Y: nose left positive  
float rollDeg = (Float) args.get(2);    // Z: right ear down positive
```

#### 5.2.2 Standard Conversion Formula

Convert to radians, apply ARKit-to-Minecraft sign conventions:

```java
double pitchRad = Math.toRadians(-pitchDeg);  // Invert for Minecraft down-positive
double yawRad = Math.toRadians(180 - yawDeg); // Offset and invert for south-zero

double cp = Math.cos(pitchRad);
double sp = Math.sin(pitchRad);
double cy = Math.cos(yawRad);
double sy = Math.sin(yawRad);

// Minecraft forward direction (negative Z is forward/north)
double dirX = -sy * cp;   // East-west component
double dirY = -sp;        // Up-down component (inverted)
double dirZ = -cy * cp;   // North-south component (negative = forward)
```

#### 5.2.3 Minecraft-Specific Adjustments

The negative signs on `pitch`, `yaw` offset, and final `dirZ` encode all convention differences. Verify with known orientations: looking straight at screen (neutral pose) should yield approximately `dirX=0, dirY=0, dirZ=-1` (facing north/negative Z).

### 5.3 Quaternion to Direction Vector Conversion

#### 5.3.1 Extracting Components from `/HRQ` Message (x, y, z, w)

```java
float qx = (Float) args.get(0);
float qy = (Float) args.get(1);
float qz = (Float) args.get(2);
float qw = (Float) args.get(3);
```

Verify normalization: `qx*qx + qy*qy + qz*qz + qw*qw` should be ≈ 1.0.

#### 5.3.2 Rotation Matrix Construction or Direct Vector Rotation

Full rotation matrix (column-major, for reference):

```
| 1-2(y²+z²)   2(xy-zw)     2(xz+yw)   |
| 2(xy+zw)     1-2(x²+z²)   2(yz-xw)   |
| 2(xz-yw)     2(yz+xw)     1-2(x²+y²) |
```

#### 5.3.3 Forward Vector Extraction

Direct formula without full matrix construction—rotate unit Z vector (0,0,1) by quaternion:

```java
// Forward = q * (0,0,1) * q⁻¹, simplified:
double fx = 2 * (qx * qz + qw * qy);
double fy = 2 * (qy * qz - qw * qx);
double fz = 1 - 2 * (qx * qx + qy * qy);

// Convert to Minecraft coordinates (ARKIT: +Z backward, MINECRAFT: -Z forward)
double dirX = -fx;   // East-west
double dirY = -fy;   // Up-down (invert)
double dirZ = -fz;   // North-south (invert for forward)
```

This extracts the third column of the rotation matrix with appropriate sign flips for Minecraft conventions.

### 5.4 Smoothing & Dead Zone Processing

#### 5.4.1 Exponential Moving Average for Jitter Reduction

```java
// Per-frame update
smoothedPitch = alpha * rawPitch + (1 - alpha) * smoothedPitch;
```

**α = 0.3–0.5** provides responsive yet stable tracking. Higher α = more responsive, more jitter; lower α = smoother, more lag. Adaptive α based on velocity (higher during fast movement, lower during stillness) can optimize both.

#### 5.4.2 Dead Zone Implementation for Micro-Movement Filtering

```java
double applyDeadZone(double value, double threshold) {
    if (Math.abs(value) < threshold) return 0;
    return Math.signum(value) * (Math.abs(value) - threshold) / (1 - threshold);
}
```

Typical threshold: **2–5°** for pitch/yaw. Eliminates drift and physiological tremor without affecting intentional movement.

#### 5.4.3 Sensitivity Scaling: User-Configurable Multipliers

```java
double finalYaw = (rawYaw - neutralYaw) * yawSensitivity;
double finalPitch = (rawPitch - neutralPitch) * pitchSensitivity;
```

**Sensitivity 1.0** = 1:1 mapping (30° head turn = 30° camera rotation). **Sensitivity 2.0** = amplified (15° head turn = 30° camera), useful for limited mobility. **Sensitivity 0.5** = reduced (60° head turn = 30° camera), for precision aiming. Per-axis configuration accommodates individual preference and physical constraints.

---

## 6. Head Tracking Calibration System

### 6.1 Calibration Philosophy & Goals

#### 6.1.1 Neutral Pose Definition: "Looking Straight at Screen"

The **neutral pose** is the user's habitual, comfortable viewing position: head upright, eyes directed at screen center, facial expression relaxed. This pose maps to **zero camera offset**—the in-game view direction remains unchanged from default when the user is in neutral pose. Critically, this is **not** a forced, artificial position; it must be sustainable for extended gameplay without strain.

#### 6.1.2 Coordinate System Alignment: Real World to Virtual World

Calibration bridges the gap between physical tracking data and virtual camera control. ARKit's arbitrary session origin, individual face geometry variations, and desired sensitivity all require compensation. The calibration system establishes: (1) neutral orientation offsets, (2) real-world-to-virtual-world scaling factors, and (3) comfortable range of motion limits.

#### 6.1.3 Individual Variation Compensation: Face Geometry Differences

ARKit's blend shape normalization attempts consistency across individuals, but absolute head pose reference frames vary based on: eye depth relative to nose bridge, habitual head posture (forward head posture common in desk workers), and phone mounting position relative to screen. Calibration captures these as correctable offsets rather than inherent limitations.

### 6.2 Calibration Procedure Implementation

#### 6.2.1 Step 1: Capture Neutral Pose (Zero Reference)

##### 6.2.1.1 User Instruction: "Look at center of screen, keep head still"

Clear, unambiguous instructions are essential. Display a visual target (crosshair, dot) at screen center. Instruct: "Position yourself comfortably as you would for gameplay. Look at the target. Keep your head still for 2 seconds."

##### 6.2.1.2 Sampling Duration: 1–2 Seconds of Averaged Values

Sample at 60 Hz for 120 frames (2 seconds). Compute mean and standard deviation for each angle. **Validation**: standard deviation < 3° indicates adequate stillness; if exceeded, prompt user to retry. Store averaged values as `neutralPitch`, `neutralYaw`, `neutralRoll`.

##### 6.2.1.3 Stored Offsets: `neutralPitch`, `neutralYaw`, `neutralRoll`

These values are subtracted from all subsequent raw readings before sensitivity scaling and application. Persistence through NeoForge's config system ensures calibration survives game restarts.

#### 6.2.2 Step 2: Range of Motion Mapping

##### 6.2.2.1 Maximum Look-Up/Down Angles (Typically ±30° Pitch)

Instruct: "Slowly look up as far as comfortable, hold, then down as far as comfortable, hold." Capture maximum observed angles. Typical comfortable range: **±20° to ±35°**. Values beyond ±45° suggest extreme positioning or measurement error.

##### 6.2.2.2 Maximum Look-Left/Right Angles (Typically ±60° Yaw)

Similar procedure for horizontal range. Typical: **±45° to ±70°**. Beyond ±90° requires significant neck rotation and is rarely sustainable.

##### 6.2.2.3 Scaling Factors: Real Degrees to Minecraft Camera Degrees

Compute sensitivity to map comfortable physical range to desired virtual range. Example: if user comfortably achieves ±30° pitch and desires ±45° camera pitch, `pitchSensitivity = 1.5`. Provide visual preview during adjustment.

#### 6.2.3 Step 3: Validation & Fine-Tuning

##### 6.2.3.1 Visual Feedback: Crosshair Alignment Test

Display crosshair at screen center. User in neutral pose should see crosshair centered. Look extremes should reach screen edges without overshoot or undershoot. Iterate adjustment if misaligned.

##### 6.2.3.2 Iterative Adjustment Interface (In-Game Config Screen)

NeoForge's config GUI or custom screen with: live tracking visualization, current values, +/- adjustment buttons, reset to captured neutral, and save/load profile functionality.

### 6.3 Runtime Calibration Persistence

#### 6.3.1 Configuration File Storage (NeoForge Config System)

```java
public static class ClientConfig {
    public static final ModConfigSpec.DoubleValue NEUTRAL_PITCH;
    public static final ModConfigSpec.DoubleValue NEUTRAL_YAW;
    public static final ModConfigSpec.DoubleValue PITCH_SENSITIVITY;
    // ... etc.
    
    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        NEUTRAL_PITCH = builder.defineInRange("neutralPitch", 0.0, -180.0, 180.0);
        // ...
        SPEC = builder.build();
    }
}
```

Register with `ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);`

#### 6.3.2 Per-User Profile Support

Multiple named profiles for different scenarios: "Seated Desktop", "Standing VR", "Reclined Couch". Profile selection in-game without restart.

#### 6.3.3 Quick Recalibration Trigger (Keybinding)

Bind to accessible key (e.g., `KP_MINUS` or controller button) for rapid neutral pose recapture without full menu navigation. Useful after posture adjustment or phone repositioning.

---

## 7. Facial Gesture Detection & Game Action Mapping

### 7.1 Gesture Recognition Architecture

#### 7.1.1 Threshold-Based Detection vs. Machine Learning Classification

**Threshold-based detection** is appropriate for this application: deterministic, interpretable, configurable, and computationally trivial. Machine learning approaches (neural network classifiers) offer potential robustness to individual variation but require training data, increase mod complexity, and reduce transparency. The 52-dimensional blend shape space with clear semantic mapping to facial actions enables effective rule-based detection.

#### 7.1.2 Hysteresis Implementation: Preventing Rapid State Toggling

Raw threshold crossing causes flickering when values hover near threshold. **Hysteresis** uses separate activation and deactivation thresholds:

```java
if (!gestureActive && value > ACTIVATION_THRESHOLD) gestureActive = true;
if (gestureActive && value < DEACTIVATION_THRESHOLD) gestureActive = false;
// Typically: DEACTIVATION = ACTIVATION * 0.6–0.8
```

This creates clean state transitions with ~10–20% noise immunity margin.

#### 7.1.3 Gesture Cooldowns: Avoiding Accidental Repeated Triggers

After gesture deactivation, enforce **cooldown period** (typically 500–1000 ms) before reactivation possible. Prevents: single intentional gesture registering multiple times, and rapid oscillation from physiological tremor.

### 7.2 Core Gesture Implementations

#### 7.2.1 Nod Detection (Vertical Head Movement)

##### 7.2.1.1 Signal Source: `/HR` Pitch Value Change Over Time

Compute pitch velocity: `dpitch/dt = (current.pitch - previous.pitch) / deltaTime`.

##### 7.2.1.2 Velocity Threshold: >15°/second for 200ms

Nod is rapid, deliberate pitch change. Typical natural nod: 2–4 Hz oscillation, 15–30° amplitude, 100–200 ms per half-cycle. Threshold: **|velocity| > 15°/s sustained for 200 ms**.

##### 7.2.1.3 Direction Discrimination: Nod-Down vs. Nod-Up

`velocity < -THRESHOLD` = nod down (affirmative). `velocity > +THRESHOLD` = nod up (negative/uncertain). Require complete cycle (down then up, or up then down) within 800 ms for valid nod gesture.

##### 7.2.1.4 Default Mapping: Nod-Down = Crouch, Nod-Up = Jump/Stand

| Gesture | Default Action | Rationale |
|---------|---------------|-----------|
| Nod down | Toggle crouch/sneak | Natural "yes", reduces profile |
| Nod up | Jump if standing, stand if crouching | Natural "no", upward impulse |
| Double nod | Sprint toggle | Distinct from single, intentional activation |

#### 7.2.2 Head Shake Detection (Horizontal Head Movement)

##### 7.2.2.1 Signal Source: `/HR` Yaw Value Oscillation

Track yaw velocity and zero-crossings.

##### 7.2.2.2 Pattern Recognition: Two Direction Reversals Within 500ms

Valid shake: left→right→left or right→left→right, with |velocity| > 20°/s at peaks, completed within 500 ms. Natural shake frequency ~4–6 Hz.

##### 7.2.2.3 Default Mapping: "No" Gesture = Cancel Action/Open Inventory

| Gesture | Default Action |
|---------|---------------|
| Shake | Cancel current action, close GUI, or "no" emote |
| Rapid shake | Emergency disengage (drop item, stop mining) |

#### 7.2.3 Head Tilt Detection (Roll-Based)

##### 7.2.3.1 Signal Source: `/HR` Roll Value

Sustained roll deviation from neutral.

##### 7.2.3.2 Threshold: >20° From Neutral

Comfortable sustained tilt limit. Beyond 30° induces neck strain.

##### 7.2.3.3 Default Mapping: Lean Left/Right = Strafe Modifier

| Gesture | Default Action |
|---------|---------------|
| Lean left | Strafe left without keypress (or modifier: walk+strafe) |
| Lean right | Strafe right |
| Either lean + look | Cover system peek (future enhancement) |

### 7.3 Blend Shape-Based Gestures

#### 7.3.1 Mouth Open (`jawOpen` / `mouthOpen` Blend Shapes)

##### 7.3.1.1 Threshold: >0.6 Intensity for >300ms

`jawOpen` (index 25) is primary signal; `mouthOpen` (29) redundant. Require sustained activation to distinguish from speech breathing.

##### 7.3.1.2 Default Mapping: Sprint Activation

| Gesture | Default Action | Alternative |
|---------|---------------|-------------|
| Mouth open | Sprint (while held) | Toggle sprint |
| Mouth open + look down | Slide (if modded) | — |

#### 7.3.2 Mouth Smile (`mouthSmile_L` / `mouthSmile_R`)

##### 7.3.2.1 Threshold: >0.5 Bilateral Intensity

Require both left and right > 0.5 to avoid asymmetric expression false positives. Cheek squint (31–32) correlation confirms genuine smile.

##### 7.3.2.2 Default Mapping: Emote/Chat Trigger

| Gesture | Default Action |
|---------|---------------|
| Smile | Wave emote, or open quick-chat wheel |
| Sustained smile (>2s) | Auto-accept trade/request |

#### 7.3.3 Eye Blink Patterns

##### 7.3.3.1 Single Blink: `eyeBlink_L` + `eyeBlink_R` >0.8

Both eyes simultaneously, 100–200 ms duration. Natural blink ~150 ms.

##### 7.3.3.2 Double Blink: Two Blinks Within 600ms

Inter-blink interval 200–400 ms. Distinct from natural ~3–4 second blink rate.

##### 7.3.3.3 Default Mapping: Single = Attack/Use, Double = Swap Item

| Gesture | Default Action |
|---------|---------------|
| Single blink | Primary action (attack, use, place) |
| Double blink | Swap to next hotbar slot |
| Wink (L or R only) | Peek camera (if implemented) |

#### 7.3.4 Eyebrow Raise (`browInnerUp`)

##### 7.3.4.1 Threshold: >0.7 Intensity

High, distinct activation. Brief duration acceptable (~100 ms).

##### 7.3.4.2 Default Mapping: Zoom/Spyglass Activation

| Gesture | Default Action |
|---------|---------------|
| Eyebrow raise | Hold to zoom/scope (while raised) |
| Eyebrow raise + nod | Snapshot/screenshot |

### 7.4 Custom Gesture Configuration

#### 7.4.1 In-Game Gesture Binding Interface

NeoForge config screen or custom GUI showing: live blend shape values with bars, gesture list with current bindings, click-to-rebind with action selection, threshold adjustment sliders with live preview.

#### 7.4.2 Threshold Adjustment Per Gesture

Expose activation threshold, deactivation threshold (hysteresis ratio), minimum duration, and cooldown as user-adjustable per gesture. Save per-profile.

#### 7.4.3 Gesture Combination Support (Simultaneous Detection)

Detect co-occurring gestures for expanded vocabulary: "smile while nodding" = screenshot, "mouth open + lean" = slide, "eyebrow raise + blink" = special ability. Combination detection requires state machine tracking active gestures with temporal window (gestures within 200 ms of each other considered simultaneous).

---

## 8. Advanced Topics & Optimization

### 8.1 Network Performance Tuning

#### 8.1.1 UDP Buffer Sizing for High-Frequency Updates (60Hz Target)

Operating system UDP receive buffers may default to sizes inadequate for 60 Hz bursty reception. Increase: Linux `net.core.rmem_max = 262144`; Windows via `setsockopt` with `SO_RCVBUF`. Java `DatagramSocket` buffer can be set via `setReceiveBufferSize(65536)`.

#### 8.1.2 Packet Loss Mitigation: Interpolation vs. Extrapolation

| Strategy | Implementation | Use Case |
|----------|---------------|----------|
| **Hold last** | Repeat last valid sample | Brief gaps (< 100 ms) |
| **Interpolation** | Lerp between last two samples | Known smooth motion |
| **Extrapolation** | Velocity-based prediction | High confidence, short horizon |

For head tracking, **hold last with timeout** is robust; extrapolation risks divergence if movement changes abruptly.

#### 8.1.3 Bandwidth Optimization: Delta Compression (Future Enhancement)

Face Cap transmits full state; custom protocol could send only changed values with sequence numbers. Not necessary for local network but relevant for remote/cloud scenarios.

### 8.2 Latency Reduction Strategies

#### 8.2.1 USB Tethering vs. Wi-Fi Latency Comparison

| Metric | Wi-Fi | USB Tethering |
|--------|-------|---------------|
| Typical RTT | 5–20 ms | 1–3 ms |
| Jitter | High (±10 ms) | Negligible |
| Packet loss | 0.1–2% | < 0.01% |
| Setup | Trivial | Moderate |

**Recommendation**: USB tethering for competitive PvP, Wi-Fi acceptable for creative/survival.

#### 8.2.2 Mod Update Frequency: Decoupling Render Tick from Network Receive

Network thread receives at 60 Hz; game renders at 60–240 Hz. Camera update should occur every render frame with interpolated or latest-sample value, not locked to network receive. Prevents visual stutter from network jitter.

#### 8.2.3 Prediction Algorithms for Head Movement

Simple velocity extrapolation: `predicted = current + velocity * predictionTime`. Effective for 10–20 ms horizon; beyond this, acceleration uncertainty dominates. Kalman filtering with head dynamics model possible but likely overkill.

### 8.3 Fallback & Error Handling

#### 8.3.1 Connection Loss Detection: Timeout-Based Degradation

If no packet received for > 100 ms (6 frames at 60 Hz), mark tracking as stale. At > 500 ms, transition to mouse/keyboard control with visual notification.

#### 8.3.2 Graceful Degradation: Mouse/Keyboard Resume on Disconnect

Seamless handoff: when tracking unavailable, last camera orientation preserved, mouse regains control without jarring reset. User awareness through subtle HUD indicator (color-coded dot, optional).

#### 8.3.3 Reconnection Auto-Retry with Exponential Backoff

On detected disconnect, attempt reconnection at intervals: 1s, 2s, 4s, 8s, then 30s sustained. Reset on user manual reconnect or successful restoration.

### 8.4 Security Considerations

#### 8.4.1 Local Network Scope: No Internet Exposure Required

Face Cap → Minecraft communication is purely local network. No cloud services, no data leaves local subnet. Firewall external interface blocking ensures isolation.

#### 8.4.2 Optional: OSC Message Authentication (HMAC-SHA256)

For untrusted network environments (LAN parties, public Wi-Fi), add HMAC-SHA256 signature to OSC bundles with pre-shared key. Mod verifies before processing. Prevents spoofed head tracking injection.

---

## 9. Testing & Debugging Workflow

### 9.1 Verification Tools

#### 9.1.1 OSC Monitoring: Protokol, TouchOSC Bridge, or Custom Logger

| Tool | Platform | Features |
|------|----------|----------|
| **Protokol** | macOS, Windows | Purpose-built OSC monitor, message logging, visualization |
| **TouchOSC Bridge** | Cross-platform | Routing, monitoring, simple forwarding |
| **Python + python-osc** | All | Custom scripts, automated testing |

Essential for verifying Face Cap output before mod integration.

#### 9.1.2 In-Mod Debug Overlay: Real-Time Data Visualization

Render to screen: current pitch/yaw/roll, active blend shapes with bars, gesture state machine status, network latency/packet loss indicator. Toggle with debug key.

#### 9.1.3 Network Packet Capture: Wireshark for UDP Analysis

Filter: `udp.port == 9000`. Verify packet frequency, size distribution, source IP. Detect fragmentation, loss patterns, unusual delays.

### 9.2 Common Issues & Resolutions

#### 9.2.1 "No Face Detected": Lighting, Distance, Occlusion Checks

| Symptom | Cause | Resolution |
|---------|-------|------------|
| Mesh absent | Insufficient light | Add diffuse ambient lighting |
| Mesh flickers | Distance variation | Fix phone position, verify 30–50 cm |
| Mesh partial | Face occlusion | Clear hair, hands, glasses tint |

#### 9.2.2 "Choppy Movement": Network Jitter vs. Processing Lag Diagnosis

| Indicator | Cause | Resolution |
|-----------|-------|------------|
| Regular stutter at 5s | Free version limit | Purchase unlock |
| Irregular, with packet loss | Wi-Fi congestion | Switch to USB tethering |
| Smooth in debug, choppy in game | Main thread blocking | Optimize mod processing, reduce GC |

#### 9.2.3 "Inverted Controls": Coordinate System Sign Error Identification

Systematic test: look left → camera should turn left. If opposite, invert yaw sign. Look up → camera should look up. If opposite, pitch sign error. Use debug overlay to compare raw values vs. applied rotation.

#### 9.2.4 "Gestures Not Firing": Threshold Tuning & Sensitivity Adjustment

Check live blend shape values in debug overlay. If `jawOpen` peaks at 0.4, threshold of 0.6 will never fire. Adjust to 0.3 or increase expression intensity. Verify hysteresis not preventing deactivation→reactivation cycle.

---

## 10. Appendix: Quick Reference

### 10.1 Face Cap OSC Address Cheat Sheet

| Address | Args | Description | Minecraft Use |
|---------|------|-------------|---------------|
| `/HT` | 3f | Position (x,y,z) m | Optional advanced |
| `/HR` | 3f | Euler (pitch,yaw,roll) ° | Primary rotation (with care) |
| `/HRQ` | 4f | Quaternion (x,y,z,w) | **Preferred rotation** |
| `/ELR` | 2f | Left eye (horiz,vert) ° | Optional gaze |
| `/ERR` | 2f | Right eye (horiz,vert) ° | Optional gaze |
| `/W` | i,f | Blend shape index, value | **All gestures** |

### 10.2 ARKit Blend Shape Index Complete Table

| Index | Name | Gesture Priority |
|-------|------|----------------|
| 0 | `browInnerUp` | Medium |
| 1–2 | `browDown_L/R` | Low |
| 3–4 | `browOuterUp_L/R` | Low |
| **5–6** | **`eyeBlink_L/R`** | **High** |
| 17 | `cheekPuff` | Low |
| 19–20 | `eyeSquint_L/R` | Medium |
| 21–22 | `eyeWide_L/R` | Medium |
| **25** | **`jawOpen`** | **Highest** |
| 26 | `jawForward` | Low |
| 27–28 | `jawLeft/Right` | Low |
| 29 | `mouthOpen` | Redundant |
| 31–32 | `cheekSquint_L/R` | Medium |
| 33–34 | `noseSneer_L/R` | Low |
| **35–36** | **`mouthSmile_L/R`** | **High** |
| 37–38 | `mouthFrown_L/R` | Medium |
| 43 | `mouthPucker` | Medium |
| 50–51 | `mouthShrugLower/Upper` | Low |

### 10.3 Sample Code Snippets (Java/NeoForge)

#### 10.3.1 Minimal OSC Receiver Thread

```java
public class OSCReceiver implements Runnable {
    private final OSCPortIn receiver;
    private final AtomicReference<float[]> latestRotation = new AtomicReference<>();
    
    public OSCReceiver(int port) throws Exception {
        receiver = new OSCPortIn(port);
        receiver.addListener("/HR", (time, msg) -> {
            List<Object> args = msg.getArguments();
            latestRotation.set(new float[]{
                (Float)args.get(0), (Float)args.get(1), (Float)args.get(2)
            });
        });
    }
    
    public void run() { receiver.startListening(); }
    public float[] getLatest() { return latestRotation.get(); }
    public void stop() { receiver.stopListening(); }
}
```

#### 10.3.2 Quaternion-to-Direction Conversion

```java
public Vec3 quaternionToDirection(float x, float y, float z, float w) {
    // Extract forward vector (rotated Z axis)
    double fx = 2 * (x * z + w * y);
    double fy = 2 * (y * z - w * x);
    double fz = 1 - 2 * (x * x + y * y);
    
    // Convert ARKit (+Z backward) to Minecraft (-Z forward)
    return new Vec3(-fx, -fy, -fz).normalize();
}
```

#### 10.3.3 Gesture Threshold Detector

```java
public class GestureDetector {
    private float lastValue = 0;
    private boolean active = false;
    private long lastActiveTime = 0;
    
    private final float activateThreshold;
    private final float deactivateThreshold;
    private final long cooldownMs;
    
    public boolean update(float value) {
        long now = System.currentTimeMillis();
        
        if (!active && value > activateThreshold) {
            if (now - lastActiveTime > cooldownMs) {
                active = true;
                return true; // Gesture triggered
            }
        } else if (active && value < deactivateThreshold) {
            active = false;
            lastActiveTime = now;
        }
        return false;
    }
}
```

### 10.4 External Resources & Further Reading

- **Face Cap**: https://www.bannaflak.com/face-cap/ — Official documentation, video tutorials 
- **ARKit Face Tracking**: https://developer.apple.com/documentation/arkit/arfacetrackingconfiguration — Apple's developer reference
- **OSC Specification**: http://opensoundcontrol.org/spec-1_0 — Protocol details
- **NeoForge Documentation**: https://docs.neoforged.net/ — Mod development guides
- **JavaOSC Library**: https://github.com/hoijui/JavaOSC — Source and examples
