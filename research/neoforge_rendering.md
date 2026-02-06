

# Creating a Stereo 3D Mod for X-Real 2 Glasses in Minecraft 1.21 with NeoForge

## 1. Core Rendering Architecture for Side-by-Side Stereo 3D

### 1.1 OpenGL Foundation for Split-Screen Rendering

The fundamental challenge in creating a stereo 3D mod for X-Real 2 glasses lies in understanding how to manipulate OpenGL's rendering pipeline to produce two distinct viewpoints within a single framebuffer. The X-Real 2 glasses, like many AR devices, expect a **side-by-side stereo format** where the left half of the screen contains the left eye's view and the right half contains the right eye's view. This approach does not require targeting specific resolutions or aspect ratios, making it inherently adaptable to various display configurations .

The core technique for achieving this split-screen effect centers on OpenGL's **viewport manipulation** capabilities. In standard OpenGL rendering, the viewport defines the rectangular region of the window where rendering output is directed. By strategically modifying this viewport between rendering passes, developers can control exactly where each eye's perspective appears on the final display. This technique has been well-documented in OpenGL literature and forms the theoretical basis for Minecraft modding approaches .

The critical insight from established OpenGL practice is that **viewport manipulation must be paired with proper state management** to prevent rendering artifacts. When rendering multiple views into a single window, each view requires not only its own viewport definition but also careful handling of the scissor test, depth buffer, and other OpenGL state that might persist between rendering passes. The Khronos Forums documentation specifically highlights that `glViewport` alone is insufficient for clean split-screen rendering; the scissor test must be employed to prevent overdraw and ensure crisp boundaries between view regions .

For the X-Real 2 glasses application, this translates to a rendering sequence where: first, the scissor test is enabled and set to the left eye region; second, the viewport is configured for the left eye and the scene is rendered from the left eye's perspective; third, the scissor and viewport are adjusted for the right eye and the scene is rendered again; and finally, the scissor test is disabled to restore normal rendering state. This pattern ensures that each eye's rendering is strictly confined to its designated screen region without interference .

#### 1.1.1 Viewport Manipulation with `glViewport`

The `glViewport` function serves as the **primary mechanism for directing OpenGL rendering output to specific screen regions**. In the context of Minecraft modding through NeoForge, this function is accessible through LWJGL's GL11 bindings, though modern NeoForge versions may provide wrapper methods through `GlStateManager` or similar utility classes. The function signature `glViewport(int x, int y, int width, int height)` defines the lower-left corner position and dimensions of the rendering target in window coordinates .

For side-by-side stereo rendering, the viewport configuration follows a precise pattern. Assuming a window of arbitrary width **W** and height **H**, the left eye viewport would be configured as `glViewport(0, 0, W/2, H)`, establishing a rendering region covering the left half of the screen from the bottom-left origin. The right eye viewport would subsequently be configured as `glViewport(W/2, 0, W/2, H)`, covering the right half. This division maintains the full vertical resolution for both eyes while horizontally splitting the available pixels.

The coordinate system requires careful attention: **OpenGL's window coordinates have their origin (0,0) at the lower-left corner of the window**, with X increasing to the right and Y increasing upward. This contrasts with many GUI systems where Y increases downward. For Minecraft applications, this distinction is particularly important when integrating with the game's existing GUI rendering, which may use different coordinate conventions. The viewport dimensions must be calculated in pixels, and for optimal results, the width should be an even number to ensure both halves have identical pixel counts .

Dynamic viewport adjustment is essential for handling window resizing. The mod must respond to framebuffer dimension changes by recalculating the viewport parameters each frame or registering for window resize events. NeoForge's event system provides mechanisms for this through `WindowResizeEvent` or similar callbacks, ensuring that the stereo rendering remains correctly proportioned regardless of window dimensions. This adaptability is crucial for X-Real 2 compatibility, as the glasses may be used with various host devices and display configurations .

#### 1.1.2 Scissor Testing with `glScissor` for Region Control

While viewport manipulation defines where rendering output appears, **scissor testing provides the critical guarantee of render region isolation** that makes clean side-by-side stereo possible. The scissor test, enabled with `glEnable(GL_SCISSOR_TEST)` and configured via `glScissor(x, y, width, height)`, provides a per-fragment test that can discard fragments outside a specified rectangular region .

For stereo 3D applications, scissor testing serves multiple important functions. First, it provides a **hard boundary that prevents any rendering from spilling outside the intended eye region**, even in cases where viewport calculations might have rounding errors or where post-processing effects might otherwise extend beyond boundaries. Second, it enables more complex stereo configurations where the eye regions might not be simple rectangular splits—for example, if future AR glasses require different aspect ratios or positioning.

The scissor rectangle should **exactly match the viewport rectangle for each eye**: `glScissor(0, 0, screenWidth / 2, screenHeight)` for the left eye and `glScissor(screenWidth / 2, 0, screenWidth / 2, screenHeight)` for the right eye. This redundancy between viewport and scissor settings might seem unnecessary, but it serves critical purposes: it provides defense-in-depth against viewport state errors, it ensures that any rendering operations that might affect pixels outside the intended region are clipped, and it maintains compatibility with Minecraft's internal rendering code that may make assumptions about scissor state .

The scissor test operates in window coordinates, which typically have their origin at the bottom-left corner in OpenGL convention. However, Minecraft's rendering abstraction may use different coordinate conventions in various contexts, requiring careful verification of coordinate system alignment when implementing low-level OpenGL operations. The interaction between viewport transformation (which maps normalized device coordinates to window coordinates) and scissor testing (which operates in window coordinates) must be thoroughly understood to avoid subtle positioning errors that could cause eye misalignment or vertical parallax issues .

#### 1.1.3 Disabling Scissor Test After Rendering Each Eye

**Proper state cleanup is essential in OpenGL programming** to prevent state leakage that can cause difficult-to-debug rendering artifacts in subsequent rendering operations. After completing the stereo rendering pass, the scissor test must be explicitly disabled to restore normal rendering behavior for subsequent operations. This cleanup step is frequently overlooked in rendering code but is essential for maintaining compatibility with Minecraft's existing rendering systems and other mods .

The recommended pattern implements try-finally blocks or explicit state push/pop mechanisms to ensure scissor state restoration even when exceptions occur during rendering. This defensive programming approach prevents cascading failures that would otherwise require game restart to resolve. The sequence for a complete stereo rendering frame would be: save original state (if needed), enable scissor test and set scissor rectangle for left eye, set viewport for left eye, render left eye scene, disable scissor test, then repeat for right eye with appropriate rectangle and viewport, and finally disable scissor test again before any post-stereo rendering operations .

Failure to properly disable the scissor test can result in **clipped GUI elements, missing debug information, or other visual artifacts** that significantly degrade the user experience. In extreme cases, persistent scissor test state can cause complete failure of GUI rendering, making the game unplayable. These failure modes emphasize the importance of robust state management in rendering modifications .

### 1.2 Framebuffer Object (FBO) Strategy

While direct viewport manipulation can achieve basic split-screen rendering, a **more robust and flexible approach employs Framebuffer Objects (FBOs)** to render each eye's view to off-screen textures before compositing them to the final display. This technique, extensively documented in OpenGL tutorials and Minecraft modding forums, provides several advantages for stereo 3D implementations: it decouples scene rendering from final display configuration, enables post-processing effects per eye if needed, and simplifies the integration with Minecraft's existing rendering pipeline .

The FBO-based approach aligns with modern OpenGL best practices and provides better compatibility with Minecraft's internal rendering architecture, which makes extensive use of FBOs for various rendering effects. The research into Minecraft's rendering system reveals that understanding FBO management is essential for any sophisticated rendering modification .

#### 1.2.1 Creating Dedicated FBOs for Left and Right Eye Capture

FBO creation in OpenGL involves several steps: generating the framebuffer object, creating and attaching texture images for color and depth data, and verifying framebuffer completeness. For Minecraft modding, these operations are typically performed through LWJGL bindings or higher-level abstractions provided by NeoForge's rendering utilities .

Each eye requires its own FBO with matching specifications. The color attachment should use a format appropriate for final display—typically **GL_RGBA8** for standard dynamic range or **GL_RGBA16F** for HDR content. The depth attachment, essential for proper 3D rendering, commonly uses **GL_DEPTH_COMPONENT24** or **GL_DEPTH_COMPONENT32F**. The dimensions of these attachments determine the rendering resolution for each eye; for quality comparable to single-view rendering, each eye's FBO should have half the screen width and full height .

