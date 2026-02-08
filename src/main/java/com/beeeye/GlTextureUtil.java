package com.beeeye;

import com.mojang.blaze3d.textures.GpuTexture;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves OpenGL texture IDs from Minecraft's {@link GpuTexture} objects.
 * NeoForge wraps textures in ValidationGpuTexture — this utility unwraps
 * them via reflection and calls glId(). Results are cached per class.
 */
public class GlTextureUtil {

    private static final ConcurrentHashMap<Class<?>, Method> glIdCache = new ConcurrentHashMap<>();
    private static boolean unwrapProbed;
    private static Method unwrapMethod;
    private static boolean errorLogged;

    /** Returns the GL texture ID, or -1 on failure. */
    public static int textureId(GpuTexture texture) {
        if (texture == null) return -1;
        try {
            GpuTexture real = unwrap(texture);
            Method glId = glIdCache.computeIfAbsent(real.getClass(), cls -> {
                try { return cls.getMethod("glId"); }
                catch (NoSuchMethodException e) { return null; }
            });
            if (glId == null) {
                logOnce("No glId method on {}", real.getClass().getName());
                return -1;
            }
            return (int) glId.invoke(real);
        } catch (Exception e) {
            logOnce("Failed to get texture ID via reflection", e);
            return -1;
        }
    }

    private static GpuTexture unwrap(GpuTexture texture) throws Exception {
        if (!unwrapProbed) {
            unwrapProbed = true;
            if (texture.getClass().getName().contains("Validation")) {
                try { unwrapMethod = texture.getClass().getMethod("getRealTexture"); }
                catch (NoSuchMethodException ignored) {}
            }
        }
        if (unwrapMethod != null && texture.getClass().getName().contains("Validation")) {
            return (GpuTexture) unwrapMethod.invoke(texture);
        }
        return texture;
    }

    private static void logOnce(String msg, Object... args) {
        if (!errorLogged) {
            errorLogged = true;
            Beeeye.LOGGER.warn(msg, args);
        }
    }
}
