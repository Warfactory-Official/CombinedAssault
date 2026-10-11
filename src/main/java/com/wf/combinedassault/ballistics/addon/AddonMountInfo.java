package com.wf.combinedassault.ballistics.addon;

import com.google.gson.annotations.SerializedName;
import net.minecraft.world.phys.Vec3;

/**
 * One {@code addon_armor} entry: a flat panel in the vehicle-local frame (blocks, +X port, +Z forward),
 * carried by a structure bone.
 */
public class AddonMountInfo {

    @SerializedName("id")
    public String id = "";

    @SerializedName("kind")
    public AddonKind kind = AddonKind.ERA;

    /** Structure-model bone; "" = hull frame. */
    @SerializedName("bone")
    public String bone = "";

    @SerializedName("center")
    public Vec3 center = Vec3.ZERO;

    /** Outward face normal. */
    @SerializedName("normal")
    public Vec3 normal = new Vec3(0, 0, 1);

    /** Projected onto the panel plane; slat bars run perpendicular to it. */
    @SerializedName("up")
    public Vec3 up = new Vec3(0, 1, 0);

    @SerializedName("width")
    public float width = 1.0f;

    @SerializedName("height")
    public float height = 0.5f;

    /** ERA grid {@code [columns, rows]}; absent = derived from {@link AddonMount#ERA_CELL_WIDTH}/{@code HEIGHT}. */
    @SerializedName("cells")
    public int[] cells = null;

    /** Slat: bracket length back to the hull, drawn only; the physics measures the real gap. */
    @SerializedName("standoff")
    public float standoff = 0.0f;

    /** Baked from the display mesh by {@code tools/bake_addon_armor.py}; replaces center/normal/up when present. */
    @SerializedName("surface")
    public Surface surface = null;

    public static class Surface {
        @SerializedName("center")
        public Vec3 center;
        @SerializedName("normal")
        public Vec3 normal;
        @SerializedName("up")
        public Vec3 up;
        /** Max hit distance off the fitted plane, blocks: a large value = panel spans a bend. */
        @SerializedName("residual")
        public float residual;
    }

    public Vec3 center() {
        return surface != null ? surface.center : center;
    }

    public Vec3 normal() {
        return surface != null ? surface.normal : normal;
    }

    public Vec3 up() {
        return surface != null ? surface.up : up;
    }
}
