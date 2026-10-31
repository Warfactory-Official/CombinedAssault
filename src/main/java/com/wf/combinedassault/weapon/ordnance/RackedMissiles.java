package com.wf.combinedassault.weapon.ordnance;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.wf.wflib.missile.api.client.ModModels;
import com.wf.wflib.missile.api.ModelledItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** A racked WFLib round drawn as its own airframe at true size, centred on the pose, nose +Z. */
@OnlyIn(Dist.CLIENT)
public final class RackedMissiles {

    private RackedMissiles() {
    }

    /** Baked airframe of a WFLib round; null otherwise. */
    @org.jetbrains.annotations.Nullable
    static ResourceLocation baked(ItemStack round) {
        return round.getItem() instanceof ModelledItem modelled && ModModels.baked(modelled.modelId())
                ? modelled.modelId() : null;
    }

    public static boolean canRender(ItemStack round) {
        return !round.isEmpty() && baked(round) != null;
    }

    /** Airframe centred on the pose origin, nose +Z; longer than {@code length} => scaled down to it. */
    public static boolean renderCentered(ItemStack round, float length, PoseStack poseStack, MultiBufferSource bufferSource,
                                  int packedLight) {
        ResourceLocation id = baked(round);
        if (id == null) {
            return false;
        }
        ModModels.RenderModel model = ModModels.render(id);
        Vec3 center = model.center();
        poseStack.pushPose();
        float scale = (float) Math.min(1, length / model.length());
        poseStack.scale(scale, scale, scale);
        poseStack.mulPose(Axis.XP.rotationDegrees(90));
        poseStack.translate(-center.x, -center.y, -center.z);
        Minecraft.getInstance().getItemRenderer().renderModelLists(model.baked(), round, packedLight,
                OverlayTexture.NO_OVERLAY, poseStack, bufferSource.getBuffer(Sheets.cutoutBlockSheet()));
        poseStack.popPose();
        return true;
    }

}
