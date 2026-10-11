package com.wf.combinedassault.compat.wflib;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.wf.wflib.MissileModels;
import com.wf.wflib.ModModels;
import com.wf.wflib.item.ModelledItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** A racked WFLib round drawn as its own airframe at true size, on the pose's Z axis, hung flush from {@code top}. */
@OnlyIn(Dist.CLIENT)
final class RackedMissiles {

    private RackedMissiles() {
    }

    static boolean render(ItemStack round, double top, PoseStack poseStack, MultiBufferSource bufferSource,
                          int packedLight) {
        if (!(round.getItem() instanceof ModelledItem modelled)) {
            return false;
        }
        ResourceLocation id = modelled.modelId();
        // render() falls back to the default airframe, seven blocks of V2 on a pylon.
        if (!ModModels.baked(id)) {
            return false;
        }
        ModModels.RenderModel model = ModModels.render(id);
        Vec3 center = model.center();
        poseStack.pushPose();
        poseStack.translate(0, top - MissileModels.dimensions(id).z / 2, 0);
        // Airframes: nose +Y.
        poseStack.mulPose(Axis.XP.rotationDegrees(90));
        poseStack.translate(-center.x, -center.y, -center.z);
        Minecraft.getInstance().getItemRenderer().renderModelLists(model.baked(), round, packedLight,
                OverlayTexture.NO_OVERLAY, poseStack, bufferSource.getBuffer(Sheets.cutoutBlockSheet()));
        poseStack.popPose();
        return true;
    }

    /** Baked airframe of a WFLib round; null otherwise. */
    @org.jetbrains.annotations.Nullable
    static ResourceLocation baked(ItemStack round) {
        return round.getItem() instanceof ModelledItem modelled && ModModels.baked(modelled.modelId())
                ? modelled.modelId() : null;
    }

    /** Airframe centred on the pose origin, nose +Z. */
    static boolean renderCentered(ItemStack round, PoseStack poseStack, MultiBufferSource bufferSource,
                                  int packedLight) {
        ResourceLocation id = baked(round);
        if (id == null) {
            return false;
        }
        ModModels.RenderModel model = ModModels.render(id);
        Vec3 center = model.center();
        poseStack.pushPose();
        poseStack.mulPose(Axis.XP.rotationDegrees(90));
        poseStack.translate(-center.x, -center.y, -center.z);
        Minecraft.getInstance().getItemRenderer().renderModelLists(model.baked(), round, packedLight,
                OverlayTexture.NO_OVERLAY, poseStack, bufferSource.getBuffer(Sheets.cutoutBlockSheet()));
        poseStack.popPose();
        return true;
    }

}
