package com.wf.combinedassault.api.entity;

import net.minecraft.world.entity.LivingEntity;

/** Ported from TACZ. */
public interface KnockBackModifier {
    static KnockBackModifier fromLivingEntity(LivingEntity entity) {
        return (KnockBackModifier)entity;
    }

    void combinedassault$resetKnockBackStrength();

    double combinedassault$getKnockBackStrength();

    void combinedassault$setKnockBackStrength(double var1);
}