The memory overhead of two additional FBOs at half-resolution each is equivalent to one full-screen FBO, which is acceptable for modern GPUs. For a 1920x1080 display with split-screen rendering, each eye FBO would be 960x1080, requiring approximately **4.1 MB for color** (960 * 1080 * 4 bytes) and **4.1 MB for depth-stencil** (960 * 1080 * 4 bytes for 32-bit depth), totaling approximately **16.4 MB for both eyes**—well within the capabilities of any GPU capable of running Minecraft 1.21 .

Resource management is critical for FBO-based rendering. Each FBO consumes GPU memory for its attachments, and these resources should be released when the mod is disabled or the game exits. The `Framebuffer` class in Minecraft provides `destroyBuffers()` or similar methods for proper cleanup, and the mod should register appropriate lifecycle handlers to ensure resources are not leaked .

#### 1.2.2 Attaching Color and Depth Textures to FBOs

Texture attachment to FBOs requires careful attention to format compatibility and sampling requirements. The color attachment texture must be created with dimensions matching the desired eye resolution, appropriate internal format, and filtering parameters suitable for subsequent sampling during compositing .

For side-by-side stereo without resolution targeting, the eye resolution is derived from the current framebuffer dimensions. If the main window has width **W** and height **H**, each eye renders at **W/2 × H**. This dynamic calculation must be performed each frame or in response to resize events, with FBO resources recreated as necessary. The texture parameters should include **GL_TEXTURE_MIN_FILTER** and **GL_TEXTURE_MAG_FILTER** set to **GL_LINEAR** for smooth scaling, though **GL_NEAREST** may be preferred for pixel-exact reproduction .

Depth attachment configuration affects the precision of depth testing and the quality of depth-based effects like shadows. The choice between **GL_DEPTH_COMPONENT16**, **GL_DEPTH_COMPONENT24**, and **GL_DEPTH_COMPONENT32F** involves trade-offs between precision and memory usage. For most Minecraft scenes, **24-bit depth provides sufficient precision** without the memory overhead of 32-bit float depth. However, scenes with very large depth ranges might benefit from 32-bit float precision .

The attachment process uses `glFramebufferTexture2D` to bind each texture to its respective attachment point: **GL_COLOR_ATTACHMENT0** for color and **GL_DEPTH_ATTACHMENT** for depth. After both attachments are configured, `glCheckFramebufferStatus` must verify that the FBO is complete and ready for rendering. Incomplete FBOs—resulting from format mismatches, dimension inconsistencies, or unsupported configurations—will fail this check and must be debugged before use .

#### 1.2.3 Binding and Unbinding Framebuffers for Render-to-Texture Operations

Framebuffer binding in OpenGL is a stateful operation that redirects all subsequent rendering commands to the specified FBO rather than the default window framebuffer. The binding call `glBindFramebuffer(GL_FRAMEBUFFER, fboId)` establishes this redirection, with `glBindFramebuffer(GL_FRAMEBUFFER, 0)` restoring default framebuffer rendering .

For stereo rendering, the binding sequence interleaves with camera setup and scene rendering:

| Step | Operation | Purpose |
|------|-----------|---------|
| 1 | Bind left eye FBO | Redirect rendering to left eye buffer |
| 2 | Configure viewport to FBO dimensions | Set proper coordinate transformation |
| 3 | Set left eye camera matrices | Apply eye offset transformation |
| 4 | Render scene | Capture left eye view |
| 5 | Bind right eye FBO | Redirect rendering to right eye buffer |
| 6 | Configure viewport | Set proper coordinate transformation |
| 7 | Set right eye camera matrices | Apply eye offset transformation |
| 8 | Render scene | Capture right eye view |
| 9 | Bind default framebuffer (0) | Restore screen rendering |
| 10 | Composite both eye textures to screen | Create final side-by-side output |

*Table 1: FBO Binding Sequence for Stereo Rendering*

This sequence ensures that each eye's scene rendering is captured to its dedicated FBO without interference. The viewport configuration for FBO rendering should typically match the FBO dimensions exactly—using the full FBO resolution rather than the final screen subdivision—to maximize texture utilization and simplify coordinate systems .

### 1.3 Render-to-Texture Pipeline

The complete render-to-texture pipeline for side-by-side stereo integrates FBO management, multiple scene renders, and final compositing into a coherent frame generation process. This pipeline must operate within Minecraft's existing rendering framework, respecting the game's assumptions about rendering state and output format .

The pipeline's structure reflects a fundamental tradeoff: **rendering the scene twice per frame doubles the GPU workload** compared to monoscopic rendering. This is the unavoidable cost of genuine stereo 3D, though various optimizations can mitigate the impact. The benefit is complete flexibility in how the two eye views are presented, enabling not only side-by-side format but also future extensions to other stereo formats if needed.

#### 1.3.1 Rendering Scene to Off-Screen FBO

Scene rendering to an FBO proceeds similarly to standard rendering but with modified camera parameters and output destination. The FBO binding established in the previous step ensures that all draw commands affect the off-screen buffers rather than the visible display .

Camera setup for each eye requires modifying both the **view matrix** (camera position and orientation) and potentially the **projection matrix**. For basic stereo, the view matrix is translated left or right by half the inter-pupillary distance, with the translation applied in eye space—along the camera's local X axis—to maintain correct parallax. More sophisticated implementations may also adjust the projection matrix for asymmetric frustums that improve comfort and reduce distortion .

The actual scene rendering should leverage Minecraft's existing `LevelRenderer` where possible, rather than reimplementing the entire rendering pipeline. NeoForge events and access transformers can enable invocation of appropriate rendering methods with modified camera state. The `RenderWorldLastEvent` or similar hooks may provide entry points, though careful attention to event timing is required to ensure proper integration .

#### 1.3.2 Binding Default Screen Framebuffer

After both eye renders complete, the pipeline transitions to compositing by binding the default framebuffer. This restoration is critical: all subsequent operations—texture sampling, quad rendering, GUI overlay—expect to render to the visible display. The binding operation `glBindFramebuffer(GL_FRAMEBUFFER, 0)` is conceptually simple but must be coordinated with other state restoration .

At this point, the viewport should be set to full screen dimensions to prepare for the composite rendering pass. The depth buffer of the default framebuffer is typically not used for compositing since we're rendering full-screen quads, but proper depth configuration is necessary if any subsequent rendering will occur.

#### 1.3.3 Drawing Captured Textures to Split Screen Regions

The final compositing stage draws each eye's texture to its designated screen region. This is accomplished with **simple textured quad rendering**: two full-screen-height quads, one for each eye, positioned side by side. The left eye texture is drawn to the left half, the right eye texture to the right half .

The vertex positions for the quads must account for the current viewport and any desired scaling. A straightforward approach places the left eye quad with corners at **(-1, -1)** to **(0, 1)** in normalized device coordinates and the right eye from **(0, -1)** to **(1, 1)**, with corresponding texture coordinates **(0, 0)** to **(1, 1)** for each. This exactly fills the screen with the two eye views side by side. Alternative mappings can implement letterboxing, scaling, or offset adjustments for calibration purposes .

The compositing shader can be extremely simple—a basic texture sampler with no additional processing—or can incorporate sophisticated post-processing. For X-Real 2 glasses, **minimal processing is generally preferred** to maintain image quality and performance. However, optional features like brightness/contrast adjustment or color channel swapping for different 3D modes can be implemented at this stage .

## 2. NeoForge Rendering Event Integration

### 2.1 Primary Event Hooks for Stereo Rendering

NeoForge 1.21 provides an event-driven architecture for modding, with specific events corresponding to stages of the rendering pipeline. For stereo 3D rendering, identifying the correct events to hook into is crucial for proper integration with Minecraft's rendering pipeline .

The rendering event hierarchy in NeoForge reflects Minecraft's internal frame generation structure. At the highest level, `RenderFrameEvent` brackets the entire frame, with `Pre` and `Post` phases allowing setup and cleanup operations. Within frame rendering, more specific events target world rendering, GUI rendering, and other subsystems .

For stereo 3D implementation, the critical requirement is to intercept rendering at a point where the complete 3D scene can be captured with modified camera parameters, while preserving subsequent GUI and overlay rendering. This suggests hooking into world rendering specifically, rather than the entire frame, to avoid double-rendering GUI elements that should appear identically to both eyes.

#### 2.1.1 `RenderFrameEvent.Post` for Post-Frame Manipulation

The `RenderFrameEvent.Post` event fires after Minecraft completes its entire rendering pipeline for a frame, including world, entities, particles, GUI, and all overlays. This event provides access to the framebuffer for post-processing operations .

For basic side-by-side stereo without complex post-processing, `RenderFrameEvent.Post` offers a straightforward integration point. The mod could perform both eye renders during this event, completely replacing the vanilla frame content with the stereo-composited result. However, this approach requires reimplementing or invoking substantial portions of Minecraft's rendering pipeline within the event handler .

