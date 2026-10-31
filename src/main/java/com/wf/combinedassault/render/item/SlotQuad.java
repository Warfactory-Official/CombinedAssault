package com.wf.combinedassault.render.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/** One-block texture quad centred on the origin, facing +Z: a slot icon held as an item. */
public final class SlotQuad {

    private SlotQuad() {
    }

    public static void render(PoseStack poseStack, VertexConsumer buffer, int light, int overlay) {
        PoseStack.Pose pose = poseStack.last();
        vertex(buffer, pose, -0.5f, -0.5f, 0, 1, light, overlay);
        vertex(buffer, pose, 0.5f, -0.5f, 1, 1, light, overlay);
        vertex(buffer, pose, 0.5f, 0.5f, 1, 0, light, overlay);
        vertex(buffer, pose, -0.5f, 0.5f, 0, 0, light, overlay);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float u, float v,
                               int light, int overlay) {
        buffer.addVertex(pose, x, y, 0).setColor(-1).setUv(u, v).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
    }
}
