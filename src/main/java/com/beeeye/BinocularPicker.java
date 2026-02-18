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
 * Convergence ray picking — uses the actual camera direction (with head
 * tracking applied) to find convergence targets.
 *
 * Three-tier picking:
 * 1. Center ray from camera position along look direction
 * 2. Binocular rays from each eye position along look direction
 * 3. Static fallback from config
 *
 * All rays cast forward at fixed max range to avoid feedback loops
 * (previous version aimed at current convergence point, which caused
 * oscillation when convergence jumped between near/far).
 */
public class BinocularPicker {

    /** Max pick distance — matches Minecraft's interaction range. */
    private static final float MAX_RANGE = 128.0f;

    /**
     * Pick convergence distance using head-tracked camera direction.
     * Called after camera.setup() so forward vector includes head tracking.
     */
    public static float pick(Camera camera, Level level, Player player) {
        if (player == null || level == null) return Convergence.getStatic();

        Vec3 eyePos = player.getEyePosition(1.0f);
        Vec3 forward = new Vec3(camera.forwardVector());
        Vec3 endPoint = eyePos.add(forward.scale(MAX_RANGE));

        // Tier 1: center ray — most common case
        float centerDist = raycast(eyePos, endPoint, level, player);
        if (centerDist >= 0) return centerDist;

        // Tier 2: binocular rays — catches objects visible to one eye
        Vec3 left = new Vec3(camera.leftVector());
        float halfIPD = StereoState.getIPD() / 2.0f;
        Vec3 leftEyePos = eyePos.add(left.scale(halfIPD));
        Vec3 rightEyePos = eyePos.subtract(left.scale(halfIPD));
        Vec3 leftEnd = leftEyePos.add(forward.scale(MAX_RANGE));
        Vec3 rightEnd = rightEyePos.add(forward.scale(MAX_RANGE));

        float bestDist = Float.MAX_VALUE;
        float d = raycast(leftEyePos, leftEnd, level, player);
        if (d >= 0 && d < bestDist) bestDist = d;
        d = raycast(rightEyePos, rightEnd, level, player);
        if (d >= 0 && d < bestDist) bestDist = d;

        if (bestDist < Float.MAX_VALUE) return bestDist;

        // Tier 3: nothing hit — static fallback
        return Convergence.getStatic();
    }

    /**
     * Cast a ray for blocks and entities. Returns distance from player eye
     * position, or -1 if nothing hit.
     */
    private static float raycast(
        Vec3 from,
        Vec3 to,
        Level level,
        Player player
    ) {
        Vec3 eyePos = player.getEyePosition(1.0f);
        double bestDistSq = Double.MAX_VALUE;

        HitResult blockHit = level.clip(
            new ClipContext(
                from,
                to,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
            )
        );
        if (blockHit.getType() != HitResult.Type.MISS) {
            bestDistSq = blockHit.getLocation().distanceToSqr(eyePos);
        }

        double rangeSq = from.distanceToSqr(to);
        AABB searchBox = new AABB(from, to).inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
            player,
            from,
            to,
            searchBox,
            EntitySelector.CAN_BE_PICKED,
            rangeSq
        );
        if (entityHit != null) {
            double d = entityHit.getLocation().distanceToSqr(eyePos);
            if (d < bestDistSq) bestDistSq = d;
        }

        return bestDistSq < Double.MAX_VALUE
            ? (float) Math.sqrt(bestDistSq)
            : -1f;
    }
}
