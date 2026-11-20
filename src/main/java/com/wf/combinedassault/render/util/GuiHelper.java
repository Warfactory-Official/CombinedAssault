package com.wf.combinedassault.render.util;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.wf.combinedassault.render.shader.ModShaders;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;

public class GuiHelper {

    public static void drawOpticalScope(GuiGraphics guiGraphics, int width, int height) {
        ShaderInstance shader = ModShaders.getOpticalScopeShader();
        if (shader == null || width <= 0 || height <= 0) {
            return;
        }
        guiGraphics.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(() -> shader);
        shader.safeGetUniform("ScreenSize").set((float) width, (float) height);
        try {
            Matrix4f matrix = guiGraphics.pose().last().pose();
            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            buffer.addVertex(matrix, 0, height, 0).setUv(0, 1);
            buffer.addVertex(matrix, width, height, 0).setUv(1, 1);
            buffer.addVertex(matrix, width, 0, 0).setUv(1, 0);
            buffer.addVertex(matrix, 0, 0, 0).setUv(0, 0);
            BufferUploader.drawWithShader(buffer.buildOrThrow());
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
        }
    }

}
