package com.beeeye;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Binocular ray picking for dynamic convergence.
 *
 * When the center crosshair misses (e.g. pointing at sky), casts two rays —
 * one from each eye position — toward the current convergence point.
 * Each ray checks blocks and entities. The closest hit determines the
 * new convergence target, keeping stereo depth natural even when the
 * crosshair points at empty space.
 */
public class BinocularPicker {

    /**
     * Cast eye rays and return the distance to the nearest hit.
     * Falls back to static convergence if both rays miss.
     */
    public static float pick(
        Vec3 eyePos,
        Camera camera,
        Level level,
        Player player
    ) {
        if (player == null || level == null) return Convergence.getStatic();

        Vec3 forward = new Vec3(camera.forwardVector());
        Vec3 left = new Vec3(camera.leftVector());
        float halfIPD = StereoState.getIPD() / 2.0f;
        float convDist = Convergence.get();
        Vec3 convergencePoint = eyePos.add(forward.scale(convDist));

        Vec3 leftEyePos = eyePos.add(left.scale(halfIPD));
        Vec3 rightEyePos = eyePos.subtract(left.scale(halfIPD));

        double bestDistSq = Double.MAX_VALUE;

        for (Vec3 eyeOrigin : new Vec3[] { leftEyePos, rightEyePos }) {
            HitResult blockHit = level.clip(
                new ClipContext(
                    eyeOrigin,
                    convergencePoint,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player
                )
            );
            if (blockHit.getType() != HitResult.Type.MISS) {
                double d = blockHit.getLocation().distanceToSqr(eyePos);
                if (d < bestDistSq) bestDistSq = d;
            }

            AABB searchBox = new AABB(eyeOrigin, convergencePoint).inflate(1.0);
            EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                player,
                eyeOrigin,
                convergencePoint,
                searchBox,
                EntitySelector.CAN_BE_PICKED,
                convDist * convDist + 4.0
            );
            if (entityHit != null) {
                double d = entityHit.getLocation().distanceToSqr(eyePos);
                if (d < bestDistSq) bestDistSq = d;
            }
        }

        return bestDistSq < Double.MAX_VALUE
            ? (float) Math.sqrt(bestDistSq)
            : Convergence.getStatic();
    }
}
