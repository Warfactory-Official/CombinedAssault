package com.wf.combinedassault.weapon;

import com.wf.wflib.round.api.KineticPreset;
import com.wf.wflib.round.api.RoundArc;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public class CcipUtil {

    private static final int MAX_TICKS = 1200;

    /**
     * Impact point of a WFLib kinetic round in air: motor along {@code nose} for its burn. Null: none within
     * {@link #MAX_TICKS} / fuse life.
     */
    @Nullable
    public static Vec3 computeCcip(Level level, Entity entity, Vec3 startPos, Vec3 startVelocity, Vec3 nose,
                                   KineticPreset round) {
        int maxTicks = Math.min(MAX_TICKS, round.lifeTicks() + 1);
        double[] arc = new double[3 * maxTicks];
        int n = RoundArc.predictPowered(round, startPos.x, startPos.y, startPos.z, startVelocity.x, startVelocity.y,
                startVelocity.z, nose.x, nose.y, nose.z, maxTicks + 1, Double.NEGATIVE_INFINITY, maxTicks, arc);
        Vec3 position = startPos;
        for (int k = 0; k < n; k++) {
            Vec3 next = new Vec3(arc[3 * k], arc[3 * k + 1], arc[3 * k + 2]);
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
