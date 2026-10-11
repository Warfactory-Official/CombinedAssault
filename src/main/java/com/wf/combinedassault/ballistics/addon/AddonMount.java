package com.wf.combinedassault.ballistics.addon;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.vehicle.structure.VehicleCubeGroup;

/**
 * A mount point resolved into its bone's rest frame.
 *
 * <p>Panel frame: +X across (width), +Y along {@code up} (height), +Z out of the hull. ERA occupies
 * {@code z in [0, thickness]} (centre = hull face); slat bars lie in {@code z = 0} (centre = bar plane).
 */
public final class AddonMount {

    public static final float ERA_CELL_WIDTH = 0.40f;
    public static final float ERA_CELL_HEIGHT = 0.25f;
    /** Bar centre spacing, blocks (= m). Tuned so an 85-93 mm RPG body cannot pass a gap. */
    public static final float SLAT_PITCH = 0.09f;
    /** Bar section across the gap. */
    public static final float SLAT_BAR = 0.015f;
    /** Bar section along the panel normal. */
    public static final float SLAT_DEPTH = 0.05f;
    /** Cells per mount are a {@code long} bitmask. */
    public static final int MAX_CELLS = 64;

    public final int index;
    public final AddonMountInfo info;
    /** Panel -> bone rest frame. */
    public final float cx;
    public final float cy;
    public final float cz;
    public final Quaternionf rotation;
    public final float halfWidth;
    public final float halfHeight;
    public final int columns;
    public final int rows;
    public final int bars;

    private AddonMount(int index, AddonMountInfo info, Vector3f centre, Quaternionf rotation,
                       int columns, int rows, int bars) {
        this.index = index;
        this.info = info;
        this.cx = centre.x;
        this.cy = centre.y;
        this.cz = centre.z;
        this.rotation = rotation;
        this.halfWidth = info.width / 2;
        this.halfHeight = info.height / 2;
        this.columns = columns;
        this.rows = rows;
        this.bars = bars;
    }

    /**
     * @param rest the bone's group at rest, or null for the hull frame
     * @throws IllegalArgumentException on a degenerate or oversized panel
     */
    public static AddonMount resolve(int index, AddonMountInfo info, @Nullable VehicleCubeGroup rest) {
        Vector3f n = info.normal().toVector3f();
        if (n.lengthSquared() < 1.0e-8f || info.width <= 0 || info.height <= 0) {
            throw new IllegalArgumentException("addon mount '" + info.id + "': zero normal or size");
        }
        n.normalize();
        Vector3f v = info.up().toVector3f();
        v.sub(new Vector3f(n).mul(v.dot(n)));
        if (v.lengthSquared() < 1.0e-8f) {
            throw new IllegalArgumentException("addon mount '" + info.id + "': up is parallel to normal");
        }
        v.normalize();
        Vector3f u = new Vector3f(v).cross(n);
        Quaternionf panel = new Quaternionf().setFromNormalized(new Matrix3f(u, v, n));
        Vector3f centre = info.center().toVector3f();
        if (rest != null) {
            VehicleCubeGroup.GlobalTransform at = rest.globalTransform();
            Quaternionf inverse = new Quaternionf(at.rotation()).conjugate();
            centre.sub(at.offset().toVector3f());
            inverse.transform(centre);
            panel.premul(inverse);
        }
        int columns = 0;
        int rows = 0;
        int bars = 0;
        if (info.kind == AddonKind.ERA) {
            columns = info.cells != null ? info.cells[0] : Math.max(1, Math.round(info.width / ERA_CELL_WIDTH));
            rows = info.cells != null ? info.cells[1] : Math.max(1, Math.round(info.height / ERA_CELL_HEIGHT));
            if (columns < 1 || rows < 1 || columns * rows > MAX_CELLS) {
                throw new IllegalArgumentException("addon mount '" + info.id + "': " + columns + "x" + rows
                        + " cells, need 1.." + MAX_CELLS);
            }
        } else {
            bars = Math.max(2, Mth.floor(info.height / SLAT_PITCH));
        }
        return new AddonMount(index, info, centre, panel, columns, rows, bars);
    }

    public int cells() {
        return columns * rows;
    }

    /** Bar k centre, panel Y. */
    public float barY(int k) {
        return -halfHeight + (k + 0.5f) * (info.height / bars);
    }

    public float barPitch() {
        return info.height / bars;
    }

    /** Nearest bar to panel Y. */
    public int barAt(float y) {
        return Mth.clamp(Mth.floor((y + halfHeight) / barPitch()), 0, bars - 1);
    }

    /** Cell under panel (x, y). */
    public int cellAt(float x, float y) {
        int column = Mth.clamp(Mth.floor((x + halfWidth) / (info.width / columns)), 0, columns - 1);
        int row = Mth.clamp(Mth.floor((y + halfHeight) / (info.height / rows)), 0, rows - 1);
        return row * columns + column;
    }

    /** Distance from (x, y) to the nearest edge of its cell, as a fraction of the smaller cell side. */
    public float cellEdgeFraction(float x, float y) {
        float cw = info.width / columns;
        float ch = info.height / rows;
        float fx = (x + halfWidth) - Mth.floor((x + halfWidth) / cw) * cw;
        float fy = (y + halfHeight) - Mth.floor((y + halfHeight) / ch) * ch;
        float edge = Math.min(Math.min(fx, cw - fx), Math.min(fy, ch - fy));
        return edge / Math.min(cw, ch);
    }

    /** Cell centre, panel frame (z = 0). */
    public Vec3 cellCentre(int cell) {
        float cw = info.width / columns;
        float ch = info.height / rows;
        int column = cell % columns;
        int row = cell / columns;
        return new Vec3(-halfWidth + (column + 0.5) * cw, -halfHeight + (row + 0.5) * ch, 0);
    }

    /** Units of {@code fit}'s item a full install consumes. */
    public int installCost(AddonFit fit) {
        return fit.kind == AddonKind.ERA ? cells() : slatCost();
    }

    /** One slat panel item per started square block. */
    public int slatCost() {
        return Math.max(1, Mth.ceil(info.width * info.height - 1.0e-3f));
    }

    /** Sphere about the panel centre holding its box for any fit. */
    public double boundingRadius() {
        float depth = Math.max(EraSpec.HEAVY.thickness(), SLAT_DEPTH);
        return Math.sqrt(halfWidth * halfWidth + halfHeight * halfHeight + depth * depth);
    }

    /** Box the trace tests, panel frame: {@code [zMin, zMax]} along the normal. */
    public float zMin(AddonFit fit) {
        return fit.kind == AddonKind.ERA ? 0.0f : -SLAT_DEPTH / 2;
    }

    public float zMax(AddonFit fit) {
        return fit.kind == AddonKind.ERA ? fit.era.thickness() : SLAT_DEPTH / 2;
    }
}
