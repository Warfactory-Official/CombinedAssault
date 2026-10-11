package com.wf.combinedassault.mixin.create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

@Mixin(ContraptionCollider.class)
public class ContraptionColliderMixin {

    @WrapOperation(
            method = "collideEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"
            )
    )
    private static AABB wrapGetBoundingBox(Entity instance, Operation<AABB> original) {
        if (instance instanceof AbstractVehicle) {
            return AABB.ofSize(instance.position().add(0, 0.5, 0), 1, 1, 1);
        }
        return original.call(instance);
    }

}
