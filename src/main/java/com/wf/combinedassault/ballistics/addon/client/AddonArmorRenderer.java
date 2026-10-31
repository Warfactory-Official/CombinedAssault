package com.wf.combinedassault.ballistics.addon.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.wf.combinedassault.ballistics.addon.AddonArmorLayer;
import com.wf.combinedassault.ballistics.addon.AddonFit;
import com.wf.combinedassault.ballistics.addon.AddonKind;
import com.wf.combinedassault.ballistics.addon.AddonMount;
import com.wf.combinedassault.ballistics.addon.AddonMountState;
import com.wf.combinedassault.ballistics.addon.AddonTargeting;
import com.wf.combinedassault.camo.client.CamoMesh;
import com.wf.combinedassault.vehicle.compat.IrisCompat;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.ballistics.addon.AddonArmorItem;
import com.wf.combinedassault.dynamics.structure.VehicleCubeGroup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * ERA and slat: one dynamic VertexBuffer and one draw per installed panel. A panel's buffer is rebuilt
 * only when its own state or quantised light changes. Light/normal handling as {@code VehicleCamoRenderer}.
 */
public final class AddonArmorRenderer {

    private static final int LIGHT_QUANTUM = 16;
    private static final int SWEEP_INTERVAL = 200;
    private static final Vector3f DIFFUSE_0 = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
    private static final Vector3f DIFFUSE_1 = new Vector3f(-0.2F, 1.0F, 0.7F).normalize();
    private static final Vector3f NETHER_DIFFUSE_1 = new Vector3f(-0.2F, -1.0F, 0.7F).normalize();

    private static final Map<AbstractVehicle, Panel[]> CACHE = new HashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static final PoseStack SCRATCH = new PoseStack();
    private static final Matrix3f LIGHT_ROTATION = new Matrix3f();
    private static final Vector3f LIGHT_0 = new Vector3f();
    private static final Vector3f LIGHT_1 = new Vector3f();
    private static int sweepCountdown = SWEEP_INTERVAL;

    private AddonArmorRenderer() {
    }

    private static final class Panel {
        private long signature = Long.MIN_VALUE;
        @Nullable
        private AddonMesh mesh;
        @Nullable
        private VertexBuffer buffer;
        private int uploadedLight = Integer.MIN_VALUE;

        private void discard() {
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
            uploadedLight = Integer.MIN_VALUE;
        }
    }

    private record Pending(AbstractVehicle vehicle, Panel[] panels, Matrix4f pose, int light, float partialTick) {
    }

    /** From inside the vehicle's entity render: {@code poseStack} carries the vehicle's own transform. */
    public static void enqueue(AbstractVehicle vehicle, PoseStack poseStack, MultiBufferSource bufferSource,
                               int packedLight, float partialTick) {
        AddonArmorLayer layer = vehicle.addonArmor();
        if (layer.isEmpty()) {
            return;
        }
        Panel[] panels = CACHE.get(vehicle);
        if (panels == null || panels.length != layer.mounts().size()) {
            if (panels != null) {
                for (Panel panel : panels) {
                    panel.discard();
                }
            }
            panels = new Panel[layer.mounts().size()];
            for (int i = 0; i < panels.length; i++) {
                panels[i] = new Panel();
            }
            CACHE.put(vehicle, panels);
        }
        for (int i = 0; i < panels.length; i++) {
            AddonMountState state = layer.state(i);
            long signature = signature(state);
            if (signature != panels[i].signature) {
                panels[i].discard();
                panels[i].signature = signature;
                panels[i].mesh = state.isEmpty() ? null : AddonMesh.bake(layer.mounts().get(i), state);
            }
        }
        if (IrisCompat.isRenderingShadowPass()) {
            VertexConsumer consumer = bufferSource.getBuffer(AddonMesh.RENDER_TYPE);
            for (int i = 0; i < panels.length; i++) {
                if (panels[i].mesh != null) {
                    poseStack.pushPose();
                    toPanel(poseStack, vehicle, layer, i, partialTick);
                    panels[i].mesh.emit(poseStack.last(), consumer, packedLight);
                    poseStack.popPose();
                }
            }
            return;
        }
        PENDING.add(new Pending(vehicle, panels, new Matrix4f(poseStack.last().pose()), quantise(packedLight), partialTick));
    }

    private static long signature(AddonMountState state) {
        return (long) state.fit.ordinal() << 56 ^ state.live * 0x9E3779B97F4A7C15L ^ Arrays.hashCode(state.bars);
    }

    private static int quantise(int packedLight) {
        int block = (packedLight & 0xFFFF) / LIGHT_QUANTUM * LIGHT_QUANTUM;
        int sky = (packedLight >> 16 & 0xFFFF) / LIGHT_QUANTUM * LIGHT_QUANTUM;
        return block | sky << 16;
    }

