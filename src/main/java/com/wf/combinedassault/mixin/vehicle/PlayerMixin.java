package com.wf.combinedassault.mixin.vehicle;

import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

@Mixin(Player.class)
public class PlayerMixin {

    @Inject(method = "wantsToStopRiding",
            at = @At("HEAD"),
            cancellable = true)
    public void wantsToStopRiding(CallbackInfoReturnable<Boolean> cir) {
        if (((Player) (Object) this).getVehicle() instanceof AbstractVehicle) {
            cir.setReturnValue(false);
        }
    }
}
