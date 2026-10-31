package com.wf.combinedassault.mixin.vehicle;

import com.wf.combinedassault.vehicle.AltitudeView;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class ChunkMapViewMixin {

    @Inject(method = "getPlayerViewDistance", at = @At("RETURN"), cancellable = true)
    private void combinedassault$altitudeView(ServerPlayer player, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(AltitudeView.view(player, cir.getReturnValue()));
    }
}
