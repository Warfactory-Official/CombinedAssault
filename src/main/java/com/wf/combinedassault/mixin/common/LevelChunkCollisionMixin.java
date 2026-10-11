package com.wf.combinedassault.mixin.common;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.wf.combinedassault.vehicle.collision.ChunkCollisionCache;

/** Hooked here, not NeoForge block events: those miss piston/explosion/fluid/worldgen writes. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkCollisionMixin {

    @Shadow
    @Final
    Level level;

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void combinedassault$invalidateCollisionSnapshot(BlockPos pos, BlockState state, boolean isMoving,
                                                  CallbackInfoReturnable<BlockState> cir) {
        ChunkCollisionCache.invalidate(this.level, pos);
    }

}