The event's `Post` timing means that any rendering performed in the handler will appear on top of Minecraft's normal output unless the framebuffer is cleared or the previous content is otherwise obscured. For a complete stereo replacement, the handler would need to clear the default framebuffer and draw only the stereo composite, or use blending operations to combine the stereo render with any HUD elements that should remain visible .

#### 2.1.2 `RenderWorldLastEvent` for World Render Interception

`RenderWorldLastEvent` represents a more promising interception point, firing after world geometry rendering but before GUI composition. This timing allows: capture of complete 3D scene information, preservation of depth buffer for potential depth-based effects, and avoidance of GUI duplication issues .

However, this event's position after world rendering means that certain post-world rendering operations—specifically those performed by `LevelRenderer` after `renderLevel` returns—may not be captured. The Stereopsis mod's reported issues with certain HUD elements suggest that complete rendering interception requires additional event handling or core rendering method modification .

A critical consideration is whether this event allows triggering additional world renders with modified camera parameters. If the event fires after the world render is complete, modifying the camera and triggering another render would require understanding how to invoke `LevelRenderer` methods properly. The research indicates that `RenderWorldLastEvent` is "called after the world is rendered, but before the overlays are added" , suggesting that the world render has already occurred when this event fires.

#### 2.1.3 Event Priority and Cancellation Considerations

Event handlers in NeoForge can specify priority annotations that control the order in which multiple handlers for the same event are invoked. For stereo 3D rendering, the mod's handlers should typically use **EventPriority.HIGH** or **EventPriority.HIGHEST** to ensure its handlers execute before lower-priority handlers that may depend on rendering state .

Cancellation of rendering events should be approached with extreme caution, as many mods depend on specific rendering stages for functionality. The Stereopsis mod's compatibility notes mention specific incompatibilities with rendering-related mods like Exordium, indicating that event interaction complexity is a significant concern . A more cooperative approach involves performing stereo rendering without cancellation, accepting that some rendering work may be duplicated or that the vanilla output will be overwritten.

### 2.2 Accessing Render Context in Events

Effective stereo rendering requires extracting and manipulating several key objects from NeoForge events. These objects provide the bridge between the event system and the underlying OpenGL state, allowing mods to implement sophisticated rendering modifications without direct access to private Minecraft fields .

#### 2.2.1 Obtaining `MatrixStack` from Event Parameters

The `MatrixStack` (or `PoseStack` in newer mappings) represents the transformation hierarchy for the current rendering operation. For stereo 3D, this stack must be manipulated to incorporate the eye offset transformation. The typical pattern involves calling `stack.pushPose()` to create a new transformation frame, applying the eye-specific translation with `stack.translate(x, y, z)`, performing the render operations, and finally calling `stack.popPose()` to restore the previous state .

The eye offset translation must be applied in view space, meaning it should affect the camera position but not the orientation. In matrix terms, this corresponds to a translation along the camera's right vector (positive for right eye, negative for left eye). The magnitude of this translation is **half the inter-pupillary distance**, typically around **0.03 to 0.065 meters** depending on user configuration and the scale of the Minecraft world .

#### 2.2.2 Accessing `Framebuffer` Instance for Dimensions

Knowledge of the current framebuffer dimensions is essential for proper viewport and scissor rectangle calculations. The `Framebuffer` instance (or `RenderTarget` in some mappings) provides `width` and `height` fields that reflect the current rendering surface's dimensions. For windowed Minecraft, these dimensions change when the user resizes the window; for fullscreen, they match the display mode. The stereo 3D implementation must query these dimensions each frame rather than caching them, as they may change dynamically .

The framebuffer instance also provides methods for binding (`bindWrite`), clearing (`clear`), and blitting (`blitToScreen`) operations. For the FBO-based stereo implementation, the mod will create custom framebuffer instances with dimensions based on these source dimensions (typically half width, full height), but the original screen framebuffer remains important for the final compositing phase .

#### 2.2.3 Retrieving `LevelRenderer` and `Camera` References

The `LevelRenderer` class encapsulates Minecraft's world rendering logic, managing chunk meshes, entity rendering, and environmental effects. For advanced stereo 3D implementations that need fine-grained control over the rendering process, access to this instance is valuable. The `Minecraft.getInstance().levelRenderer` field provides this access, though as with all Minecraft internals, this represents a dependency on implementation details that may change between versions .

The `Camera` class represents the viewpoint from which the world is rendered, encapsulating position, rotation, and projection parameters. For stereo 3D, the camera must be effectively "split" into two instances with offset positions. Direct camera manipulation is challenging because much of Minecraft's rendering code assumes a single global camera state. The most robust approaches either manipulate the camera before each eye's render pass or use matrix transformations that achieve equivalent results without modifying the camera object directly .

### 2.3 Event Handler Registration

Proper registration of event handlers ensures that mod code executes at the appropriate times and on the appropriate threads. NeoForge distinguishes between the mod event bus (for initialization-phase events) and the game event bus (for runtime events). Rendering events typically register on the game event bus, which fires continuously during gameplay .

#### 2.3.1 `@SubscribeEvent` Annotation Usage

The `@SubscribeEvent` annotation marks methods that should be invoked when specific events fire. The annotation can be applied to static or instance methods, with different registration patterns for each. For rendering events that need to maintain state across frames (like FBO references, cached dimensions, or configuration values), instance methods on a singleton handler object are typically preferred .

The annotated method must accept exactly one parameter: the event type being handled. The parameter type determines which events will trigger the method; multiple methods can handle the same event type with different priorities. The method should be `public` and return `void`. NeoForge's event bus uses reflection to discover and invoke these methods, with performance-optimized invocation paths for frequently-fired events .

#### 2.3.2 Event Bus Registration in Mod Constructor

Event handler registration occurs in the mod's constructor or initialization methods. For the game event bus (where rendering events fire), registration uses `NeoForge.EVENT_BUS.register(handlerInstance)` for instance methods or `NeoForge.EVENT_BUS.register(HandlerClass.class)` for static methods. The registration must occur during mod initialization; attempting to register at arbitrary points during execution may fail or produce inconsistent results .

The mod constructor is the appropriate place for this registration because it executes during the `FMLConstructionEvent` phase, before the game begins running. The constructor receives an `IEventBus` parameter for the mod-specific event bus, but rendering events fire on the global game bus accessed via `NeoForge.EVENT_BUS`. Both buses may be relevant for a complete mod implementation, with initialization events on the mod bus and runtime events on the game bus .

#### 2.3.3 Client-Side Only Event Handling with `Dist.CLIENT`

The `@Mod` annotation accepts a `dist` parameter that restricts mod loading to specific distribution types. For rendering mods, `@Mod(value = "modid", dist = Dist.CLIENT)` ensures the mod only loads on client distributions, preventing crashes from client-only code executing on dedicated servers. This is essential because rendering classes simply don't exist in the server environment .

Within a client-only mod, additional safety can be achieved by checking `FMLLoader.getDist()` or using `DistExecutor` to run code only on the physical client. However, for mods that are entirely client-side, the `@Mod` distribution restriction is usually sufficient. The NeoForge documentation emphasizes isolating client code in dedicated packages as an organizational best practice, even when technical restrictions already prevent server execution .

## 3. Camera and Projection Matrix Modifications

### 3.1 Eye Separation and Parallax Calculation

The physiological basis for stereo 3D perception lies in the horizontal separation between human eyes, typically termed the **interpupillary distance (IPD)**. For X-Real 2 glasses and similar AR devices, accurate IPD simulation is essential for comfortable, convincing depth perception. The magnitude of the differences—horizontal parallax—depends on the IPD and the distance to objects, with closer objects showing greater disparity .

#### 3.1.1 Defining Interpupillary Distance for X-Real 2 Glasses

The average human IPD ranges from approximately **55mm to 75mm**, with a population mean near **63mm**. However, the effective IPD for AR rendering depends on the optical system's characteristics. The X-Real 2 uses birdbath optics with a specific virtual image distance that must be considered when calculating appropriate separation values. Without official technical specifications, implementation must rely on **user-configurable parameters with sensible defaults** .

For Minecraft specifically, the IPD must be expressed in block-scale units. If one Minecraft block represents one meter, then a 63mm IPD becomes **0.063 blocks**. However, this scale assumption may not match the perceived scale through AR glasses, where virtual objects may appear at different apparent distances than their geometric placement would suggest. The implementation should expose IPD as a **user-configurable parameter in real-world millimeters**, with internal conversion to Minecraft units based on an assumed or calibrated scale factor .

#### 3.1.2 Calculating Left and Right Eye Offsets from Center

