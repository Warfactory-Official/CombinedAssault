package com.wf.combinedassault.render.gem;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.gemrender.direct.DirectPass;
import com.wf.gemrender.direct.DirectRenderer;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.asset.GemRenderModels;
import com.wf.gemrender.texture.Paint;
import com.wf.gemrender.texture.VariantUv;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import com.wf.combinedassault.render.model.VehicleModelInstance;
import com.wf.combinedassault.render.entity.vehicle.VehicleRender;
import com.wf.combinedassault.vehicle.part.data.PartMount;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;

/**
 * GemRender hull + part mounts outside the level (GUI): {@link VehicleGemVisual} is a Flywheel instance, drawn only in
 * the level pass. Call after the entity renderer ran this frame (it publishes the pose).
 */
@OnlyIn(Dist.CLIENT)
public final class GemRenderPreview {

    private GemRenderPreview() {
    }

    /** {@code instance}'s published palette at {@code pose}; level pass. False if nothing is published yet. */
    public static boolean drawLevel(AbstractVehicle vehicle, VehicleModelInstance instance, Matrix4f pose, int light) {
        if (!(instance instanceof GemRenderModelInstance gem)) {
            return false;
        }
        GemRenderGltfModel gltf = ((GemRenderVehicleModel) gem.model()).gltf();
        GemRenderModelInstance.Snapshot snapshot = gem.snapshot();
        if (gltf == null || snapshot == null) {
            return false;
        }
        Paint paint = VehiclePaints.forVehicle(vehicle, gltf);
        DirectRenderer.submit(gltf, snapshot.palette(), snapshot.morphBlock(), new Matrix4f(pose), light,
                OverlayTexture.NO_OVERLAY, 0xFFFFFFFF, DirectPass.LEVEL, VariantUv.NONE,
                paint, gltf.paintReference(), RestPalettes.of(gltf));
        drawMounts(vehicle, gltf, snapshot, pose, light, DirectPass.LEVEL, paint);
        return true;
    }

    /** {@code poseStack} at the entity origin, as handed to the entity renderer. */
    public static void draw(AbstractVehicle vehicle, PoseStack poseStack, int light) {
        VehicleModelInstance active = VehicleRender.activeModelInstance(vehicle);
        if (!(active instanceof GemRenderModelInstance gem)) {
            return;
        }
        GemRenderGltfModel gltf = ((GemRenderVehicleModel) gem.model()).gltf();
        GemRenderModelInstance.Snapshot snapshot = gem.snapshot();
        if (gltf == null || snapshot == null) {
            return;
        }
        Vec3 centre = vehicle.centerOffset;
        Matrix4f pose = new Matrix4f(poseStack.last().pose())
                .translate((float) centre.x, (float) centre.y, (float) centre.z)
                .rotate(vehicle.rotYXZ(1.0f))
                .translate((float) -centre.x, (float) -centre.y, (float) -centre.z);
        Paint paint = VehiclePaints.forVehicle(vehicle, gltf);
        DirectRenderer.submit(gltf, snapshot.palette(), snapshot.morphBlock(), pose, light,
                OverlayTexture.NO_OVERLAY, 0xFFFFFFFF, DirectPass.GUI, VariantUv.NONE,
                paint, gltf.paintReference(), RestPalettes.of(gltf));
        drawMounts(vehicle, gltf, snapshot, pose, light, DirectPass.GUI, paint);
        DirectRenderer.flushGui();
    }

    /** Carried part mounts at their bone's published pose, as {@link VehicleGemVisual} draws them. */
    private static void drawMounts(AbstractVehicle vehicle, GemRenderGltfModel gltf, GemRenderModelInstance.Snapshot snapshot,
                                   Matrix4f pose, int light, DirectPass pass, Paint paint) {
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part.isDetached()) {
                continue;
            }
            for (PartMount mount : part.getData().getMounts()) {
                GemRenderGltfModel model = GemRenderModels.handle(mount.model()).get();
                int slot = gltf.layout().nodeTable().slotOfName(mount.node());
                if (model == null || slot < 0 || slot >= snapshot.jointCount() || !mount.carriedBy(part)) {
                    continue;
                }
                Matrix4f[] rest = RestPalettes.of(model);
                Paint own = model.paintable() ? paint : Paint.NONE;
                DirectRenderer.submit(model, rest, null, new Matrix4f(pose).mul(snapshot.palette()[slot]), light,
                        OverlayTexture.NO_OVERLAY, 0xFFFFFFFF, pass, VariantUv.NONE, own, model.paintReference(), rest);
            }
        }
    }
}
