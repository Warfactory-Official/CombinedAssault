package com.wf.combinedassault.camo.client;

import com.wf.combinedassault.render.model.VehicleModelInstance;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.wf.combinedassault.camo.CamoConfig;
import com.wf.combinedassault.vehicle.compat.IrisCompat;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.camo.CamoLayer;
import com.wf.combinedassault.dynamics.structure.VehicleCubeGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Draws vehicle camo from buffers that change only when painted.
 * Per-frame cost: one draw per (bone, sheet), independent of piece count; {@link CamoMesh} bakes once.
 * Re-upload triggers: packed light (lives in the vertex buffer), new model instance after reload.
 * Iris shadow pass: own bound program => raw buffer draw gets the vanilla shader; replays through the
 * ordinary buffer source instead.
 */
public final class VehicleCamoRenderer {

    /** Steps per light channel before it counts as changed. */
    private static final int LIGHT_QUANTUM = 16;
    /** Ticks between sweeps for stale GL buffers. */
    private static final int SWEEP_INTERVAL = 200;
    /** Blocks driven before foliage tint re-resolves to the new biome. */
    private static final double REBAKE_DISTANCE = 24;

    /** Order sets up render state once per sheet, not per vehicle. */
    private static final RenderType[] SHEETS = {CamoMesh.CUTOUT_SHEET, CamoMesh.TRANSLUCENT_SHEET};

    /**
     * Mirrors {@code Lighting}'s private diffuse directions; not accessible from here otherwise.
     * Shaders compare the vertex normal (bone space, never re-transformed) to these; rotating these
     * instead of every normal is 1 vector product/draw, pixel-identical.
     */
    private static final Vector3f DIFFUSE_0 = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
    private static final Vector3f DIFFUSE_1 = new Vector3f(-0.2F, 1.0F, 0.7F).normalize();
    private static final Vector3f NETHER_DIFFUSE_0 = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
    private static final Vector3f NETHER_DIFFUSE_1 = new Vector3f(-0.2F, -1.0F, 0.7F).normalize();

    private static final PoseStack SCRATCH = new PoseStack();
    private static final Matrix3f LIGHT_ROTATION = new Matrix3f();
    private static final Vector3f LIGHT_0 = new Vector3f();
    private static final Vector3f LIGHT_1 = new Vector3f();
    private static final Map<AbstractVehicle, Entry> CACHE = new HashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static int sweepCountdown = SWEEP_INTERVAL;

    private VehicleCamoRenderer() {
    }

    private record Pending(Entry entry, Matrix4f pose, int packedLight, float partialTick) {
    }

    private record Buffered(int attachment, RenderType renderType, VertexBuffer buffer) {
    }

    private static final class Entry {

        private final AbstractVehicle vehicle;
        private final List<Buffered> buffers = new ArrayList<>();
        private CamoMesh mesh;
        private VehicleModelInstance instance;
        private int bakedVersion = Integer.MIN_VALUE;
        private int uploadedLight = Integer.MIN_VALUE;
        private double bakedX;
        private double bakedZ;

        private Entry(AbstractVehicle vehicle) {
            this.vehicle = vehicle;
        }

        private void discardBuffers() {
            for (Buffered buffered : buffers) {
                buffered.buffer().close();
            }
            buffers.clear();
            uploadedLight = Integer.MIN_VALUE;
        }
    }

    /**
     * Routes this vehicle's camo to whichever path the pass can use.
     * Called from inside the entity render: pose stack carries the vehicle transform, model instance
     * posed for this frame.
     */
    public static void enqueue(AbstractVehicle vehicle, PoseStack poseStack, MultiBufferSource bufferSource,
                               int packedLight, float partialTick) {
        CamoLayer layer = vehicle.getCamoLayer();
        VehicleModelInstance instance = vehicle.getVehicleModelInstance();
        if (layer.isEmpty() || instance == null) {
            return;
        }
        Entry entry = CACHE.computeIfAbsent(vehicle, Entry::new);
        if (entry.mesh == null || entry.bakedVersion != layer.version() || entry.instance != instance
                || movedBiomes(entry, vehicle)) {
            entry.discardBuffers();
            entry.mesh = CamoMesh.bake(vehicle, layer);
            entry.instance = instance;
            entry.bakedVersion = layer.version();
            entry.bakedX = vehicle.getX();
            entry.bakedZ = vehicle.getZ();
        }
        if (entry.mesh.isEmpty()) {
            return;
        }
        // Config is a fallback for anything else that doesn't survive a hand-issued draw.
        if (IrisCompat.isRenderingShadowPass() || !CamoConfig.get().bufferedRender()) {
            entry.mesh.emit(poseStack, bufferSource, packedLight, partialTick);
            return;
        }
        // Deferred to flush: binding a VAO under a mid-batch buffer source here risks corruption for no gain.
        PENDING.add(new Pending(entry, new Matrix4f(poseStack.last().pose()), quantise(packedLight), partialTick));
    }

    /** Foliage tint keeps the biome it was baked in until driven far enough to look wrong. */
    private static boolean movedBiomes(Entry entry, AbstractVehicle vehicle) {
        double dx = vehicle.getX() - entry.bakedX;
        double dz = vehicle.getZ() - entry.bakedZ;
        return dx * dx + dz * dz > REBAKE_DISTANCE * REBAKE_DISTANCE;
    }