Given a head position and viewing direction, the left and right eye positions are calculated by offsetting perpendicular to the view direction by half the IPD in each direction. Mathematically, if the view direction is represented by unit vector **F** (forward) and the up direction by unit vector **U**, the right vector **R** = **F** × **U** (cross product). The left eye position is then **Head** - (**R** × IPD/2) and the right eye position is **Head** + (**R** × IPD/2) .

This calculation must be performed each frame because the view direction changes with head rotation. In the context of Minecraft without head tracking, the view direction comes from the mouse-controlled camera orientation. For X-Real 2 integration with potential future head tracking support, the calculation would incorporate the tracked head pose. The offset vectors must be recalculated in the rendering thread context where the current view matrices are accessible .

#### 3.1.3 Convergence Plane Adjustment for Comfortable Viewing

The convergence plane is the virtual depth at which objects appear with zero parallax—directly in front of the viewer with no horizontal offset between eye views. Objects closer than this plane show negative parallax (appearing to pop out toward the viewer), while distant objects show positive parallax (receding into the screen). **Proper convergence plane placement is critical for comfortable viewing**; excessive negative parallax causes eye strain, while excessive positive parallax can cause the images to diverge beyond the viewer's fusion range .

For AR applications, the convergence plane is typically placed at or near the optical focus distance of the display system. For X-Real 2, this would correspond to the apparent distance of the virtual image produced by the optics. Without specific technical data, a **default convergence distance of 2-3 meters** provides a reasonable starting point, with user adjustment available. The convergence plane is implemented through subtle toe-in of the view frustums or through asymmetric frustum calculations, not through simple translation alone .

### 3.2 View Matrix Transformations

The view matrix transforms from world coordinates to camera coordinates, incorporating both position and orientation. For stereo 3D, each eye requires its own view matrix with the appropriate position offset. The orientation remains identical between eyes (parallel cameras) or includes slight convergence rotation (toe-in) depending on the chosen projection approach. **Parallel cameras with asymmetric frustums are generally preferred for AR applications** as they avoid geometric distortions .

#### 3.2.1 Translating Camera Position for Each Eye

The eye offset translation is most straightforwardly applied as a world-space translation before the view transformation. In `PoseStack` terms, this corresponds to `translate(-eyeOffsetX, -eyeOffsetY, -eyeOffsetZ)` applied after the standard view orientation but before projection. The negative sign reflects that moving the camera right (positive X) is equivalent to moving the world left .

The translation must be expressed in the coordinate system active at the point of application. If applied in world space, the offset vector must be rotated to match the camera orientation. If applied in view space (after the view rotation), a simple X-axis translation suffices. The `PoseStack` operations accumulate right-to-left in matrix multiplication order, so the sequence of pushes, translations, and pops must be carefully ordered to achieve the desired result .

#### 3.2.2 Applying `GlStateManager.pushMatrix()` and `popMatrix()`

While `PoseStack` represents the modern approach, legacy code and certain rendering paths may still use direct OpenGL matrix operations through `GlStateManager`. The `pushMatrix()` and `popMatrix()` operations manipulate the legacy OpenGL matrix stack, which operates independently of the `PoseStack` system. For comprehensive stereo 3D, both systems may need coordination .

The safest approach is to ensure that any `GlStateManager` matrix modifications are properly scoped with push/pop pairs and that the matrix state is known at the entry and exit of stereo rendering code. Ideally, modern code paths use exclusively `PoseStack`, but Minecraft's large codebase contains legacy elements. Debugging matrix-related rendering issues often requires inspecting both systems and understanding their interaction .

#### 3.2.3 Modifying `MatrixStack` with `translate()` Operations

The `MatrixStack` (or `PoseStack`) `translate()` method applies a translation transformation to the current matrix. For stereo 3D, the critical translation is the eye offset applied in view space. The typical pattern involves: obtaining the current `PoseStack` from the rendering context, calling `pushPose()` to save state, calling `translate()` with the negative eye offset, performing the render operations, and finally calling `popPose()` to restore .

The translation values must be calculated based on the current view direction to ensure the offset is perpendicular to the line of sight. For a view matrix that transforms world coordinates to camera coordinates, the eye offset in camera space is simply **(±IPD/2, 0, 0)**. The challenge lies in ensuring this transformation is applied consistently across all rendering code paths, including those that may cache or manipulate matrices independently .

### 3.3 Projection Matrix Considerations

The projection matrix transforms from camera coordinates to clip coordinates, implementing the perspective transformation that creates the appearance of depth on a flat display. For stereo 3D, the projection matrix must be carefully constructed to produce correct parallax while minimizing geometric distortions. The standard symmetric perspective projection used in monoscopic rendering is inadequate; **asymmetric frustums are required for proper stereo geometry** .

#### 3.3.1 Asymmetric Frustum for Reduced Keystone Distortion

Keystone distortion occurs when the view frustum is asymmetric in a way that causes rectangular objects to appear trapezoidal. The toe-in approach to stereo (rotating cameras toward a convergence point) introduces keystone distortion because the image planes are no longer parallel to the projection of vertical world lines. **Parallel cameras with asymmetric frustums avoid this** by maintaining parallel image planes while shifting the frustum laterally .

The asymmetric frustum is defined by four parameters: left, right, bottom, and top clipping plane offsets, plus near and far distances. For symmetric frustum with field of view `fov` and aspect ratio `aspect`, the offsets are `±aspect * near * tan(fov/2)` for left/right and `±near * tan(fov/2)` for top/bottom. For asymmetric stereo, the left and right values are shifted by `±(IPD/2) * near / convergenceDistance`, creating the desired parallax behavior without rotation .

#### 3.3.2 Field of View Adjustments for AR Glasses

The effective field of view (FOV) of AR glasses is determined by their optical system and may differ significantly from the FOV of conventional displays. The X-Real 2 specifications indicate a **46-degree diagonal FOV**, which translates to approximately **40-degree horizontal FOV** depending on aspect ratio. This is narrower than typical monitor-based Minecraft FOV settings (often 70-90 degrees horizontal) .

The mod should allow **FOV configuration independent of Minecraft's standard FOV setting**, as the AR glasses' optical FOV determines the appropriate rendering parameter. Rendering with too wide a FOV causes the virtual world to appear shrunken and distant; too narrow causes cropping and an unnatural zoomed appearance. User calibration may be necessary to match the rendered FOV to the perceived optical FOV for comfortable immersion .

#### 3.3.3 Near and Far Plane Configuration

The near and far clipping planes bound the depth range that will be rendered. The near plane is particularly critical for AR applications, as objects closer than this distance will be clipped, creating disturbing holes in the virtual world. However, setting the near plane too close reduces depth buffer precision and can cause z-fighting artifacts on distant geometry. **Minecraft's default near plane of 0.05 blocks (5cm) is likely adequate for most AR use cases** .

The far plane primarily affects sky rendering and distant terrain visibility. For AR glasses where the real world remains visible, extremely distant virtual objects may conflict with real-world depth perception. A far plane at the standard render distance (typically 12-32 chunks, or 192-512 blocks) is generally appropriate, with the understanding that virtual objects at maximum distance will appear as an overlay on the real world rather than replacing it .

## 4. Implementation Structure and Code Organization

### 4.1 Main Mod Class Setup

The main mod class serves as the entry point for NeoForge's mod loading system, establishing the foundation for all stereo 3D functionality. Proper structure ensures clean separation of concerns and maintainable code as the mod grows in complexity .

#### 4.1.1 `@Mod` Annotation and Mod ID Definition

The `@Mod` annotation's required `value` parameter specifies the mod's unique identifier, which must match an entry in `neoforge.mods.toml`. For a stereo 3D mod targeting X-Real 2 glasses, an identifier like `"xrealstereo"` or `"stereo3d"` would be appropriate. The `dist` parameter should specify `Dist.CLIENT` to prevent loading on dedicated servers .

```java
@Mod(value = "xrealstereo", dist = Dist.CLIENT)
public class XRealStereoMod {
    // Mod implementation
}
```

This annotation triggers NeoForge's mod discovery system to instantiate this class during the construction phase. The mod ID becomes the namespace for resources, configuration files, and inter-mod communication. Choosing a clear, unique ID prevents conflicts with other mods .

#### 4.1.2 Constructor with `FMLClientSetupEvent` Registration

The mod constructor receives an `IEventBus` parameter for the mod-specific event bus, along with optional `ModContainer` and `Dist` parameters. Client-specific initialization should register for `FMLClientSetupEvent`, which fires when the client is ready for initialization that requires Minecraft classes to be loaded .

