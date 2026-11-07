package com.wf.combinedassault.mixin.vehicle;

import com.wf.combinedassault.vehicle.entity.KnockBackModifier;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

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