package com.wf.combinedassault.client.render.part;

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
import com.wf.combinedassault.compat.wflib.WfLibCompat;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.structure.VehicleCubeGroup;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;

/** A racked round drawn in its station box, nose to the box's +Z face, hung from its top. Pose: vehicle render frame. */
@OnlyIn(Dist.CLIENT)
public final class RackedRoundRenderer {

    private RackedRoundRenderer() {
    }

    public static void render(AbstractVehicle vehicle, VehicleCubeOBB station, ItemStack round,
                              PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        VehicleCubeGroup.GlobalTransform transform = station.group.globalTransform();
        Vec3 offset = transform.offset();
        poseStack.pushPose();
        poseStack.translate(offset.x, offset.y, offset.z);
        poseStack.mulPose(transform.rotation());
        poseStack.translate(station.x + station.width / 2, station.y + station.height / 2,
                station.z + station.depth / 2);
        if (!WfLibCompat.renderRound(round, station.height / 2, poseStack, bufferSource, packedLight)) {
            // Untyped round: its item sprite, edge-on to the side, one station long.
            float scale = (float) station.depth;
            poseStack.mulPose(Axis.YP.rotationDegrees(90));
            poseStack.scale(scale, scale, scale);
            Minecraft.getInstance().getItemRenderer().renderStatic(round, ItemDisplayContext.FIXED, packedLight,
                    OverlayTexture.NO_OVERLAY, poseStack, bufferSource, vehicle.level(), 0);
        }
        poseStack.popPose();
    }

}