```java
public XRealStereoMod(IEventBus modBus, ModContainer container) {
    modBus.addListener(this::onClientSetup);
    NeoForge.EVENT_BUS.register(new StereoRenderHandler());
}

private void onClientSetup(FMLClientSetupEvent event) {
    // Client-only initialization
    StereoConfig.register();
    KeyBindingRegistry.register();
}
```

The `onClientSetup` method receives `FMLClientSetupEvent` and performs initialization that requires access to `Minecraft` instance and other client-only classes. This includes registering key bindings, configuring default settings, and initializing rendering resources like shaders or FBOs .

#### 4.1.3 DistExecutor for Client-Side Initialization

`DistExecutor` provides a type-safe way to execute code only on specific distribution types. While the `@Mod` annotation prevents the entire mod class from loading on servers, individual methods may still need distribution-conditional execution when shared code paths exist. The `unsafeRunWhenOn` method accepts a `Dist` and `Supplier` to execute conditionally .

For comprehensive safety, rendering resource initialization can be wrapped:

```java
DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
    // Client-only initialization
    StereoConfig.register();
    KeyBindingRegistry.register();
});
```

This pattern ensures that client-only classes are never referenced in code that might execute on a server, preventing `ClassNotFoundException` crashes .

### 4.2 Stereo Rendering Manager Class

The `StereoRenderHandler` (or similarly named) class encapsulates the core stereo 3D rendering logic, implementing `RenderFrameEvent.Post` or other appropriate event handlers. This class maintains the state necessary for stereo rendering: FBO references, current dimensions, configuration values, and cached calculations. Separation into a dedicated class keeps the main mod class clean and allows the rendering logic to evolve independently .

#### 4.2.1 FBO Initialization and Management

FBO management requires tracking current dimensions to detect resize events, creating appropriately-sized color and depth attachments, and validating framebuffer completeness. The handler should create FBOs lazily on first render or during `FMLClientSetupEvent`, and recreate them when dimensions change .

```java
private void ensureFbos(int width, int height) {
    if (leftFbo != null && leftFbo.width == width / 2 && leftFbo.height == height) {
        return; // Still valid
    }
    // Dispose old FBOs if exist
    if (leftFbo != null) leftFbo.destroyBuffers();
    if (rightFbo != null) rightFbo.destroyBuffers();
    
    // Create new FBOs at half width, full height
    leftFbo = new TextureTarget(width / 2, height, true, false);
    rightFbo = new TextureTarget(width / 2, height, true, false);
}
```

The `TextureTarget` class (or equivalent in specific Minecraft version) wraps FBO creation with convenient methods for binding and clearing. Proper error checking with `glCheckFramebufferStatus` ensures that incomplete framebuffers are detected before use .

#### 4.2.2 Viewport Dimension Calculation

Dynamic viewport calculation ensures correct rendering regardless of window size or aspect ratio. The calculation divides screen width by two for each eye, with careful handling of odd widths:

```java
int screenWidth = minecraft.getWindow().getWidth();
int screenHeight = minecraft.getWindow().getHeight();
int eyeWidth = screenWidth / 2;
int leftWidth = eyeWidth;
int rightWidth = screenWidth - eyeWidth; // Handles odd total width

// Left eye viewport: (0, 0, leftWidth, screenHeight)
// Right eye viewport: (leftWidth, 0, rightWidth, screenHeight)
```

This approach ensures the entire screen is covered without gaps or overlaps, regardless of whether the total width is even or odd. Height should always be equal for both eyes to prevent vertical misalignment that causes significant discomfort .

#### 4.2.3 Eye Offset Configuration Storage

Configuration values like IPD, convergence distance, and FOV should be stored in a persistent configuration system. NeoForge provides `ModConfigSpec` for type-safe configuration with automatic GUI generation. The rendering manager reads these values each frame or caches them with invalidation on configuration changes .

```java
public static final ModConfigSpec.DoubleValue IPD_MM = BUILDER
    .comment("Inter-pupillary distance in millimeters")
    .defineInRange("ipdMm", 63.0, 50.0, 80.0);
```

The manager converts millimeter values to block-scale units using an assumed scale factor, with potential future enhancement for user calibration .

### 4.3 Configuration System for X-Real 2 Compatibility

A robust configuration system enables users to tune the stereo 3D experience for their specific hardware and visual preferences. For X-Real 2 glasses, certain defaults may be more appropriate than generic stereo settings, but user variation in head size, glasses fit, and visual acuity necessitates adjustable parameters .

#### 4.3.1 ModMenu Integration for In-Game Settings

ModMenu (a Fabric mod with NeoForge equivalents) provides in-game configuration GUI access. While not strictly required, integration with such systems dramatically improves user experience compared to manual file editing. The configuration spec automatically generates appropriate GUI controls based on value types .

For NeoForge-native configuration without external dependencies, `ConfigScreenHandler` can register a custom configuration screen factory that creates an appropriate GUI using Minecraft's screen system. This requires more implementation effort but avoids external dependencies .

#### 4.3.2 Toggle for AR Mode vs. Standard Stereo

The X-Real 2 glasses may benefit from specific rendering optimizations compared to generic side-by-side stereo displays. An **"AR Mode" toggle** could enable: adjusted convergence defaults for AR optical distance, reduced brightness or contrast to blend better with real-world visibility, or specific FOV calibration. When disabled, the mod operates in generic side-by-side mode compatible with 3D TVs and other stereo displays .

```java
public static final ModConfigSpec.EnumValue<ViewMode> VIEW_MODE = BUILDER
    .comment("Rendering mode for different display types")
    .defineEnum("viewMode", ViewMode.SIDE_BY_SIDE);

public enum ViewMode {
    SIDE_BY_SIDE,    // Generic half-width side-by-side
    AR_OPTIMIZED,    // X-Real 2 specific adjustments
    CROSS_EYE,       // Reversed for cross-eye viewing
    ANAGLYPH         // Red-cyan for testing without hardware
}
```

#### 4.3.3 Adjustable Eye Separation and Convergence Parameters

Beyond basic IPD, advanced users may benefit from controlling convergence plane distance and individual eye separation scaling. These parameters allow compensation for individual visual characteristics and hardware variations. The configuration should present these as advanced options with clear explanations of their effects .

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| IPD (mm) | 63.0 | 50.0–80.0 | Physical eye separation |
| Convergence (m) | 2.0 | 0.5–10.0 | Zero-parallax distance |
| Separation scale | 1.0 | 0.5–2.0 | Multiplier for effect intensity |
| FOV override | 70 | 30–110 | Vertical field of view |

*Table 2: Core Stereo Configuration Parameters*

## 5. Shader and Post-Processing Considerations

### 5.1 Vanilla Shader Compatibility

Minecraft's rendering increasingly relies on shader programs for both core functionality and visual effects. The stereo 3D implementation must coexist with these shaders, ensuring that the dual-render approach produces correct results when shaders are active. This compatibility challenge is significant because shaders may make assumptions about camera position, screen dimensions, or rendering passes that are violated by stereo rendering .

#### 5.1.1 Identifying Shader-Induced Rendering Issues

Common symptoms of shader incompatibility include: **incorrect depth effects** where shaders compute depth-based fog or blur using wrong camera parameters; **screen-space effects that sample from incorrect regions** due to assumed full-screen rendering; and **temporal effects that accumulate incorrectly** across the two eye renders. Identifying these issues requires systematic testing with popular shader packs and careful visual inspection of the results .

The debugging process should isolate whether issues stem from the stereo transformation itself or from interaction with specific shader features. Disabling individual shader effects (shadows, reflections, post-processing) can help identify the problematic component. Screenshot comparison between monoscopic and stereoscopic rendering with identical shader configuration reveals where discrepancies arise .

#### 5.1.2 Disabling or Patching Conflicting Shaders

When specific shader effects prove incompatible, the mod may need to disable them during stereo rendering or patch their behavior. Disabling can be achieved by modifying the active shader program or by setting uniform values that cause problematic code paths to be skipped. Patching requires more sophisticated shader injection or replacement .

For core shader modifications, NeoForge's `RegisterShadersEvent` allows registration of custom shader programs. The mod could provide stereo-aware versions of problematic shaders, though maintaining compatibility across shader pack versions becomes a significant maintenance burden. A more practical approach may be documenting known-compatible shader configurations and providing user guidance .

#### 5.1.3 Fallback Rendering Paths for Shader Incompatibility

When shader incompatibility is detected or user-configured, the mod should gracefully fall back to vanilla rendering or simplified stereo rendering. This ensures the mod remains functional even when ideal conditions aren't met. The fallback path might use a simpler compositing approach or disable certain stereo optimizations that conflict with active shaders .

