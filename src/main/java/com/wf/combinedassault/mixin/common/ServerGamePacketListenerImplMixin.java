package com.wf.combinedassault.mixin.common;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import com.wf.combinedassault.vehicle.parenting.DeckAttachment;
import com.wf.combinedassault.vehicle.parenting.VehicleParenting;

/**
 * Lets a player standing on a vehicle move as fast as the vehicle is carrying it.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerImplMixin {

    @Shadow
    public ServerPlayer player;

    @ModifyConstant(
            method = "handleMovePlayer",
            constant = @Constant(floatValue = 100.0F))
    private float combinedassault$allowCarriedMotion(float allowance) {
        DeckAttachment attachment = VehicleParenting.attachmentOf(this.player);
        if (attachment == null || !attachment.gripped()) {
            return allowance;
        }
        // allowance is a squared distance; carry contribution added squared too.
        return allowance + (float) attachment.lastCarrySpeedSqr();
    }

    /** Server's deck is 1-2 ticks ahead of client's; widen move tolerance by that lead to avoid snapback. */
    @ModifyConstant(
            method = "handleMovePlayer",
            constant = @Constant(doubleValue = 0.0625))
    private double combinedassault$allowDeckLead(double tolerance) {
        DeckAttachment attachment = VehicleParenting.attachmentOf(this.player);
        if (attachment == null || !attachment.gripped()) {
            return tolerance;
        }
        double slack = Math.sqrt(tolerance) + 2 * Math.sqrt(attachment.lastCarrySpeedSqr());
        return slack * slack;
    }

}
