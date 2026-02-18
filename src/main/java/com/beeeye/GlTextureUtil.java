package com.beeeye;

import com.mojang.blaze3d.textures.GpuTexture;
import java.lang.reflect.Method;

/**
 * Resolves OpenGL texture IDs from Minecraft's {@link GpuTexture} objects.
 * NeoForge wraps textures in ValidationGpuTexture — unwrapped via reflection
 * on first call, then cached as plain static fields. Zero hash lookups,
 * zero ConcurrentHashMap overhead on the hot compositing path.
 */
public class GlTextureUtil {

    // Probed once on first textureId() call, then frozen.
    private static volatile boolean probed = false;
    private static Method unwrapMethod = null; // ValidationGpuTexture.getRealTexture()
    private static Class<?> validationClass = null;
    private static Method glIdMethod = null; // <real impl>.glId()

    private static boolean errorLogged = false;

    /** Returns the GL texture ID, or -1 on failure. */
    public static int textureId(GpuTexture texture) {
        if (texture == null) return -1;
        try {
            if (!probed) probe(texture);

            GpuTexture real = (unwrapMethod != null &&
                validationClass != null &&
                validationClass.isInstance(texture))
                ? (GpuTexture) unwrapMethod.invoke(texture)
                : texture;

            if (glIdMethod == null) {
                // glIdMethod resolved against the concrete real class on first successful unwrap
                glIdMethod = real.getClass().getMethod("glId");
            }
            return (int) glIdMethod.invoke(real);
        } catch (Exception e) {
            logOnce("Failed to get texture ID via reflection", e);
            return -1;
        }
    }

    /**
     * Probe the texture class hierarchy once. After this call, unwrapMethod
     * and glIdMethod are set (or null if unavailable) and probed=true prevents
     * any further reflection.
     */
    private static synchronized void probe(GpuTexture texture) {
        if (probed) return; // double-checked inside sync
        try {
            Class<?> cls = texture.getClass();
            GpuTexture real = texture;

            if (cls.getName().contains("Validation")) {
                validationClass = cls;
                try {
                    unwrapMethod = cls.getMethod("getRealTexture");
                    real = (GpuTexture) unwrapMethod.invoke(texture);
                } catch (NoSuchMethodException ignored) {}
            }

            try {
                glIdMethod = real.getClass().getMethod("glId");
            } catch (NoSuchMethodException e) {
                logOnce("No glId() on {}", real.getClass().getName());
            }
        } catch (Exception e) {
            logOnce("GlTextureUtil probe failed", e);
        } finally {
            probed = true;
        }
    }

    private static void logOnce(String msg, Object... args) {
        if (!errorLogged) {
            errorLogged = true;
            Beeeye.LOGGER.warn(msg, args);
        }
    }
}
