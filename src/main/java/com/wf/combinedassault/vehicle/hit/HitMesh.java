package com.wf.combinedassault.vehicle.hit;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Display model of one vehicle type as hit geometry, before structure binding ({@link HitModel}). Built on each side
 * from that side's own pack copy: the client traces what it draws, the server stays authoritative for damage.
 *
 * @param source model the geometry came from, for messages
 */
public record HitMesh(String source, List<Node> nodes) {

    /**
     * @param parent    index, or -1
     * @param module    role {@code hit}: not drawn, not the surface; an internal system a shot or blast can reach
     * @param frame     {@link ModelRig} frame the node rides
     * @param component {@code combinedassault.system}: a {@code ComponentKind} name, {@code "none"}, or empty (inherit)
     * @param side      {@code combinedassault.side}: {@code left} / {@code right}, or empty (inherit)
     * @param unit      {@code combinedassault.unit}: own pool within the system kind, or empty (inherit)
     * @param spin      {@code combinedassault.spin}: {@code cw} / {@code ccw} seen from above, or empty (inherit)
     * @param health    pool override, 0 = share of the vehicle's
     * @param origin    rest pose, model frame; geometry is in this node's frame
     * @param mesh      triangles, null for a node with none
     * @param disc      swept volume of a spinning node (rotor), replacing its triangles
     * @param kit       grafted from a kit-scoped part mount: live only while that kit is fitted
     */
    public record Node(String name, int parent, boolean module, int frame, String component, String side,
                       String unit, String spin, float health, Quaternionf rotation, Vector3f origin,
                       @Nullable TriangleBvh mesh, @Nullable Disc disc, @Nullable String kit) {

        /** 6 floats, node frame; inverted when empty. */
        public float[] bounds() {
            if (disc != null) {
                return disc.bounds();
            }
            return mesh != null ? mesh.rootBounds()
                    : new float[]{Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        }
    }

    /** Cylinder about node-frame axis {@code axis} (0 x, 1 y, 2 z) through the origin, {@code lo..hi} along it. */
    public record Disc(int axis, float radius, float lo, float hi) {

        public float[] bounds() {
            float[] b = {-radius, -radius, -radius, radius, radius, radius};
            b[axis] = lo;
            b[axis + 3] = hi;
            return b;
        }
    }
}
