package com.wf.combinedassault.camo.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.FastColor;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.util.VectorUtil;
import com.wf.combinedassault.camo.CamoLayer;
import com.wf.combinedassault.camo.CamoPiece;
import com.wf.combinedassault.dynamics.structure.VehicleCubeGroup;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Layer flattened to vertex arrays, batched by (bone, sheet) pair; same bone+sheet merges into one array.
 * Rebuilt only on paint, not per frame.
 * Baked in bone-local space: bone's global transform applied at draw time, so a rotating turret invalidates nothing.
 * Bone = structure group, not display group. Collision skeleton (server-posed) and display skeleton
 * (client-animated) don't share bone names; camo is traced against collision cubes, so it draws in the
 * structure group's frame.
 */
@OnlyIn(Dist.CLIENT)
public final class CamoMesh {

    /**
     * +Z push, as fraction of piece size. 0 deliberately: ray tracer's normal isn't reliably outward,
     * so centred (not offset) straddles the skin whichever way the normal faces.
     */
    private static final float SURFACE_BIAS = 0f;
    /** Block models are baked with a fixed seed: camo must not shimmer between rebuilds. */
    private static final long MODEL_SEED = 42L;

    static final RenderType CUTOUT_SHEET = RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS);
    static final RenderType TRANSLUCENT_SHEET = RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);

    private static final Direction[] FACES_AND_GENERAL = new Direction[]{
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null
    };

    /**
     * One draw: everything on {@code attachment} that renders with {@code renderType}.
     *
     * @param attachment index into the mesh's own attachment list, or -1 for the vehicle's root frame
     */
    public record Group(int attachment, RenderType renderType,
                        float[] pos, float[] uv, float[] normal, int[] color) {

        public int vertexCount() {
            return color.length;
        }
    }

    private final List<Group> groups;
    private final List<VehicleCubeGroup> attachments;
    private final int vertexCount;

    private CamoMesh(List<Group> groups, List<VehicleCubeGroup> attachments) {
        this.groups = groups;
        this.attachments = attachments;
        int total = 0;
        for (Group group : groups) {
            total += group.vertexCount();
        }
        this.vertexCount = total;
    }

    public List<Group> groups() {
        return groups;
    }

    /** The structure group a draw group hangs off, or null for the vehicle's own frame. */
    public VehicleCubeGroup attachment(int index) {
        return index < 0 ? null : attachments.get(index);
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }

    public int vertexCount() {
        return vertexCount;
    }

    /** Resolve a layer into draw groups. Render thread only; it reads the block model bakery. */
    public static CamoMesh bake(AbstractVehicle vehicle, CamoLayer layer) {
        Map<Long, Builder> builders = new LinkedHashMap<>();
        Map<String, VehicleCubeGroup> hullGroups = groupsByName(vehicle);
        List<VehicleCubeGroup> attachments = new ArrayList<>();
        Map<String, Integer> attachmentIds = new HashMap<>();
        Minecraft minecraft = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderer = minecraft.getBlockRenderer();
        BlockColors blockColors = minecraft.getBlockColors();
        RandomSource random = RandomSource.create();
        BlockPos pos = vehicle.blockPosition();

        for (CamoPiece piece : layer.pieces()) {
            String boneName = layer.bone(piece.bone());
            int attachment = -1;
            if (!boneName.isEmpty()) {
                VehicleCubeGroup group = hullGroups.get(boneName);
                // Dropped, not reattached: falling back to root would silently move a whole model's camo to hull origin.
                if (group == null) {
                    continue;
                }
                Integer existing = attachmentIds.get(boneName);
                if (existing == null) {
                    existing = attachments.size();
                    attachments.add(group);
                    attachmentIds.put(boneName, existing);
                }
                attachment = existing;
            }
            BlockState state = layer.material(piece.material());
            if (state.isAir()) {
                continue;
            }
            Matrix4f transform = transformOf(piece);
            Matrix3f normalMatrix = transform.normal(new Matrix3f());
            switch (piece.kind()) {
                case CUBE -> bakeCube(builders, blockRenderer, blockColors, random, vehicle, pos,
                        state, attachment, transform, normalMatrix);
                case PLATE -> bakePlate(builders, blockRenderer, blockColors, random, vehicle, pos,
                        state, attachment, transform, normalMatrix);
            }
        }

        List<Group> groups = new ArrayList<>(builders.size());
        for (Builder builder : builders.values()) {
            Group group = builder.build();
            if (group != null) {
                groups.add(group);
            }
        }
        return new CamoMesh(groups, attachments);
    }

    /** Built per bake, not held on the vehicle: only used here, and bake is already rare (unchanged layer isn't re-resolved). */
    private static Map<String, VehicleCubeGroup> groupsByName(AbstractVehicle vehicle) {
        Map<String, VehicleCubeGroup> byName = new HashMap<>();
        for (VehicleCubeOBB cube : VectorUtil.hullCubes(vehicle)) {
            for (VehicleCubeGroup group = cube.group; group != null; group = group.parent) {
                if (!group.name.isEmpty()) {
                    byName.putIfAbsent(group.name, group);
                }
            }
        }
        return byName;
    }

    /** Move a pose stack into a structure group's frame, the way the group's own cubes are posed. */
    public static void applyAttachment(PoseStack poseStack, VehicleCubeGroup group, float partialTick) {
        VehicleCubeGroup.GlobalTransform transform = group.globalTransform(partialTick);
        poseStack.translate(transform.offset().x, transform.offset().y, transform.offset().z);
        poseStack.mulPose(transform.rotation());
    }

    /**
     * Piece placement in bone space. Rotation order matches {@link com.wf.combinedassault.props.part.DecorationUnit}:
     * Y, X, Z, yaw negated. Piece and decoration at the same hit land identically.
     */
    private static Matrix4f transformOf(CamoPiece piece) {
        return new Matrix4f()
                .translate(piece.x(), piece.y(), piece.z())
                .rotateYXZ((float) Math.toRadians(-piece.yaw()),
                        (float) Math.toRadians(piece.pitch()),
                        (float) Math.toRadians(piece.roll()))
                .scale(piece.scale());
    }

    private static void bakeCube(Map<Long, Builder> builders, BlockRenderDispatcher blockRenderer,
                                 BlockColors blockColors, RandomSource random, AbstractVehicle vehicle,
                                 BlockPos pos, BlockState state, int attachment,
                                 Matrix4f transform, Matrix3f normalMatrix) {
        BakedModel model = blockRenderer.getBlockModel(state);
        // Block models live in [0,1]^3, origin at a corner; centre on the piece.
        Matrix4f local = new Matrix4f(transform).translate(-0.5f, -0.5f, -0.5f + SURFACE_BIAS);
        random.setSeed(MODEL_SEED);
        for (RenderType chunkType : model.getRenderTypes(state, random, ModelData.EMPTY)) {
            Builder builder = builderFor(builders, attachment, sheetFor(chunkType));
            for (Direction face : FACES_AND_GENERAL) {
                random.setSeed(MODEL_SEED);
                for (BakedQuad quad : model.getQuads(state, face, random, ModelData.EMPTY, chunkType)) {
                    int tint = quad.isTinted()
                            ? blockColors.getColor(state, vehicle.level(), pos, quad.getTintIndex())
                            : -1;
                    builder.quad(quad, local, normalMatrix, tint);
                }
            }
        }
    }

    private static void bakePlate(Map<Long, Builder> builders, BlockRenderDispatcher blockRenderer,
                                  BlockColors blockColors, RandomSource random, AbstractVehicle vehicle,
                                  BlockPos pos, BlockState state, int attachment,
                                  Matrix4f transform, Matrix3f normalMatrix) {
        BakedModel model = blockRenderer.getBlockModel(state);
        random.setSeed(MODEL_SEED);
        TextureAtlasSprite sprite = model.getParticleIcon(ModelData.EMPTY);
        // Plate = one square of the block's texture; belongs on the block's own sheet.
        RenderType sheet = CUTOUT_SHEET;
        for (RenderType chunkType : model.getRenderTypes(state, random, ModelData.EMPTY)) {
            sheet = sheetFor(chunkType);
            break;
        }
        int tint = blockColors.getColor(state, vehicle.level(), pos, 0);
        builderFor(builders, attachment, sheet)
                .plate(sprite, new Matrix4f(transform).translate(0, 0, SURFACE_BIAS), normalMatrix, tint);
    }

    /**
     * Chunk format has no overlay element; route to entity sheets (one format, one atlas).
     * No-cull deliberately: a rotated block scrap's winding-based "rear face" isn't the camera-facing one.
     * Solid and cutout share one sheet; alpha-testing opaque costs nothing here.
     */
    private static RenderType sheetFor(RenderType chunkType) {
        if (chunkType == RenderType.translucent() || chunkType == RenderType.tripwire()) {
            return TRANSLUCENT_SHEET;
        }
        return CUTOUT_SHEET;
    }

    private static Builder builderFor(Map<Long, Builder> builders, int attachment, RenderType renderType) {
        long key = ((long) (attachment + 1) << 32) | sheetKey(renderType);
        return builders.computeIfAbsent(key, ignored -> new Builder(attachment, renderType));
    }

    private static int sheetKey(RenderType renderType) {
        return renderType == TRANSLUCENT_SHEET ? 1 : 0;
    }

    /** Slower than the buffered path; kept for Iris's shadow pass, where a raw buffer draw would use the wrong shader. */
    public void emit(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, float partialTick) {
        for (Group group : groups) {
            VertexConsumer consumer = bufferSource.getBuffer(group.renderType());
            poseStack.pushPose();
            try {
                VehicleCubeGroup attachment = attachment(group.attachment());
                if (attachment != null) {
                    applyAttachment(poseStack, attachment, partialTick);
                }
                PoseStack.Pose pose = poseStack.last();
                float[] positions = group.pos();
                float[] uvs = group.uv();
                float[] normals = group.normal();
                int[] colors = group.color();
                for (int v = 0, p = 0, t = 0; v < colors.length; v++, p += 3, t += 2) {
                    consumer.addVertex(pose, positions[p], positions[p + 1], positions[p + 2])
                            .setColor(colors[v])
                            .setUv(uvs[t], uvs[t + 1])
                            .setOverlay(OverlayTexture.NO_OVERLAY)
                            .setLight(packedLight)
                            .setNormal(pose, normals[p], normals[p + 1], normals[p + 2]);
                }
            } finally {
                poseStack.popPose();
            }
        }
    }

    /**
     * Light written here, not held in the group: it's the vehicle's attribute, not the camo's.
     * Light change re-uploads; doesn't rebake.
     *
     * @return null if the group has no vertices; the caller owns the result
     */
    public static MeshData buildMesh(Group group, ByteBufferBuilder scratch, int packedLight) {
        int count = group.vertexCount();
        if (count == 0) {
            return null;
        }
        BufferBuilder builder = new BufferBuilder(scratch, group.renderType().mode(), group.renderType().format());
        float[] positions = group.pos();
        float[] uvs = group.uv();
        float[] normals = group.normal();
        int[] colors = group.color();
        for (int v = 0, p = 0, t = 0; v < count; v++, p += 3, t += 2) {
            builder.addVertex(positions[p], positions[p + 1], positions[p + 2])
                    .setColor(colors[v])
                    .setUv(uvs[t], uvs[t + 1])
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(packedLight)
                    .setNormal(normals[p], normals[p + 1], normals[p + 2]);
        }
        return builder.build();
    }

    private static final class Builder {

        private final int attachment;
        private final RenderType renderType;
        private final FloatArrayList pos = new FloatArrayList();
        private final FloatArrayList uv = new FloatArrayList();
        private final FloatArrayList normal = new FloatArrayList();
        private final IntArrayList color = new IntArrayList();

        private Builder(int attachment, RenderType renderType) {
            this.attachment = attachment;
            this.renderType = renderType;
        }

        void quad(BakedQuad quad, Matrix4f transform, Matrix3f normalMatrix, int tint) {
            int[] data = quad.getVertices();
            int stride = data.length / 4;
            Vector3f faceNormal = new Vector3f(quad.getDirection().getStepX(),
                    quad.getDirection().getStepY(), quad.getDirection().getStepZ());
            normalMatrix.transform(faceNormal).normalize();
            Vector3f scratch = new Vector3f();
            for (int v = 0; v < 4; v++) {
                int base = v * stride;
                scratch.set(Float.intBitsToFloat(data[base]),
                        Float.intBitsToFloat(data[base + 1]),
                        Float.intBitsToFloat(data[base + 2]));
                transform.transformPosition(scratch);
                vertex(scratch, Float.intBitsToFloat(data[base + 4]), Float.intBitsToFloat(data[base + 5]),
                        faceNormal, modulate(data[base + 3], tint));
            }
        }

        void plate(TextureAtlasSprite sprite, Matrix4f transform, Matrix3f normalMatrix, int tint) {
            if (sprite == null) {
                return;
            }
            // No-cull sheet: a second winding would double-draw the same pixels.
            Vector3f front = normalMatrix.transform(new Vector3f(0, 0, 1)).normalize();
            emitQuad(transform, front, modulate(-1, tint), sprite, false);
        }

        private void emitQuad(Matrix4f transform, Vector3f normal, int argb,
                              TextureAtlasSprite sprite, boolean flip) {
            float u0 = sprite.getU0();
            float u1 = sprite.getU1();
            float v0 = sprite.getV0();
            float v1 = sprite.getV1();
            float[] xs = flip ? new float[]{0.5f, 0.5f, -0.5f, -0.5f} : new float[]{-0.5f, -0.5f, 0.5f, 0.5f};
            float[] ys = {-0.5f, 0.5f, 0.5f, -0.5f};
            float[] us = flip ? new float[]{u1, u1, u0, u0} : new float[]{u0, u0, u1, u1};
            float[] vs = {v1, v0, v0, v1};
            Vector3f scratch = new Vector3f();
            for (int i = 0; i < 4; i++) {
                scratch.set(xs[i], ys[i], 0);
                transform.transformPosition(scratch);
                vertex(scratch, us[i], vs[i], normal, argb);
            }
        }

        private void vertex(Vector3f position, float u, float v, Vector3f normalVector, int argb) {
            pos.add(position.x);
            pos.add(position.y);
            pos.add(position.z);
            uv.add(u);
            uv.add(v);
            normal.add(normalVector.x);
            normal.add(normalVector.y);
            normal.add(normalVector.z);
            color.add(argb);
        }

        /** The BLOCK format stores colour as four little-endian bytes, so the low byte is red. */
        private static int modulate(int bakedAbgr, int tintRgb) {
            int r = bakedAbgr & 0xFF;
            int g = (bakedAbgr >> 8) & 0xFF;
            int b = (bakedAbgr >> 16) & 0xFF;
            int a = (bakedAbgr >> 24) & 0xFF;
            if (tintRgb != -1) {
                r = r * ((tintRgb >> 16) & 0xFF) / 255;
                g = g * ((tintRgb >> 8) & 0xFF) / 255;
                b = b * (tintRgb & 0xFF) / 255;
            }
            return FastColor.ARGB32.color(a, r, g, b);
        }

        Group build() {
            if (color.isEmpty()) {
                return null;
            }
            return new Group(attachment, renderType,
                    pos.toFloatArray(), uv.toFloatArray(), normal.toFloatArray(), color.toIntArray());
        }
    }
}
