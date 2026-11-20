package com.wf.combinedassault.render.util;

import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import com.wf.combinedassault.CombinedAssault;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import org.joml.Matrix4f;

public class RenderHelper {

    public static void drawCenteredString(GuiGraphics guiGraphics, Font pFont, String pText, int pX, int pY, int pColor) {
        guiGraphics.drawString(pFont, pText, pX - pFont.width(pText) / 2, pY, pColor, false);
    }

    public static void fill(GuiGraphics guiGraphics, RenderType pRenderType, float pMinX, float pMinY, float pMaxX, float pMaxY, float pZ, int pColor) {
        Matrix4f matrix4f = guiGraphics.pose().last().pose();
        if (pMinX < pMaxX) {
            float i = pMinX;
            pMinX = pMaxX;
            pMaxX = i;
        }

        if (pMinY < pMaxY) {
            float j = pMinY;
            pMinY = pMaxY;
            pMaxY = j;
        }

        float f3 = (float) FastColor.ARGB32.alpha(pColor) / 255.0F;
        float f = (float) FastColor.ARGB32.red(pColor) / 255.0F;
        float f1 = (float) FastColor.ARGB32.green(pColor) / 255.0F;
        float f2 = (float) FastColor.ARGB32.blue(pColor) / 255.0F;
        VertexConsumer vertexconsumer = guiGraphics.bufferSource().getBuffer(pRenderType);
        vertexconsumer.addVertex(matrix4f, pMinX, pMinY, pZ).setColor(f, f1, f2, f3);
        vertexconsumer.addVertex(matrix4f, pMinX, pMaxY, pZ).setColor(f, f1, f2, f3);
        vertexconsumer.addVertex(matrix4f, pMaxX, pMaxY, pZ).setColor(f, f1, f2, f3);
        vertexconsumer.addVertex(matrix4f, pMaxX, pMinY, pZ).setColor(f, f1, f2, f3);
    }

    public static void renderArrow3D(PoseStack poseStack, MultiBufferSource bufferSource, float w, float h, int r, int g, int b, int a) {
        poseStack.pushPose();
        {
            long time = System.currentTimeMillis();
            float bounce = (float) Math.sin((time % 1000L) / 1000.0f * Math.PI * 2) * 0.1f;
            float hoverHeight = 0.8f;
            poseStack.translate(0, hoverHeight + bounce, 0);

            float spin = (time % 2000L) / 2000.0f * 360.0f;
            poseStack.rotateAround(Axis.YP.rotationDegrees(spin), 0, 0, 0);
            ResourceLocation whiteTexture = CombinedAssault.resourceLocation("textures/misc/white.png");
            VertexConsumer arrowBuilder = bufferSource.getBuffer(RenderType.entityCutout(whiteTexture));
            PoseStack.Pose poseEntry = poseStack.last();
            Matrix4f pose = poseEntry.pose();
            int light = 15728880;
            RenderHelper.renderSolidQuad(arrowBuilder, pose, poseEntry, 0, 0, 0,   w, h, w,  -w, h, w,  -w, h, w,  r, g, b, a, light);
            RenderHelper.renderSolidQuad(arrowBuilder, pose, poseEntry, 0, 0, 0,  -w, h, -w,  w, h, -w,  w, h, -w, r, g, b, a, light);
            RenderHelper.renderSolidQuad(arrowBuilder, pose, poseEntry, 0, 0, 0,  -w, h, w,  -w, h, -w, -w, h, -w, r, g, b, a, light);
            RenderHelper.renderSolidQuad(arrowBuilder, pose, poseEntry, 0, 0, 0,   w, h, -w,  w, h, w,   w, h, w,  r, g, b, a, light);
            RenderHelper.renderSolidQuad(arrowBuilder, pose, poseEntry, -w, h, -w, -w, h, w,   w, h, w,   w, h, -w, r, g, b, a, light);
        }
        poseStack.popPose();
    }

    public static void renderSolidQuad(VertexConsumer builder, Matrix4f pose, PoseStack.Pose poseEntry,
                                 float x1, float y1, float z1, float x2, float y2, float z2,
                                 float x3, float y3, float z3, float x4, float y4, float z4,
                                 int r, int g, int b, int a, int light) {
        // UV(0,0) + white texture (caller) => solid color, no texture sampling.
        builder.addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setUv(0, 0).setOverlay(OverlayTexture.NO_OVERLAY).setUv2(light & 0xFFFF, light >> 16 & 0xFFFF).setNormal(poseEntry, 0, 1, 0);
        builder.addVertex(pose, x2, y2, z2).setColor(r, g, b, a).setUv(0, 0).setOverlay(OverlayTexture.NO_OVERLAY).setUv2(light & 0xFFFF, light >> 16 & 0xFFFF).setNormal(poseEntry, 0, 1, 0);
        builder.addVertex(pose, x3, y3, z3).setColor(r, g, b, a).setUv(0, 0).setOverlay(OverlayTexture.NO_OVERLAY).setUv2(light & 0xFFFF, light >> 16 & 0xFFFF).setNormal(poseEntry, 0, 1, 0);
        builder.addVertex(pose, x4, y4, z4).setColor(r, g, b, a).setUv(0, 0).setOverlay(OverlayTexture.NO_OVERLAY).setUv2(light & 0xFFFF, light >> 16 & 0xFFFF).setNormal(poseEntry, 0, 1, 0);
    }

}
