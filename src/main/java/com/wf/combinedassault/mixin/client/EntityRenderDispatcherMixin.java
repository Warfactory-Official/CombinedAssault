package com.wf.combinedassault.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

/**
 * Vehicle hitbox draw only; collision AABB untouched. Vanilla's axis-aligned box is oversized
 * against the oriented boxes {@code VehicleRender} already draws (blue/green/yellow).
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {

    @Inject(method = "renderHitbox", at = @At("HEAD"), cancellable = true)
    private static void combinedassault$skipVehicleHitbox(PoseStack poseStack, VertexConsumer buffer, Entity entity,
                                               float partialTick, float red, float green, float blue,
                                               CallbackInfo ci) {
        if (entity instanceof AbstractVehicle) {
            ci.cancel();
        }
    }
}
