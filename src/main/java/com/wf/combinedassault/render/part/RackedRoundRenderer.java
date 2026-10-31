package com.wf.combinedassault.render.part;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.dynamics.structure.VehicleCubeGroup;
import com.wf.combinedassault.weapon.ordnance.RackedMissiles;

/** A racked round centred on its station, nose +Z. Pose: vehicle render frame. */
@OnlyIn(Dist.CLIENT)
public final class RackedRoundRenderer {

    private RackedRoundRenderer() {
    }

    /** @param station round centre in the vehicle model frame */
    public static void render(AbstractVehicle vehicle, VehicleCubeGroup.GlobalTransform station, float length,
                              ItemStack round, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        Vec3 offset = station.offset();
        poseStack.pushPose();
        poseStack.translate(offset.x, offset.y, offset.z);
        poseStack.mulPose(station.rotation());
        if (RackedMissiles.canRender(round)) {
            RackedMissiles.renderCentered(round, length, poseStack, bufferSource, packedLight);
        } else {
            // Untyped round: its item sprite, edge-on to the side, one station long.
            poseStack.mulPose(Axis.YP.rotationDegrees(90));
            poseStack.scale(length, length, length);
            Minecraft.getInstance().getItemRenderer().renderStatic(round, ItemDisplayContext.FIXED, packedLight,
                    OverlayTexture.NO_OVERLAY, poseStack, bufferSource, vehicle.level(), 0);
        }
        poseStack.popPose();
    }

}
