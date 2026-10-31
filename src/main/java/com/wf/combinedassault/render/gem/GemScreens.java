package com.wf.combinedassault.render.gem;

import com.wf.combinedassault.render.model.VehicleModelInstance;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.screen.ScreenInfo;
import com.wf.combinedassault.vehicle.screen.VehicleScreens;
import com.wf.gemrender.direct.DirectPass;
import com.wf.gemrender.direct.DirectRenderer;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.wflib.ui.api.UiLayouts;
import com.wf.wflib.ui.api.WorldScreens;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Screen slot meshes of a posed vehicle model, drawn on the direct path ({@link VehicleGemVisual} draws the body and none of the slots). */
@OnlyIn(Dist.CLIENT)
public final class GemScreens {
    private static final Matrix4f POSE = new Matrix4f();
    private static final Vector3f CENTRE = new Vector3f();

    private GemScreens() {
    }

    /** {@code pose}: model root in camera space, as the entity renderer's stack holds it after the vehicle rotation; call after the model published. */
    public static void draw(AbstractVehicle vehicle, VehicleModelInstance instance, Matrix4f pose, int light, float partialTick) {
        if (!(instance instanceof GemRenderModelInstance gem)) {
            return;
        }
        GemRenderGltfModel gltf = ((GemRenderVehicleModel) gem.model()).gltf();
        GemRenderModelInstance.Snapshot snapshot = gem.snapshot();
        if (gltf == null || snapshot == null) {
            return;
        }
        VehicleScreens.State state = VehicleScreens.of(vehicle, gltf);
        if (state == null) {
            return;
        }
        POSE.set(pose);
        boolean any = false;
        for (int i = 0; i < state.count; i++) {
            ScreenInfo info = state.infos[i];
            GemRenderGltfModel.SlotFrame frame = state.frames[i];
            if (frame.node() >= snapshot.jointCount()) {
                state.textures[i] = null;
                continue;
            }
            snapshot.palette()[frame.node()].transformPosition(frame.centre(), CENTRE);
            POSE.transformPosition(CENTRE);
            state.textures[i] = WorldScreens.texture(state.views[i], UiLayouts.get(info.layout), state.views[i],
                    CENTRE.length(), frame.width(), frame.height(), info.unitsWidth, info.maxFps);
            if (state.textures[i] != null) {
                state.views[i].refresh(partialTick);
                any = true;
            }
        }
        if (any) {
            DirectRenderer.submitSlots(gltf, snapshot.palette(), snapshot.morphBlock(), POSE, light,
                    OverlayTexture.NO_OVERLAY, 0xFFFFFFFF, DirectPass.LEVEL, state);
        }
    }
}
