package com.wf.combinedassault.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.norwood.ahf.client.body.AhfBody;
import com.wf.combinedassault.vehicle.hands.client.RiderHands;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidArmorLayer.class)
abstract class HumanoidArmorLayerMixin {
    private static final String RENDER =
            "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V";

    @Inject(method = RENDER, at = @At("HEAD"))
    private void combinedassault$riderHands(PoseStack pose, MultiBufferSource buffers, int light, LivingEntity entity,
                                           float a, float b, float c, float d, float e, float f, CallbackInfo ci) {
        RiderHands.armour(entity);
    }

    @Inject(method = "setPartVisibility", at = @At("TAIL"))
    private void combinedassault$ownBody(HumanoidModel<?> model, EquipmentSlot slot, CallbackInfo ci) {
        if (RiderHands.ownBody()) {
            RiderHands.hideHeadAndArms(model);
        }
    }

    @Inject(method = RENDER, at = @At("RETURN"))
    private void combinedassault$clear(PoseStack pose, MultiBufferSource buffers, int light, LivingEntity entity,
                                      float a, float b, float c, float d, float e, float f, CallbackInfo ci) {
        AhfBody.clear();
    }
}
