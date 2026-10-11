package com.wf.combinedassault.compat.wflib;

import com.wf.wflib.round.RoundImpactEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.particle.BulletHoleOption;

/** Chips and a bullet hole where a WFLib round stops on a block, sized by calibre. */
@EventBusSubscriber(modid = CombinedAssault.MOD_ID)
public final class RoundMarks {

    private static final double RANGE = 128.0;

    private RoundMarks() {
    }

    @SubscribeEvent
    public static void onImpact(RoundImpactEvent event) {
        ServerLevel level = event.level();
        BlockHitResult hit = event.hit();
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        Vec3 normal = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
        Vec3 chips = hit.getLocation().add(normal.scale(0.3));
        Vec3 hole = hit.getLocation().add(normal.scale(0.01));
        BlockParticleOption crack = new BlockParticleOption(ParticleTypes.BLOCK, state);
        BulletHoleOption mark = new BulletHoleOption(hit.getDirection(), pos, 1, 0, 0, event.preset().caliberMm());
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(chips) < RANGE * RANGE) {
                level.sendParticles(player, crack, true, chips.x, chips.y, chips.z, 5, 0, 0, 0, 0.1);
                level.sendParticles(player, mark, true, hole.x, hole.y, hole.z, 1, 0, 0, 0, 0);
            }
        }
    }
}
