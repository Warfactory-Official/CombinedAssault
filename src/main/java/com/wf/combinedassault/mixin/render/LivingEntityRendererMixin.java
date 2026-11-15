package com.wf.combinedassault.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.norwood.ahf.client.body.AhfBody;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.hands.client.RiderHands;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin<T extends LivingEntity> {

    @Inject(method = "setupRotations", at = @At("HEAD"), cancellable = true)
    public void setupRotations(T entity, PoseStack pPoseStack, float pAgeInTicks, float pRotationYaw, float pPartialTicks, float p_320045_, CallbackInfo ci) {
        if (entity.getRootVehicle() != entity && entity.getRootVehicle() instanceof AbstractVehicle vehicle
                && vehicle.getDetachedBodyAnchor(entity) == null) {
            // yBodyRot per tick (VehicleEvents) => traverse steps at 20 Hz; drawn seat yaw instead.
            var seat = vehicle.getOwnOperatorUnit(entity);
            float bodyYaw = seat != null ? seat.getSeatRot(pPartialTicks) : pRotationYaw;
            float yRotDiff = Mth.wrapDegrees(180 + bodyYaw - Mth.lerp(pPartialTicks, vehicle.yRotO, vehicle.getYRot()));
            Quaternionf q = new Quaternionf();
            q.rotateY((float) Math.toRadians(Mth.lerp(pPartialTicks, -vehicle.yRotO, -vehicle.getYRot())))
                    .rotateX((float) Math.toRadians(Mth.lerp(pPartialTicks, vehicle.xRotO, vehicle.getXRot())))
                    .rotateZ((float) Math.toRadians(Mth.lerp(pPartialTicks, vehicle.zRotO, vehicle.getZRot())));
            q.rotateY((float) Math.toRadians(-yRotDiff));
            Vector3f rot = new Vector3f();
            q.getEulerAnglesYXZ(rot);
            pPoseStack.mulPose(Axis.YP.rotation(rot.y));
            pPoseStack.mulPose(Axis.XP.rotation(rot.x));
            pPoseStack.mulPose(Axis.ZP.rotation(rot.z));
            ci.cancel();
        }
    }

    private static final String RENDER = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V";

    /** Rider hand contacts around the model draw. */
    @WrapOperation(method = RENDER, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
    private void combinedassault$riderHands(EntityModel<?> model, PoseStack pose, VertexConsumer vc, int light, int overlay,
                                            int color, Operation<Void> op, LivingEntity e, float yaw, float pt,
                                            PoseStack ps, MultiBufferSource buffers, int packedLight) {
        if (!(e instanceof Player player) || !(model instanceof PlayerModel<?> playerModel)
                || !(e.getVehicle() instanceof AbstractVehicle)) {
            op.call(model, pose, vc, light, overlay, color);
            return;
        }
        boolean hands = RiderHands.prepare(player, playerModel, pose, pt);
        if (hands) {
            RiderHands.apply();
        }
        try {
            op.call(model, pose, vc, light, overlay, color);
        } finally {
            if (hands) {
                AhfBody.clear();
            }
        }
    }

    /** Own first-person body: armour only. */
    @WrapOperation(method = RENDER, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V"))
    private void combinedassault$ownBodyLayers(RenderLayer<?, ?> layer, PoseStack pose, MultiBufferSource buffers,
                                               int light, Entity entity, float a, float b, float c, float d, float e,
                                               float f, Operation<Void> op) {
        if (!RiderHands.ownBody() || layer instanceof HumanoidArmorLayer<?, ?, ?>) {
            op.call(layer, pose, buffers, light, entity, a, b, c, d, e, f);
        }
    }
}