    /** Debounces re-upload: unquantised, crossing a shadow line re-uploads the whole layer twice (there, back). */
    private static int quantise(int packedLight) {
        int block = (packedLight & 0xFFFF) / LIGHT_QUANTUM * LIGHT_QUANTUM;
        int sky = (packedLight >> 16 & 0xFFFF) / LIGHT_QUANTUM * LIGHT_QUANTUM;
        return block | sky << 16;
    }

    private static void upload(Entry entry, int packedLight) {
        if (!entry.buffers.isEmpty() && entry.uploadedLight == packedLight) {
            return;
        }
        entry.discardBuffers();
        try (ByteBufferBuilder scratch = new ByteBufferBuilder(entry.mesh.vertexCount() * 64)) {
            for (CamoMesh.Group group : entry.mesh.groups()) {
                MeshData meshData = CamoMesh.buildMesh(group, scratch, packedLight);
                if (meshData == null) {
                    continue;
                }
                VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                buffer.bind();
                // upload() takes ownership of the MeshData and closes it.
                buffer.upload(meshData);
                entry.buffers.add(new Buffered(group.attachment(), group.renderType(), buffer));
            }
        }
        VertexBuffer.unbind();
        entry.uploadedLight = packedLight;
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
        }
    }

    private static void flush() {
        if (PENDING.isEmpty()) {
            return;
        }
        // Upload pass first: inline uploads would mutate the buffer list being walked; frame 1's list
        // starts empty, drawing nothing.
        for (Pending pending : PENDING) {
            upload(pending.entry(), pending.packedLight());
        }
        // Camera rotation isn't in the entity pose; LevelRenderer leaves it on RenderSystem's model-view
        // stack for the buffer-source path to pick up. Hand-issued draw must compose it itself, or the
        // layer draws as if the camera never turned.
        Matrix4f camera = RenderSystem.getModelViewMatrix();
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        Matrix4f modelView = new Matrix4f();
        RenderType active = null;
        ShaderInstance shader = null;
        try {
            for (RenderType sheet : SHEETS) {
                for (Pending pending : PENDING) {
                    for (Buffered buffered : pending.entry().buffers) {
                        if (buffered.renderType() != sheet || buffered.buffer().isInvalid()) {
                            continue;
                        }
                        if (active != sheet) {
                            if (active != null) {
                                active.clearRenderState();
                            }
                            sheet.setupRenderState();
                            active = sheet;
                            shader = RenderSystem.getShader();
                        }
                        // PoseStack, not hand matrix math: must match how the group's own collision cubes
                        // are posed (what the placement trace inverted).
                        SCRATCH.pushPose();
                        SCRATCH.mulPose(pending.pose());
                        VehicleCubeGroup attachment =
                                pending.entry().mesh.attachment(buffered.attachment());
                        if (attachment != null) {
                            CamoMesh.applyAttachment(SCRATCH, attachment, pending.partialTick());
                        }
                        PoseStack.Pose bone = SCRATCH.last();
                        modelView.set(camera).mul(bone.pose());
                        // Transposed, not inverted: rotation + uniform scale; shader normalizes the light
                        // direction anyway.
                        LIGHT_ROTATION.set(bone.normal()).transpose();
                        SCRATCH.popPose();
                        setBoneLights();
                        buffered.buffer().bind();
                        buffered.buffer().drawWithShader(modelView, projection, shader);
                    }
                }
            }
        } finally {
            VertexBuffer.unbind();
            if (active != null) {
                active.clearRenderState();
            }
            restoreLevelLights();
            PENDING.clear();
        }
    }

    /** Rotate the level's diffuse directions into the bone's own space, for one draw. */
    private static void setBoneLights() {
        boolean nether = constantAmbientLight();
        LIGHT_ROTATION.transform(nether ? NETHER_DIFFUSE_0 : DIFFUSE_0, LIGHT_0).normalize();
        LIGHT_ROTATION.transform(nether ? NETHER_DIFFUSE_1 : DIFFUSE_1, LIGHT_1).normalize();
        RenderSystem.setShaderLights(LIGHT_0, LIGHT_1);
    }

    /**
     * Restores LevelRenderer's light vectors.
     * RenderSystem holds them by reference; everything drawn after (block entities, particles, hand)
     * reads them, so a rotated pair left behind lights half the frame from the wrong side.
     */
    private static void restoreLevelLights() {
        if (constantAmbientLight()) {
            Lighting.setupNetherLevel();
        } else {
            Lighting.setupLevel();
        }
    }

    private static boolean constantAmbientLight() {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.effects().constantAmbientLight();
    }

    /**
     * Drops entries for vehicles that are gone.
     * Not a weak map: a GC'd entry would leak its GL buffer; this sweep bounds the cache instead.
     */
    private static void sweep() {
        if (--sweepCountdown > 0) {
            return;
        }
        sweepCountdown = SWEEP_INTERVAL;
        Iterator<Map.Entry<AbstractVehicle, Entry>> iterator = CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<AbstractVehicle, Entry> cached = iterator.next();
            AbstractVehicle vehicle = cached.getKey();
            if (vehicle.isRemoved() || vehicle.level() != Minecraft.getInstance().level) {
                cached.getValue().discardBuffers();
                iterator.remove();
            }
        }
    }

    /** The level going away is the one moment every buffer is certainly dead. */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            clear();
        }
    }

    public static void clear() {
        for (Entry entry : CACHE.values()) {
            entry.discardBuffers();
        }
        CACHE.clear();
        PENDING.clear();
    }
}
