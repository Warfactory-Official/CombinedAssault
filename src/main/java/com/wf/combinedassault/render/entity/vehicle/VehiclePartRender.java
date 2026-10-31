package com.wf.combinedassault.render.entity.vehicle;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.combinedassault.render.model.VehicleModelInstance;
import com.wf.combinedassault.render.gem.GemRenderPreview;
import com.wf.combinedassault.vehicle.entity.VehiclePart;
import com.wf.combinedassault.vehicle.part.PartUnit;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/** Detached part: the vehicle glTF isolated to the part's render-bone subtree, at rest. */
public class VehiclePartRender extends EntityRenderer<VehiclePart> {

    public VehiclePartRender(EntityRendererProvider.Context pContext) {
        super(pContext);
    }

    @Override
    public void render(VehiclePart part, float pEntityYaw, float pPartialTick, PoseStack pPoseStack, MultiBufferSource bufferSource, int pPackedLight) {
        VehicleRender.renderHitbox(part, pPoseStack, bufferSource);
        VehicleModelInstance instance = part.getVehicleModelInstance();
        PartUnit<?> partUnit = part.getPartUnit();
        if (instance == null || partUnit == null || partUnit.getRenderBoneName() == null) {
            return;
        }
        int modelLight = pPackedLight;
        if (part.isDestroyed()) {
            modelLight = LightTexture.pack((int) (LightTexture.block(pPackedLight) / 1.5f),
                    (int) (LightTexture.sky(pPackedLight) / 1.5f));
        }
        instance.isolate(partUnit.getRenderBoneName(), Set.copyOf(part.getExcludedBoneNames()));
        instance.resetPose();
        pPoseStack.pushPose();
        try {
            super.render(part, pEntityYaw, pPartialTick, pPoseStack, bufferSource, pPackedLight);
            VehicleRender.applyVehicleRotation(part, pPartialTick, pPoseStack);
            Vec3 bottom = part.getBottomOffset();
            pPoseStack.translate(-bottom.x, -bottom.y, -bottom.z);
            GemRenderPreview.drawLevel(part, instance, pPoseStack.last().pose(), modelLight);
        } finally {
            pPoseStack.popPose();
        }
    }

    @Override
    public ResourceLocation getTextureLocation(@NotNull VehiclePart pEntity) {
        return null;
    }
}