Detection of active shaders can occur through inspection of the current render pipeline state or through integration with shader mod APIs if available. Iris/Oculus shader mods may expose configuration or status information that enables more intelligent fallback decisions .

### 5.2 Custom Shader Integration (Optional)

Beyond compatibility with existing shaders, the stereo 3D mod may benefit from custom shaders for the compositing phase or for AR-specific corrections. Custom shaders enable precise control over how the two eye textures combine and allow implementation of optical distortion correction for the X-Real 2 glasses' specific lens characteristics .

#### 5.2.1 GLSL Shader Programs for Final Compositing

The compositing shader is conceptually simple: sample from left eye texture for left screen half, sample from right eye texture for right screen half. However, practical implementation benefits from additional features: gamma correction for consistent brightness, color space conversion if the AR glasses expect specific encoding, and edge blending to reduce the visible seam between eye views .

A basic compositing vertex shader:

```glsl
#version 150
in vec2 Position;
in vec2 UV;
out vec2 texCoord;
void main() {
    gl_Position = vec4(Position, 0.0, 1.0);
    texCoord = UV;
}
```

And fragment shader:

```glsl
#version 150
uniform sampler2D LeftEye;
uniform sampler2D RightEye;
uniform float SplitX; // Normalized x coordinate of split
in vec2 texCoord;
out vec4 fragColor;

void main() {
    if (texCoord.x < SplitX) {
        fragColor = texture(LeftEye, vec2(texCoord.x / SplitX, texCoord.y));
    } else {
        float rightU = (texCoord.x - SplitX) / (1.0 - SplitX);
        fragColor = texture(RightEye, vec2(rightU, texCoord.y));
    }
}
```

#### 5.2.2 Texture Sampling from Left and Right FBOs

The shader receives the FBO color textures bound to texture units. The `RenderSystem` class provides methods for setting active texture unit and binding specific textures. The uniform values for texture samplers must be set before drawing, typically to 0 and 1 for the first and second texture units .

Texture filtering configuration affects the quality of the final composited image. **GL_LINEAR** filtering provides smooth interpolation when the texture is eventually drawn to screen, though **GL_NEAREST** might be desirable for certain pixel-art aesthetic preferences. The `GpuTextureView` abstraction in modern NeoForge versions requires careful handling of texture view creation and binding .

#### 5.2.3 Barrel Distortion Correction for AR Glasses Optics

X-Real 2 glasses may benefit from barrel distortion correction to compensate for lens optics. The distortion model typically follows a radial pattern: `r_corrected = r * (1 + k1*r^2 + k2*r^4)` where k1 and k2 are device-specific coefficients. These coefficients should be **user-configurable or auto-detected** if the glasses provide calibration data .

The correction is implemented in the compositing shader by transforming texture coordinates before sampling:

```glsl
vec2 distort(vec2 coord, float strength) {
    vec2 center = vec2(0.5);
    vec2 delta = coord - center;
    float dist = length(delta);
    float factor = 1.0 + strength * dist * dist;
    return center + delta * factor;
}
```

### 5.3 Render Type and Buffer Management

Minecraft's render type system categorizes geometry by visual characteristics and required GPU state, with significant implications for stereo 3D implementation. The research shows that NeoForge 1.21 allows mods to register custom render types through `RegisterNamedRenderTypesEvent`, though this is primarily relevant for block and item rendering rather than the frame-level stereo composition .

For stereo rendering, the critical consideration is ensuring that all relevant render types are captured in both eye passes. Minecraft's render type system includes: solid blocks, cutout blocks, translucent blocks, tripwire, entities, and various particle types. The standard `LevelRenderer` handles these in a specific order, and stereo implementation must preserve this ordering for each eye to maintain visual correctness .

## 6. X-Real 2 Specific Integration

### 6.1 AR Mode Activation

The AR Mode configuration, as referenced in the Stereopsis mod documentation, represents a specialized rendering path optimized for AR glasses including the X-Real 2 . This mode adjusts multiple rendering parameters simultaneously to match the characteristics of see-through displays, where virtual content overlays the real world rather than replacing it.

#### 6.1.1 Configuration Toggle for X-Real 2 Compatibility

The AR Mode setting adjusts rendering parameters to match the specific characteristics of AR glasses rather than traditional displays. This includes: **eye separation optimized for X-Real 2's fixed optics**, potential barrel distortion correction, and brightness/contrast adjustment for the glasses' micro-OLED displays. The implementation should document these optimizations and provide clear explanation of when AR mode is appropriate versus standard stereo mode .

#### 6.1.2 Automatic Detection of Connected Glasses (if supported)

Future SDK integration could enable automatic mode switching based on display EDID detection, identifying X-Real 2's characteristic **3840×1080 resolution** . However, such detection should never be mandatory—users must retain manual control for compatibility with future hardware and edge cases. The XREAL SDK for Unity provides device detection capabilities , but no equivalent Java API is documented for direct Minecraft integration.

#### 6.1.3 Head Tracking Integration Considerations

X-Real 2 glasses include head tracking capabilities through the XREAL SDK . Integration with Minecraft's camera system would allow head rotation to control in-game view direction, creating a more immersive experience. However, this requires: native SDK integration through JNI or similar, handling of tracking data streams, and reconciliation with mouse input for hybrid control schemes. Given the complexity and lack of documented Java SDK, **head tracking integration is a future enhancement rather than core functionality** .

### 6.2 Display Characteristics Adaptation

The user's explicit requirement for **resolution-independent implementation** aligns well with X-Real 2's display architecture. Rather than targeting fixed pixel dimensions, the implementation dynamically calculates viewport boundaries based on whatever framebuffer size is active .

#### 6.2.1 Side-by-Side Format Without Resolution Targeting

This approach ensures functionality across: standard 16:9 displays (1920×1080, 2560×1440, 3840×2160), ultrawide displays (2560×1080, 3440×1440), native X-Real 2 resolution (3840×1080), and arbitrary window sizes. The mathematical operation is straightforward: given screen width **W** and height **H**, the left eye viewport is **(0, 0, W/2, H)** and the right eye viewport is **(W/2, 0, W/2, H)** .

#### 6.2.2 Aspect Ratio Preservation Across Different Displays

Each eye's viewport maintains the window's original aspect ratio, preventing geometric distortion. For non-standard displays like X-Real 2, user-configurable aspect ratio correction may be valuable if the glasses' optics introduce perceived geometric distortion .

#### 6.2.3 Dynamic Viewport Scaling for Variable Screen Sizes

Window resize events trigger FBO recreation and viewport recalculation. The implementation should detect these changes through framebuffer dimension monitoring and recreate FBOs with appropriate new dimensions without requiring game restart .

### 6.3 User Experience Optimization

Effective stereo 3D implementation requires attention to user interaction patterns and comfort considerations. The Stereopsis mod's approach of requiring **explicit hotkey activation**—"You'll need to bind a key for it first, it's unbound by default" —represents thoughtful design, as continuous stereo rendering can cause fatigue and is not appropriate for all gameplay situations.

#### 6.3.1 Hotkey Toggle for Stereo Mode Activation

Hotkey toggle implementation should use NeoForge's key binding system, registering a `KeyMapping` during client setup and checking for key state in appropriate event handlers. The toggle should provide **clear visual feedback** when activated, potentially through: on-screen indicator, brief notification message, or audio cue. State should persist across game sessions through configuration saving .

#### 6.3.2 On-Screen Indicators for Mode Status

A subtle corner indicator showing **"STEREO: ON"** or **"AR MODE"** with appropriate color coding provides mode information without interfering with gameplay. The indicator should be positioned to appear in both eyes (centered or duplicated) rather than at screen edges where it might be clipped or appear only in one eye .

#### 6.3.3 Comfort Settings for Extended AR Sessions

Configuration options should include: **maximum stereo separation** (preventing excessive values), **convergence distance adjustment** (matching individual comfort), and optional **vignette or comfort zone indicators** at screen edges. The implementation should also consider frame rate stability, as inconsistent timing significantly contributes to discomfort in AR/VR applications .

## 7. Performance Optimization Strategies

### 7.1 Rendering Efficiency

The fundamental performance challenge of stereo 3D is the **dual rendering requirement**—each frame must be generated twice, from different viewpoints. This inherently doubles GPU workload for geometry processing, vertex transformation, and fragment shading, with corresponding impact on frame rates .

| Technique | Implementation Complexity | Performance Impact | Quality | Compatibility |
|-----------|--------------------------|-------------------|---------|-------------|
| Multi-pass (standard) | Low | ~50% FPS (baseline) | Full | Excellent |
| Single-pass stereo | Very High | +80-100% FPS | Reduced culling | Poor |
| Instanced geometry | High | +60-80% FPS | Full with GPU support | Moderate |
| FBO resolution scaling | Low | +50-150% FPS | Configurable | Excellent |

