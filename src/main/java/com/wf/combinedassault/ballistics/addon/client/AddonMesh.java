package com.wf.combinedassault.ballistics.addon.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.addon.AddonFit;
import com.wf.combinedassault.ballistics.addon.AddonKind;
import com.wf.combinedassault.ballistics.addon.AddonMount;
import com.wf.combinedassault.ballistics.addon.AddonMountState;

/** One installed panel's geometry, panel frame, built from its state. CPU copy kept for the Iris replay. */
public final class AddonMesh {

    public static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "textures/entity/addon_armor.png");
    public static final RenderType RENDER_TYPE = RenderType.entitySolid(TEXTURE);

    private static final int TRAY = 0xFF3B3F2C;
    private static final int LIGHT_BRICK = 0xFF7F7F55;
    private static final int HEAVY_BRICK = 0xFF55603F;
    private static final int BOLT = 0xFF2A2C24;
    private static final int SCORCH = 0xFF221E1A;
    private static final int SLAT = 0xFF5A6250;
    private static final int SLAT_BENT = 0xFF6E5A44;
    private static final float TRAY_DEPTH = 0.015f;
    private static final float BRICK_GAP = 0.012f;
    private static final float RAIL = 0.035f;
    private static final float STRUT = 0.03f;
    /** Full bend pushes a bar's midspan this far along the normal. */
    private static final float SAG = 0.09f;
    private static final int BAR_SEGMENTS = 6;
    private static final float STUB = 0.18f;
    private static final float UV_PER_BLOCK = 2.0f;

    private final FloatArrayList pos = new FloatArrayList();
    private final FloatArrayList uv = new FloatArrayList();
    private final FloatArrayList normal = new FloatArrayList();
    private final IntArrayList color = new IntArrayList();

    private AddonMesh() {
    }

    public int vertexCount() {
        return color.size();
    }

    public static AddonMesh bake(AddonMount mount, AddonMountState state) {
        AddonMesh mesh = new AddonMesh();
        if (state.fit.kind == AddonKind.ERA) {
            mesh.era(mount, state);
        } else if (state.fit.kind == AddonKind.SLAT) {
            mesh.slat(mount, state);
        }
        return mesh;
    }

    private void era(AddonMount mount, AddonMountState state) {
        float hw = mount.halfWidth;
        float hh = mount.halfHeight;
        float thickness = state.fit.era.thickness();
        box(-hw, -hh, 0, hw, hh, TRAY_DEPTH, TRAY);
        float cw = mount.info.width / mount.columns;
        float ch = mount.info.height / mount.rows;
        int brick = state.fit == AddonFit.ERA_HEAVY ? HEAVY_BRICK : LIGHT_BRICK;
        for (int cell = 0; cell < mount.cells(); cell++) {
            float x0 = -hw + (cell % mount.columns) * cw + BRICK_GAP;
            float y0 = -hh + (cell / mount.columns) * ch + BRICK_GAP;
            float x1 = x0 + cw - 2 * BRICK_GAP;
            float y1 = y0 + ch - 2 * BRICK_GAP;
            if (state.isLive(cell)) {
                box(x0, y0, TRAY_DEPTH, x1, y1, thickness, brick);
                float bolt = Math.min(cw, ch) * 0.08f;
                float inset = bolt * 1.5f;
                box(x0 + inset, y1 - inset - bolt, thickness, x0 + inset + bolt, y1 - inset, thickness + 0.008f, BOLT);
                box(x1 - inset - bolt, y1 - inset - bolt, thickness, x1 - inset, y1 - inset, thickness + 0.008f, BOLT);
            } else {
                // Spent: scorched base plate, torn lips at the rim.
                box(x0, y0, TRAY_DEPTH, x1, y1, TRAY_DEPTH + 0.01f, SCORCH);
                float lip = thickness * 0.4f;
                box(x0, y0, TRAY_DEPTH, x0 + 0.02f, y1, lip, SCORCH);
                box(x1 - 0.02f, y0 + ch * 0.3f, TRAY_DEPTH, x1, y1, lip * 0.7f, SCORCH);
            }
        }
    }

    private void slat(AddonMount mount, AddonMountState state) {
        float hw = mount.halfWidth;
        float hh = mount.halfHeight;
        float d = AddonMount.SLAT_DEPTH / 2;
        box(-hw, hh - RAIL, -d, hw, hh, d, SLAT);
        box(-hw, -hh, -d, hw, -hh + RAIL, d, SLAT);
        box(-hw, -hh, -d, -hw + RAIL, hh, d, SLAT);
        box(hw - RAIL, -hh, -d, hw, hh, d, SLAT);
        float standoff = mount.info.standoff;
        if (standoff > 0) {
            float sx = hw - 0.12f;
            float sy = hh - RAIL - STRUT;
            for (float x : new float[]{-sx, sx - STRUT}) {
                for (float y : new float[]{-sy - STRUT, sy}) {
                    box(x, y, -standoff, x + STRUT, y + STRUT, -d, SLAT);
                }
            }
        }
        float half = AddonMount.SLAT_BAR / 2;
        float span = 2 * (hw - RAIL);
        for (int k = 0; k < mount.bars; k++) {
            float health = state.bars[k];
            float y = mount.barY(k);
            if (y - half < -hh + RAIL || y + half > hh - RAIL) {
                continue;
            }
            if (health <= 0) {
                // Shot away: two drooping stubs off the side rails.
                float stub = span * STUB;
                box(-hw + RAIL, y - half - 0.01f, -d, -hw + RAIL + stub, y + half - 0.01f, d, SLAT_BENT);
                box(hw - RAIL - stub, y - half - 0.02f, -d, hw - RAIL, y + half - 0.02f, d, SLAT_BENT);
                continue;
            }
            float bend = 1 - health;
            int colour = health < AddonMountState.BENT ? lerp(SLAT, SLAT_BENT, bend) : SLAT;
            for (int s = 0; s < BAR_SEGMENTS; s++) {
                float x0 = -hw + RAIL + span * s / BAR_SEGMENTS;
                float x1 = -hw + RAIL + span * (s + 1) / BAR_SEGMENTS;
                float mid = (s + 0.5f) / BAR_SEGMENTS;
                float sag = -SAG * bend * Mth.sin(mid * Mth.PI);
                box(x0, y - half, -d + sag, x1, y + half, d + sag, colour);
            }
        }
    }

    private static int lerp(int a, int b, float t) {
        int r = (int) Mth.lerp(t, a >> 16 & 0xFF, b >> 16 & 0xFF);
        int g = (int) Mth.lerp(t, a >> 8 & 0xFF, b >> 8 & 0xFF);
        int bl = (int) Mth.lerp(t, a & 0xFF, b & 0xFF);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    private void box(float x0, float y0, float z0, float x1, float y1, float z1, int argb) {
        quad(argb, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        quad(argb, 0, 0, -1, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
        quad(argb, 1, 0, 0, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
        quad(argb, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(argb, 0, 1, 0, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
        quad(argb, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
    }

    /** Counter-clockwise seen from outside. Texture repeats (no clamp mcmeta). */
    private void quad(int argb, float nx, float ny, float nz, float... v) {
        for (int i = 0; i < 12; i += 3) {
            pos.add(v[i]);
            pos.add(v[i + 1]);
            pos.add(v[i + 2]);
            float a = nx != 0 ? v[i + 2] : v[i];
            float b = ny != 0 ? v[i + 2] : v[i + 1];
            uv.add(a * UV_PER_BLOCK);
            uv.add(b * UV_PER_BLOCK);
            normal.add(nx);
            normal.add(ny);
            normal.add(nz);
            color.add(argb);
        }
    }

    @Nullable
    public MeshData build(ByteBufferBuilder scratch, int packedLight) {
        if (vertexCount() == 0) {
            return null;
        }
        BufferBuilder builder = new BufferBuilder(scratch, RENDER_TYPE.mode(), RENDER_TYPE.format());
        emitInto(builder, null, packedLight);
        return builder.build();
    }

    /** Replay path: into a buffer source through {@code pose}. */
    public void emit(PoseStack.Pose pose, VertexConsumer consumer, int packedLight) {
        emitInto(consumer, pose, packedLight);
    }

    private void emitInto(VertexConsumer consumer, @Nullable PoseStack.Pose pose, int packedLight) {
        for (int v = 0, p = 0, t = 0; v < color.size(); v++, p += 3, t += 2) {
            if (pose == null) {
                consumer.addVertex(pos.getFloat(p), pos.getFloat(p + 1), pos.getFloat(p + 2));
            } else {
                consumer.addVertex(pose, pos.getFloat(p), pos.getFloat(p + 1), pos.getFloat(p + 2));
            }
            consumer.setColor(color.getInt(v))
                    .setUv(uv.getFloat(t), uv.getFloat(t + 1))
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(packedLight);
            if (pose == null) {
                consumer.setNormal(normal.getFloat(p), normal.getFloat(p + 1), normal.getFloat(p + 2));
            } else {
                consumer.setNormal(pose, normal.getFloat(p), normal.getFloat(p + 1), normal.getFloat(p + 2));
            }
        }
    }
}
