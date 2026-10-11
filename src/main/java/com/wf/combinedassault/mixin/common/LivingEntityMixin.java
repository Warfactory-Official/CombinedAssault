package com.wf.combinedassault.mixin.common;


import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.wf.combinedassault.api.entity.KnockBackModifier;
import com.wf.combinedassault.api.entity.VehicleParented;
import com.wf.combinedassault.vehicle.parenting.DeckAttachment;
import com.wf.combinedassault.vehicle.parenting.DeckRejoin;
import com.wf.combinedassault.vehicle.parenting.VehicleParenting;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin implements KnockBackModifier, VehicleParented {

    @Shadow
    protected int lerpSteps;

    @Shadow
    protected abstract void updateWalkAnimation(float partialTick);

    /**
     * Carried rider: force lerpSteps=1. Vanilla's 1/3-per-tick lerp vs hull's 1-tick-stale pose
     * renders several blocks astern on a fast carrier; render still interpolates, no smoothness cost.
     */
    @Inject(method = "lerpTo", at = @At("TAIL"))
    private void combinedassault$snapCarriedRider(double x, double y, double z, float yRot, float xRot,
                                               int steps, CallbackInfo ci) {
        if (this.lerpSteps <= 1) {
            return;
        }
        DeckAttachment attachment = VehicleParenting.attachmentOf((Entity) (Object) this);
        // Only a gripped, moving carry; parked hull keeps vanilla lerp.
        if (attachment != null && attachment.gripped() && attachment.lastCarrySpeedSqr() > 0) {
            this.lerpSteps = 1;
        }
    }

    /** World-space walk anim reads a moving deck as walking; measure step in hull frame instead. */
    @Inject(method = "calculateEntityAnimation", at = @At("HEAD"), cancellable = true)
    private void combinedassault$deckWalkAnimation(boolean includeHeight, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        DeckAttachment attachment = VehicleParenting.attachmentOf(self);
        if (attachment == null || !attachment.gripped()) {
            return;
        }
        updateWalkAnimation(VehicleParenting.walkStep(self, attachment, includeHeight));
        ci.cancel();
    }

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void combinedassault$holdForDeck(Vec3 travelVector, CallbackInfo ci) {
        if (DeckRejoin.hold((LivingEntity) (Object) this)) {
            ci.cancel();
        }
    }

    @Unique
    public double combinedassault$knockbackStrength;

    @Unique
    public void combinedassault$resetKnockBackStrength() {
        this.combinedassault$knockbackStrength = -1.0F;
    }

    @Unique
    public double combinedassault$getKnockBackStrength() {
        return this.combinedassault$knockbackStrength;
    }

    @Unique
    public void combinedassault$setKnockBackStrength(double strength) {
        this.combinedassault$knockbackStrength = strength;
    }
}