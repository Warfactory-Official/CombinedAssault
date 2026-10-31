package com.wf.combinedassault.mixin.vehicle;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import com.wf.combinedassault.vehicle.entity.KnockBackModifier;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin implements KnockBackModifier {

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