*Table 3: Stereo Rendering Performance Techniques*

#### 7.1.1 Single-Pass vs. Multi-Pass Rendering Trade-offs

Single-pass stereo rendering techniques, common in VR applications, attempt to render both eyes in a single geometry submission using instancing or geometry shader duplication. These approaches reduce CPU overhead and geometry processing costs by sharing vertex transformation work between eyes. However, **Minecraft's rendering architecture is not designed for this approach**, and implementation would require significant modifications to `LevelRenderer` and related systems. For initial implementation, **dual-pass with optimization is more practical** .

#### 7.1.2 FBO Resolution Scaling for Performance Gains

Rendering each eye at **0.75x or 0.5x linear resolution** and upsampling during composition reduces fragment shading cost quadratically (0.56x and 0.25x respectively). For X-Real 2's limited display resolution, aggressive downsampling may be imperceptible while significantly improving frame rates. The implementation should provide **user-configurable scale factors** with clear quality/performance trade-off documentation .

#### 7.1.3 Occlusion Culling per Eye Optimization

Separate frustum culling per eye can reduce total rendered geometry versus naive double rendering of all visible objects. However, the additional CPU cost of dual culling calculations and potential popping artifacts when objects appear in one eye before the other typically outweigh benefits. **Conservative culling using a merged frustum** or simply accepting minor over-rendering is usually preferable .

### 7.2 Memory Management

GPU memory management is critical for stable stereo 3D performance, particularly on systems with limited VRAM. The FBO resources for stereo rendering require: **two color textures** (W/2 × H × 4 bytes for RGBA8) and **two depth textures** (W/2 × H × 4 bytes for DEPTH24_STENCIL8). For 1920×1080 rendering, this totals approximately **16MB**—modest by modern standards but not negligible for integrated graphics or multi-mod scenarios .

#### 7.2.1 FBO Texture Memory Allocation

Dynamic texture resolution based on GPU capabilities provides automatic quality adjustment. The implementation can query available VRAM through `GpuDevice` capabilities and select appropriate scale factors. Systems with less than 2GB VRAM might default to 0.75x scaling, while high-end systems could offer 1.5x or 2.0x supersampling for enhanced quality .

#### 7.2.2 Proper Resource Disposal on Mod Disable

FBO textures and framebuffers must be explicitly destroyed when: the mod is disabled, display dimensions change (requiring recreation), or the game shuts down. NeoForge 1.21's resource management emphasizes explicit lifecycle control, and failure to properly dispose GPU resources can cause crashes or degraded performance over extended play sessions .

#### 7.2.3 Dynamic Texture Resolution Based on GPU Capabilities

Querying GPU capabilities at initialization enables appropriate default settings. The implementation should expose this as **overrideable by user preference**, with real-time adjustment through configuration GUI .

### 7.3 Frame Timing and Synchronization

Consistent frame timing is essential for comfortable stereo 3D viewing, as timing variations are more noticeable and discomforting when presented to both eyes. The implementation should **prioritize frame pacing over maximum frame rates**, potentially capping at display refresh rate to maintain consistency .

#### 7.3.1 Maintaining Consistent Frame Rates for Both Eyes

Frame time variance between eyes creates temporal artifacts; strict timing enforcement through `GLFence` synchronization may be necessary for high-refresh AR displays. The dual-pass implementation naturally ensures both eyes render in the same frame interval .

#### 7.3.2 V-Sync Considerations for AR Display Compatibility

The X-Real 2 may have specific display timing requirements, and mismatches between game frame delivery and display consumption can cause judder or tearing. If the glasses support variable refresh rate or have specific timing preferences, the implementation should document recommended Minecraft video settings. **Generally, enabling V-Sync provides the most consistent experience** for fixed-refresh displays .

#### 7.3.3 Frame Pacing to Prevent Motion Sickness

Consistent frame delivery more important than peak frame rates; **target locked 30 FPS over variable 45-60 FPS** for comfort. The implementation should monitor frame times and optionally reduce quality automatically to maintain stable pacing .

## 8. Testing, Debugging, and Validation

### 8.1 Development Testing Without X-Real 2 Hardware

Effective development requires validation techniques that don't depend on target hardware availability. Multiple approaches enable stereo effect verification using standard displays .

#### 8.1.1 Cross-Eye Viewing Method for Verification

Cross-eye viewing requires no special equipment and provides immediate stereo feedback. The viewer focuses their eyes beyond the screen, allowing the left and right images to merge into a single perceived 3D image. This technique requires: sufficient screen size for comfortable viewing, appropriate eye separation in the rendered output (may need adjustment for screen viewing distance), and practice to achieve reliable fusion. The Stereopsis mod explicitly mentions this verification approach .

#### 8.1.2 Anaglyph Preview Mode for Debugging

Anaglyph preview mode converts the side-by-side stereo into red-cyan anaglyph format viewable with inexpensive glasses. Implementation involves: extracting luminance from each eye, applying color channel mapping (left eye to red, right eye to cyan), and compositing to single output. While color accuracy is lost, depth perception is preserved, enabling verification by collaborators without stereo viewing capability .

#### 8.1.3 Screenshot Capture for Offline Analysis

Automated capture of left and right eye frames separately enables: pixel-level comparison to verify correct parallax, measurement of actual separation values, and identification of rendering inconsistencies. Automated comparison tools could detect issues like: missing geometry in one eye, color/intensity mismatches, or alignment errors .

### 8.2 Common Rendering Artifacts and Solutions

Stereo 3D rendering introduces specific artifact categories that require systematic identification and resolution .

| Artifact | Cause | Solution |
|----------|-------|----------|
| Vertical parallax | Incorrect camera up-vector; head tilt unaccounted | Verify view orientation identical for both eyes; measure feature correspondence |
| Edge distortion | Viewport/scissor mismatch; filtering artifacts | Ensure exact pixel alignment; use `GL_CLAMP_TO_EDGE`; disable cross-viewport sampling |
| Depth buffer precision | Inconsistent near/far planes; reduced resolution at half-width | Maintain full-resolution depth buffers; use reversed-Z depth representation |
| Z-fighting | Insufficient depth precision at convergence distance | Adjust near plane; use logarithmic depth distribution |
| Ghost images | Scissor test disabled; blending between eye views | Verify scissor enabled during each eye render; check compositing blend modes |

*Table 4: Common Stereo Rendering Artifacts and Solutions*

#### 8.2.1 Vertical Parallax and Alignment Issues

Vertical misalignment exceeding **~0.5°** causes fusion failure. Detection requires careful measurement of corresponding feature positions in both eye views. Resolution involves verifying dimension calculations and ensuring **symmetric treatment of both eyes** .

#### 8.2.2 Edge Distortion from Viewport Splitting

Visible seams or discontinuities at the screen center where viewports abut can result from: filtering artifacts during texture sampling, incorrect viewport boundary specification, or post-processing effects that sample across the split. Solutions include: **ensuring exact pixel alignment of viewport edges**, using nearest-neighbor sampling for composition if appropriate, and disabling cross-viewport sampling in post-processing .

#### 8.2.3 Depth Buffer Precision Problems

Effective depth precision is reduced when rendering to half-width viewports if the depth buffer resolution scales proportionally. **Maintaining full-resolution depth buffers** or using reversed-Z depth representation can mitigate these issues. Additionally, stereo rendering can make depth precision limitations more visible, as surfaces that appear at similar depths in one eye may show significant separation in the other .

### 8.3 Compatibility Testing Matrix

Comprehensive validation requires systematic testing across Minecraft's rendering variations and common mod combinations .

#### 8.3.1 Vanilla Minecraft Rendering Validation

Test scenarios should include: all graphics modes (Fast, Fancy, Fabulous); various render distances; different world types (Overworld, Nether, End with distinct fog and lighting); and all weather/time conditions. Each scenario should be verified for: **correct stereo separation, absence of visual artifacts, and comfortable viewing experience** .

#### 8.3.2 Popular Shader Pack Compatibility Assessment

The Stereopsis mod notes specific incompatibility with some shader-dependent mods and provides configuration workarounds . Testing should include: OptiFine shaders (if applicable), Iris/ShaderMod combinations, and popular shader packs (SEUS, Continuum, BSL, etc.). For each, document: visual quality level, performance impact, and any required configuration adjustments .

#### 8.3.3 Other Mod Interaction Testing

