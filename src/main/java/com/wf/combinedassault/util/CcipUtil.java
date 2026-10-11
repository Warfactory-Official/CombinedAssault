package com.wf.combinedassault.util;

import com.wf.wflib.kinetic.KineticPreset;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public class CcipUtil {

    private static final int MAX_TICKS = 1200;

    /**
     * Impact point of a WFLib kinetic round: motor along {@code nose} for its burn, quadratic + linear drag,
     * the preset's gravity. Null: none within {@link #MAX_TICKS} / fuse life.
     */
    @Nullable
    public static Vec3 computeCcip(Level level, Entity entity, Vec3 startPos, Vec3 startVelocity, Vec3 nose,
                                   KineticPreset round) {
        Vec3 position = startPos;
        Vec3 velocity = startVelocity;
        Vec3 thrust = nose.normalize().scale(round.motorAccel());
        int maxTicks = Math.min(MAX_TICKS, round.lifeTicks() + 1);
        for (int tick = 1; tick <= maxTicks; tick++) {
            if (tick <= round.burnTicks()) {
                velocity = velocity.add(thrust);
            }
            velocity = velocity.scale(1 - round.quadraticDrag() * velocity.length())
                    .scale(1 - round.drag())
                    .subtract(0, round.gravity(), 0);
            Vec3 next = position.add(velocity);
            HitResult result = level.clip(new ClipContext(position, next, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, entity));
            if (result.getType() != HitResult.Type.MISS) {
                return result.getLocation();
            }
            position = next;
        }
        return null;
    }

}
