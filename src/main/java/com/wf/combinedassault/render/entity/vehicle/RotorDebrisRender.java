package com.wf.combinedassault.render.entity.vehicle;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.combinedassault.aircraft.RotorDebris;
import com.wf.combinedassault.pack.client.ClientAssetsManager;
import com.wf.combinedassault.pack.client.vehicle.VehicleDisplay;
import com.wf.combinedassault.render.gem.GemRenderPreview;
import com.wf.combinedassault.render.model.VehicleModel;
import com.wf.combinedassault.render.model.VehicleModelInstance;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Vehicle glTF isolated to the debris node, bind pose, about {@link RotorDebris#pivot()}. */
public class RotorDebrisRender extends EntityRenderer<RotorDebris> {

    private final Map<RotorDebris, VehicleModelInstance> instances = new WeakHashMap<>();

    public RotorDebrisRender(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(RotorDebris debris, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
                       int light) {
        VehicleModelInstance instance = instances.computeIfAbsent(debris, d -> ClientAssetsManager.INSTANCE
                .getVehicleDisplay(d.display())
                .map(VehicleDisplay::getVehicleModel)
                .map(VehicleModel::createInstance)
                .orElse(null));
        if (instance == null) {
            return;
        }
        instance.isolate(debris.node(), Set.of());
        instance.resetPose();
        Vector3f pivot = debris.pivot();
        poseStack.pushPose();
        poseStack.mulPose(debris.rotation(partialTick, new Quaternionf()));
        poseStack.translate(-pivot.x, -pivot.y, -pivot.z);
        GemRenderPreview.drawPiece(debris.display(), instance, poseStack.last().pose(), light);
        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(@NotNull RotorDebris debris) {
        return null;
    }
}
