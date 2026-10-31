package com.wf.combinedassault.render.gem;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.gemrender.asset.GemRenderModels;
import com.wf.gemrender.direct.DirectPass;
import com.wf.gemrender.direct.DirectRenderer;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/** Rest-pose glTF through GemRender's direct path; LEVEL outside the level => GUI (GemRender re-queues). */
@OnlyIn(Dist.CLIENT)
public final class GemModels {

    private GemModels() {
    }

    /** False until GemRender has imported {@code asset}; it is requested by the call. */
    public static boolean draw(ResourceLocation asset, PoseStack poseStack, int light) {
        return draw(asset, poseStack.last().pose(), light);
    }

    public static boolean draw(ResourceLocation asset, Matrix4f pose, int light) {
        return submit(asset, pose, light, DirectPass.LEVEL);
    }

    /** Screen code: drawn at once, inside the caller's scissor. */
    public static boolean drawGui(ResourceLocation asset, PoseStack poseStack, int light) {
        boolean drawn = submit(asset, poseStack.last().pose(), light, DirectPass.GUI);
        DirectRenderer.flushGui();
        return drawn;
    }

    private static boolean submit(ResourceLocation asset, Matrix4f pose, int light, DirectPass pass) {
        GemRenderGltfModel model = GemRenderModels.get(asset);
        if (model == null) {
            return false;
        }
        DirectRenderer.submit(model, null, 0, new Matrix4f(pose), light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF, pass);
        return true;
    }
}