Particular attention should be paid to: **HUD/GUI modifying mods** (may need stereo-aware positioning), **camera modification mods** (potential conflicts with eye offset), and **performance optimization mods** (may interfere with dual rendering). The Stereopsis mod shows extensive mod ecosystems on NeoForge 1.21, with examples like Create, JEI, and various utility mods, indicating the importance of broad compatibility testing .

## 9. Reference Implementation: Stereopsis Mod Analysis

### 9.1 Architecture Overview from Open-Source Reference

The **Stereopsis mod** by **aMelonRind** provides the most directly relevant prior implementation for X-Real 2 stereo 3D targeting. While the mod has not been updated past Minecraft 1.21.3 and explicitly requires a rewrite for newer versions , its design decisions and documented behavior provide valuable guidance for new implementations.

#### 9.1.1 MIT License Permits Code Study and Adaptation

The Stereopsis mod's **MIT license** permits study and adaptation of its approach, though direct code copying should respect license requirements including attribution. The 1.21.3 support baseline indicates that its core architecture was compatible with significant NeoForge rendering changes, suggesting that adaptation to 1.21.x is feasible with focused effort. The Fabric/NeoForge cross-compatibility noted in the mod's description indicates use of abstraction layers or conditional compilation that may be relevant for modern multi-loader development .

#### 9.1.2 1.21.3 Support as Baseline for 1.21 Implementation

The mod's version support extends to Minecraft 1.21.3, with explicit documentation noting that **"This mod is too difficult to update past 1.21.3. It needs a rewrite and will not happen soon"** . This limitation provides important context for developers targeting newer versions: the rendering changes introduced in 1.21.4 and beyond may require substantial architectural revision rather than incremental adaptation. The 1.21.3 baseline nonetheless provides a solid foundation for 1.21 implementation, with the core patterns remaining applicable even if specific API details have evolved .

#### 9.1.3 Fabric/NeoForge Cross-Compatibility Considerations

The Stereopsis mod's Fabric/NeoForge cross-compatibility demonstrates that stereo rendering concepts translate across mod loader boundaries. While this document focuses on NeoForge specifically, developers familiar with Fabric can reference the Stereopsis implementation directly, while NeoForge developers can adapt the core logic with appropriate API substitutions. The core rendering logic—OpenGL operations, event interception, matrix manipulation—is loader-agnostic, but event class names, registration patterns, and configuration handling require adaptation .

### 9.2 Key Implementation Patterns

Analysis of available documentation reveals several key implementation patterns employed by Stereopsis .

#### 9.2.1 Dual Render Pass Execution per Frame

The fundamental pattern: **complete scene rendering twice per frame**, once for each eye, with appropriate camera offsets. This includes: world geometry through `LevelRenderer`, entities through `EntityRenderDispatcher`, block entities through `BlockEntityRenderDispatcher`, particles through `ParticleEngine`, and weather/sky effects. The "HUD splitting" option added in 2.0.0  indicates sophisticated handling of GUI elements in stereo context .

#### 9.2.2 HUD and GUI Element Handling in Stereo Context

Critical challenge: GUI elements designed for single-view rendering must be adapted or excluded. The Stereopsis developer explicitly declined full GUI modification: **"I'm not going to implement it, because modifying container screens sounds like hell"** . Practical approaches include: rendering GUI at screen center (zero disparity) for comfortable viewing, duplicating them with appropriate parallax, or temporarily disabling stereo for GUI interaction .

#### 9.2.3 Configuration-Driven View Mode Switching

Runtime mode switching without restart enables user optimization for different use cases. The Stereopsis implementation uses ModMenu for configuration access, with **"View Mode option in the config"** controlling AR Mode activation . This pattern enables multiple output formats: cross-eye (for unaided viewing), side-by-side (for glasses), and potentially anaglyph or other formats .

### 9.3 Known Limitations and Workarounds

The Stereopsis mod's documentation provides explicit guidance on limitations that new implementations should address or document .

#### 9.3.1 Shader Compatibility Challenges Documented

Explicit acknowledgment that **"it renders two times in the same frame"** causes shader pack issues, with specific workaround configuration available . New implementations should consider shader detection and graceful degradation, potentially implementing "shader-safe mode" with reduced visual effects but guaranteed stereo correctness .

#### 9.3.2 Human Vision Knowledge Gaps in Implementation

Developer disclaimer: **"the dev doesn't have one [X-Real 2 glasses] and doesn't have deep knowledges about human sight, so it might look weird in some cases"** . This suggests opportunity for community contribution or collaboration with vision science expertise to optimize parameters for comfort and effectiveness .

#### 9.3.3 Rewrite Requirements for Post-1.21.3 Versions

The architectural limitations preventing straightforward updates indicate that fundamental design decisions (rendering hook selection, state management approach) should be carefully evaluated for long-term maintainability. Understanding the changes between 1.21.3 and 1.21.x—likely related to the `RenderSystem` and GPU device abstractions noted in migration documentation —is essential for new implementations targeting 1.21.x .

## 10. Deployment and Distribution

### 10.1 Build Configuration

NeoForge 1.21 mod development requires specific build tooling configuration, with two primary Gradle plugin options: **NeoGradle** and **ModDevGradle**. ModDevGradle is "aimed for simpler and more streamlined buildscripts" while NeoGradle "supports having multiple NeoForge/Minecraft versions in the same project" . For a focused stereo 3D mod, ModDevGradle's simplicity is likely preferable .

#### 10.1.1 `build.gradle` Dependencies for NeoForge 1.21

```groovy
plugins {
    id 'net.neoforged.gradle' version '[6.0.18,6.2)'
}

dependencies {
    implementation 'net.neoforged:neoforge:21.0.0-beta'
}
```

The `mods.toml` metadata in `src/main/resources/META-INF/` specifies mod identification, dependencies, and loading information, with the `modLoader` field set to "javafml" and `loaderVersion` matching the NeoForge version range .

#### 10.1.2 `mods.toml` Metadata Specification

```toml
modLoader="javafml"
loaderVersion="[4,)"
license="MIT"

[[mods]]
modId="xrealstereo"
version="${file.jarVersion}"
displayName="Stereo 3D for X-Real 2"
description="Side-by-side stereo rendering for AR glasses"
authors="Your Name"
```

#### 10.1.3 Version Range Compatibility Declaration

Explicit version ranges prevent loading on incompatible Minecraft versions. Conservative version ranging is warranted given the significant rendering changes in 1.21.x subversions :

```toml
[[dependencies.xrealstereo]]
modId="minecraft"
mandatory=true
versionRange="[1.21,1.21.1)"
ordering="NONE"
side="CLIENT"
```

### 10.2 Distribution Platforms

#### 10.2.1 Modrinth Publication Guidelines

Modrinth has gained significant adoption for its cleaner interface and developer-friendly policies. The Stereopsis mod's presence on Modrinth (3,583 downloads, 33 followers) demonstrates audience availability . Publication should include: proper metadata completion, clear description of functionality and requirements, version compatibility labeling, and appropriate categorization .

#### 10.2.2 CurseForge Compatibility Requirements

CurseForge maintains larger user reach particularly for casual players. Compatibility requirements include: NeoForge loader recognition, dependency declaration, and adherence to content policies. The platform's review process may require additional preparation time compared to Modrinth .

#### 10.2.3 GitHub Releases for Source Distribution

MIT or similar permissive licensing enables community contribution and fork development. GitHub Releases with attached sources JAR satisfies license requirements while enabling version-controlled distribution. Automated release workflows using GitHub Actions can build, test, and publish artifacts on tag creation .

### 10.3 Documentation and User Support

#### 10.3.1 Installation Instructions for X-Real 2 Users

Critical information for users: connect glasses before launching Minecraft, verify 3840×1080 resolution detection, bind activation hotkey in controls menu, and enable AR Mode in mod configuration. Screenshots of configuration screens with annotated explanations reduce user confusion .

#### 10.3.2 Troubleshooting Common Configuration Issues

| Symptom | Likely Cause | Resolution |
|---------|-----------|------------|
| Double vision, cannot fuse | Excessive eye separation | Reduce in config |
| Flat appearance, no depth | Mode not activated | Check hotkey, verify AR Mode |
| GUI elements misaligned | HUD splitting disabled | Enable in config |
| Performance degradation | Shader conflict | Disable shaders or use config fix |
| Black screen on enable | FBO creation failure | Check GPU memory; reduce resolution scale |

*Table 5: Common Configuration Issues and Resolutions*

#### 10.3.3 Community Feedback Integration for Iterative Improvement

The Stereopsis mod's development history demonstrates value of user feedback, particularly for hardware-specific optimizations where developer testing is limited . Issue tracking, Discord communities, or forum threads provide channels for X-Real 2 users to report experience and suggest improvements. This feedback loop is essential for achieving quality comparable to native stereo applications .
