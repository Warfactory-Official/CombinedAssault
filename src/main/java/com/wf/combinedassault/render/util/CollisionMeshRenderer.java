package com.wf.combinedassault.render.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import com.wf.combinedassault.vehicle.client.VehicleKeys;
import com.wf.wflib.physics.api.ChunkCollisionCache;
import com.wf.wflib.physics.api.SectionCollision;

import java.util.List;

/**
 * Draws collision cache snapshots: what the cache actually baked.
 * Absent sections are the point; skipped by the broad-phase gate.
 */
public final class CollisionMeshRenderer {

    /** Kept small: lots of line geometry per section. */
    private static final int SECTION_RADIUS = 2;

    private static final int SURFACE_RADIUS = 16;

    public enum Mode {

        OFF("off"),
        BOXES("collision boxes"),
        SURFACE("drive surface"),
        BOTH("boxes + drive surface");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        boolean boxes() {
            return this == BOXES || this == BOTH;
        }

        boolean surface() {
            return this == SURFACE || this == BOTH;
        }

    }

    private static final Mode[] MODES = Mode.values();

    private static Mode mode = Mode.OFF;

    private CollisionMeshRenderer() {}

    public static boolean isEnabled() {
        return mode != Mode.OFF;
    }

    public static Mode mode() {
        return mode;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        while (VehicleKeys.TOGGLE_COLLISION_DEBUG.consumeClick()) {
            mode = MODES[(mode.ordinal() + 1) % MODES.length];
            if (mode == Mode.OFF) {
                CollisionMeshDebug.clear();
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.literal("Vehicle collision overlay: " + mode.label), true);
            }
        }
        if (mode == Mode.OFF) {
            return;
        }
        // Client never runs tickPhysics; prepare cache here so overlay isn't empty.
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Entity cameraEntity = minecraft.getCameraEntity();
        if (level == null || cameraEntity == null) {
            return;
        }
        double reach = SECTION_RADIUS * 16.0 + 8.0;
        ChunkCollisionCache.of(level).prepare(level, cameraEntity.getBoundingBox().inflate(reach));
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (mode == Mode.OFF || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        Vec3 cameraPos = event.getCamera().getPosition();
        BlockPos around = BlockPos.containing(cameraPos);
        Frame frame = frameFor(level, around);
        if (frame.meshes.isEmpty() && frame.surface.length == 0) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        VertexConsumer buffer = bufferSource.getBuffer(DebugRenderTypes.LINES_NO_DEPTH);

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        if (mode.boxes()) {
            for (CollisionMeshDebug.SectionMesh mesh : frame.meshes) {
                drawSectionBounds(poseStack, buffer, mesh);
                for (CollisionMeshDebug.MeshBox box : mesh.boxes()) {
                    LevelRenderer.renderLineBox(poseStack, buffer,
                            box.minX(), box.minY(), box.minZ(),
                            box.maxX(), box.maxY(), box.maxZ(),
                            0.1F, 1.0F, 0.1F, 0.55F);
                }
            }
        }
        float[] surface = frame.surface;
        for (int i = 0; i < surface.length; i += SurfaceMeshDebug.STRIDE) {
            line(poseStack, buffer, surface[i], surface[i + 1], surface[i + 2], surface[i + 3], surface[i + 4], surface[i + 5],
                    surface[i + 6], surface[i + 7], surface[i + 8], surface[i + 9]);
        }

        poseStack.popPose();
        bufferSource.endBatch(DebugRenderTypes.LINES_NO_DEPTH);
    }

    private static void line(PoseStack poseStack, VertexConsumer buffer,
                             double x1, double y1, double z1, double x2, double y2, double z2,
                             float red, float green, float blue, float alpha) {
        PoseStack.Pose pose = poseStack.last();
        float nx = (float) (x2 - x1);
        float ny = (float) (y2 - y1);
        float nz = (float) (z2 - z1);
        float length = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0e-6F) {
            return;
        }
        nx /= length;
        ny /= length;
        nz /= length;
        buffer.addVertex(pose, (float) x1, (float) y1, (float) z1)
                .setColor(red, green, blue, alpha).setNormal(pose, nx, ny, nz);
        buffer.addVertex(pose, (float) x2, (float) y2, (float) z2)
                .setColor(red, green, blue, alpha).setNormal(pose, nx, ny, nz);
    }

    private record Frame(long gameTime, long cameraSection, Mode mode,
                         List<CollisionMeshDebug.SectionMesh> meshes,
                         float[] surface) {}

    private static Frame lastFrame;

    /** Caches per tick. */
    private static Frame frameFor(ClientLevel level, BlockPos around) {
        long gameTime = level.getGameTime();
        long cameraSection = SectionPos.asLong(around);
        // Mode in cache key: else mode-cycle mid-tick reuses stale frame for 1 tick.
        if (lastFrame != null && lastFrame.gameTime == gameTime
                && lastFrame.cameraSection == cameraSection && lastFrame.mode == mode) {
            return lastFrame;
        }

        float[] surface = mode.surface() ? SurfaceMeshDebug.around(level, around, SURFACE_RADIUS, 8, 4) : new float[0];
        if (!mode.boxes()) {
            lastFrame = new Frame(gameTime, cameraSection, mode, List.of(), surface);
            return lastFrame;
        }

        List<CollisionMeshDebug.SectionMesh> meshes =
                CollisionMeshDebug.meshesAround(level, around, SECTION_RADIUS);

        lastFrame = new Frame(gameTime, cameraSection, mode, meshes, surface);
        return lastFrame;
    }

    /** Tint shows the storage-class palette gate working. */
    private static void drawSectionBounds(PoseStack poseStack, VertexConsumer buffer,
                                          CollisionMeshDebug.SectionMesh mesh) {
        long key = mesh.sectionKey();
        int x = SectionPos.sectionToBlockCoord(SectionPos.x(key));
        int y = SectionPos.sectionToBlockCoord(SectionPos.y(key));
        int z = SectionPos.sectionToBlockCoord(SectionPos.z(key));
        float red = mesh.kind() == SectionCollision.Kind.UNIFORM ? 0.3F : 0.55F;
        float green = mesh.kind() == SectionCollision.Kind.UNIFORM ? 0.6F : 0.55F;
        float blue = 1.0F;
        LevelRenderer.renderLineBox(poseStack, buffer, x, y, z, x + 16.0, y + 16.0, z + 16.0,
                red, green, blue, 0.25F);
    }

}
