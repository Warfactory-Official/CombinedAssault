package com.wf.combinedassault.mixin.common;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.wf.combinedassault.api.entity.VehicleParented;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.parenting.DeckAttachment;
import com.wf.combinedassault.vehicle.parenting.DeckRejoin;
import com.wf.combinedassault.vehicle.parenting.VehicleParenting;

@Mixin(Entity.class)
public abstract class EntityMixin implements VehicleParented {

    @Shadow
    public abstract Vec3 position() ;

    @Shadow
    public abstract boolean isPassengerOfSameVehicle(Entity pEntity);

    @Shadow
    public abstract double getX();

    @Shadow
    public abstract double getZ();

    @Shadow
    public abstract boolean isVehicle();

    @Unique
    @Nullable
    private DeckAttachment combinedassault$deckAttachment;

    @Unique
    @Nullable
    private DeckRejoin combinedassault$deckRejoin;

    @Override
    @Nullable
    public DeckRejoin combinedassault$deckRejoin() {
        return this.combinedassault$deckRejoin;
    }

    @Override
    public void combinedassault$setDeckRejoin(@Nullable DeckRejoin rejoin) {
        this.combinedassault$deckRejoin = rejoin;
    }

    @Inject(method = "saveWithoutId", at = @At("RETURN"))
    private void combinedassault$saveDeck(CompoundTag compound, CallbackInfoReturnable<CompoundTag> cir) {
        DeckRejoin.save((Entity) (Object) this, cir.getReturnValue());
    }

    @Inject(method = "load", at = @At("TAIL"))
    private void combinedassault$loadDeck(CompoundTag compound, CallbackInfo ci) {
        DeckRejoin.load((Entity) (Object) this, compound);
    }

    @Override
    @Nullable
    public DeckAttachment combinedassault$deckAttachment() {
        return this.combinedassault$deckAttachment;
    }

    @Override
    public void combinedassault$setDeckAttachment(@Nullable DeckAttachment attachment) {
        this.combinedassault$deckAttachment = attachment;
    }

    @Inject(
            method = "push(Lnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"),
            cancellable = true)
    public void push0(Entity pEntity, CallbackInfo ci) {
        // Mixin typing: keep the (Object) casts.
        if (!((Object) this instanceof AbstractVehicle) && pEntity instanceof AbstractVehicle vehicle) {
            if (this.isPassengerOfSameVehicle(pEntity)) {
                ci.cancel();
                return;
            }
            vehicle.support((Entity) (Object) this);
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void combinedassault$tickDeckCarry(CallbackInfo ci) {
        if (this.combinedassault$deckRejoin != null) {
            DeckRejoin.tick((Entity) (Object) this, this.combinedassault$deckRejoin);
        }
        if (this.combinedassault$deckAttachment == null) {
            return;
        }
        VehicleParenting.onEntityTicked((Entity) (Object) this);
    }

    /** Clips vanilla's move result against the deck, in the vehicle's frame. */
    @Inject(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
            at = @At("RETURN"),
            cancellable = true)
    private void combinedassault$collideWithDeck(Vec3 wanted, CallbackInfoReturnable<Vec3> cir) {
        if (this.combinedassault$deckAttachment == null) {
            return;
        }
        Vec3 allowed = cir.getReturnValue();
        Vec3 clipped = VehicleParenting.clipMovement((Entity) (Object) this, allowed);
        if (clipped != allowed) {
            cir.setReturnValue(clipped);
        }
    }

}
