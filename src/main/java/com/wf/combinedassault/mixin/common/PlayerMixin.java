package com.wf.combinedassault.mixin.common;

import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.parenting.DeckAttachment;
import com.wf.combinedassault.vehicle.parenting.DeckCollision;
import com.wf.combinedassault.vehicle.parenting.VehicleParenting;

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

    /** Deck counts as ground: suppresses vanilla's sneak-edge fall check over it. */
    @Inject(method = "canFallAtLeast", at = @At("RETURN"), cancellable = true)
    private void combinedassault$deckIsGround(double dx, double dz, float depth,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        Player self = (Player) (Object) this;
        DeckAttachment attachment = VehicleParenting.attachmentOf(self);
        if (!VehicleParenting.isUsable(attachment, self)) {
            return;
        }
        if (DeckCollision.supportsOffset(self, attachment.vehicle().deckSnapshot(), attachment,
                dx, dz, depth)) {
            cir.setReturnValue(false);
        }
    }

}