    /** Vehicle frame -> panel frame; same composition the server poses the trace with. */
    private static void toPanel(PoseStack poseStack, AbstractVehicle vehicle, AddonArmorLayer layer, int mount,
                                float partialTick) {
        VehicleCubeGroup group = layer.group(vehicle, mount);
        if (group != null) {
            CamoMesh.applyAttachment(poseStack, group, partialTick);
        }
        AddonMount m = layer.mounts().get(mount);
        poseStack.translate(m.cx, m.cy, m.cz);
        poseStack.mulPose(m.rotation);
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            PENDING.clear();
            sweep();
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            flush();
            outlines(event);
            PENDING.clear();
        }
    }

    private static void flush() {
        if (PENDING.isEmpty()) {
            return;
        }
        try (ByteBufferBuilder scratch = new ByteBufferBuilder(4096)) {
            for (Pending pending : PENDING) {
                for (Panel panel : pending.panels()) {
                    if (panel.mesh == null || (panel.buffer != null && panel.uploadedLight == pending.light())) {
                        continue;
                    }
                    panel.discard();
                    MeshData data = panel.mesh.build(scratch, pending.light());
                    if (data == null) {
                        continue;
                    }
                    panel.buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
                    panel.buffer.bind();
                    panel.buffer.upload(data);
                    panel.uploadedLight = pending.light();
                }
            }
        }
        VertexBuffer.unbind();
        Matrix4f camera = RenderSystem.getModelViewMatrix();
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        Matrix4f modelView = new Matrix4f();
        boolean nether = constantAmbientLight();
        RenderType type = AddonMesh.RENDER_TYPE;
        type.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        try {
            for (Pending pending : PENDING) {
                AddonArmorLayer layer = pending.vehicle().addonArmor();
                for (int i = 0; i < pending.panels().length; i++) {
                    Panel panel = pending.panels()[i];
                    if (panel.buffer == null || panel.buffer.isInvalid()) {
                        continue;
                    }
                    SCRATCH.pushPose();
                    SCRATCH.mulPose(pending.pose());
                    toPanel(SCRATCH, pending.vehicle(), layer, i, pending.partialTick());
                    PoseStack.Pose pose = SCRATCH.last();
                    modelView.set(camera).mul(pose.pose());
                    LIGHT_ROTATION.set(pose.normal()).transpose();
                    SCRATCH.popPose();
                    LIGHT_ROTATION.transform(DIFFUSE_0, LIGHT_0).normalize();
                    LIGHT_ROTATION.transform(nether ? NETHER_DIFFUSE_1 : DIFFUSE_1, LIGHT_1).normalize();
                    RenderSystem.setShaderLights(LIGHT_0, LIGHT_1);
                    panel.buffer.bind();
                    panel.buffer.drawWithShader(modelView, projection, shader);
                }
            }
        } finally {
            VertexBuffer.unbind();
            type.clearRenderState();
            if (nether) {
                Lighting.setupNetherLevel();
            } else {
                Lighting.setupLevel();
            }
        }
    }

    /** With a kit or welder in hand: where it can go. The aimed point brighter. */
    private static void outlines(RenderLevelStageEvent event) {
        Player player = Minecraft.getInstance().player;
        if (player == null || PENDING.isEmpty()) {
            return;
        }
        ItemStack held = player.getMainHandItem();
        AddonFit kit = held.getItem() instanceof AddonArmorItem item ? item.fit() : null;
        boolean welder = held.is(com.wf.combinedassault.ballistics.component.ComponentKind.HULL.toolTag);
        if (kit == null && !welder) {
            return;
        }
        AddonTargeting.Target aimed = AddonTargeting.underCrosshair(player);
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        PoseStack poseStack = new PoseStack();
        for (Pending pending : PENDING) {
            AbstractVehicle vehicle = pending.vehicle();
            AddonArmorLayer layer = vehicle.addonArmor();
            for (int i = 0; i < layer.mounts().size(); i++) {
                AddonMount mount = layer.mounts().get(i);
                AddonMountState state = layer.state(i);
                boolean fits = kit != null ? mount.info.kind == kit.kind
                        && (state.isEmpty() || (state.fit == kit && state.liveCells() < mount.cells()))
                        : state.fit.kind == AddonKind.SLAT && (state.bentBars() > 0 || state.intactBars() < mount.bars);
                if (!fits) {
                    continue;
                }
                boolean isAimed = aimed != null && aimed.vehicle() == vehicle && aimed.mount() == i;
                AddonFit shape = state.isEmpty() ? (kit != null ? kit : AddonFit.SLAT) : state.fit;
                poseStack.pushPose();
                poseStack.mulPose(pending.pose());
                toPanel(poseStack, vehicle, layer, i, pending.partialTick());
                AABB box = new AABB(-mount.halfWidth, -mount.halfHeight, mount.zMin(shape),
                        mount.halfWidth, mount.halfHeight, Math.max(mount.zMax(shape), mount.zMin(shape) + 0.02));
                LevelRenderer.renderLineBox(poseStack, lines, box, 1.0f, isAimed ? 1.0f : 0.85f,
                        isAimed ? 1.0f : 0.2f, isAimed ? 1.0f : 0.6f);
                poseStack.popPose();
            }
        }
        buffers.endBatch(RenderType.lines());
    }

    private static boolean constantAmbientLight() {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.effects().constantAmbientLight();
    }

    private static void sweep() {
        if (--sweepCountdown > 0) {
            return;
        }
        sweepCountdown = SWEEP_INTERVAL;
        Iterator<Map.Entry<AbstractVehicle, Panel[]>> iterator = CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<AbstractVehicle, Panel[]> cached = iterator.next();
            AbstractVehicle vehicle = cached.getKey();
            if (vehicle.isRemoved() || vehicle.level() != Minecraft.getInstance().level) {
                for (Panel panel : cached.getValue()) {
                    panel.discard();
                }
                iterator.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            for (Panel[] panels : CACHE.values()) {
                for (Panel panel : panels) {
                    panel.discard();
                }
            }
            CACHE.clear();
            PENDING.clear();
        }
    }
}
