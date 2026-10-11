package com.wf.combinedassault.vehicle.hit;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartClipTracks;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.structure.VehicleCubeGroup;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * One vehicle's hit geometry: {@link HitModel} on this vehicle's group poses, posed per query; clip-driven nodes
 * re-posed per tick when a part's progress moves. Surface = drawn nodes (triangles; rotors as discs); modules =
 * {@code hit_*} nodes inside it.
 */
public final class VehicleHits {

    /** How far behind the struck surface an authored tagged piece still claims the hit: drawn inside the skin. */
    public static final double AUTHORED_DEPTH = 1.0;

    /**
     * @param fraction along the traced segment
     * @param normal   world, facing the shot
     * @param armor    mm RHA from the face's {@code armor_<mm>} material; 0 = none
     */
    public record Hit(int node, double fraction, Vec3 point, Vector3f normal, float armor, SurfaceMaterial material) {
    }

    private final AbstractVehicle vehicle;
    private final HitModel model;
    /** Per model group slot: this vehicle's group (part groups are per-vehicle copies). */
    private final VehicleCubeGroup[] groups;
    private final int[] groupIndex;
    /** Per node: owning part, or null. */
    private final PartUnit<?>[] parts;
    private final float[] scratch = new float[3];
    /** Per node: current frame in its group's rest frame (clips applied). */
    private final Quaternionf[] toGroup;
    private final Vector3f[] offset;
    @Nullable
    private final PartClipTracks clips;
    private Matrix4f[] clipWorld;
    private boolean[] clipMoved;
    private int posedVersion = -1;

    public VehicleHits(AbstractVehicle vehicle, HitModel model, Map<VehicleCubeGroup, VehicleCubeGroup> copies,
                       Map<String, PartUnit<?>> partsById, @Nullable PartClipTracks clips) {
        this.vehicle = vehicle;
        this.model = model;
        List<VehicleCubeGroup> typeGroups = model.groups();
        groups = new VehicleCubeGroup[typeGroups.size()];
        for (int i = 0; i < groups.length; i++) {
            groups[i] = copies.getOrDefault(typeGroups.get(i), typeGroups.get(i));
        }
        groupIndex = new int[groups.length];
        parts = new PartUnit<?>[model.nodes().size()];
        for (int i = 0; i < parts.length; i++) {
            String part = model.nodes().get(i).part();
            parts[i] = part == null ? null : partsById.get(part);
        }
        toGroup = new Quaternionf[parts.length];
        offset = new Vector3f[parts.length];
        for (int i = 0; i < parts.length; i++) {
            toGroup[i] = new Quaternionf(model.nodes().get(i).toGroup());
            offset[i] = new Vector3f(model.nodes().get(i).offset());
        }
        this.clips = clips;
        refreshClips();
    }

    /** Re-poses clip-driven nodes when a part's ticked progress moved. */
    public void refreshClips() {
        if (clips == null || clips.version() == posedVersion) {
            return;
        }
        posedVersion = clips.version();
        ClipRig rig = clips.rig();
        if (rig.nodeCount() != parts.length) {
            throw new IllegalStateException(model.mesh().source() + ": clip rig has " + rig.nodeCount()
                    + " nodes, hit mesh " + parts.length);
        }
        if (clipWorld == null) {
            clipWorld = new Matrix4f[parts.length];
            clipMoved = new boolean[parts.length];
        }
        rig.pose(clips.clipTimes(1), clipWorld, clipMoved);
        Vector3f origin = new Vector3f();
        Quaternionf rotation = new Quaternionf();
        for (int i = 0; i < parts.length; i++) {
            HitModel.Node node = model.nodes().get(i);
            if (!clipMoved[i]) {
                toGroup[i].set(node.toGroup());
                offset[i].set(node.offset());
                continue;
            }
            clipWorld[i].getTranslation(origin);
            clipWorld[i].getUnnormalizedRotation(rotation).normalize();
            model.toGroup(node.group(), origin, rotation, toGroup[i], offset[i]);
        }
    }

    public HitModel model() {
        return model;
    }

    public VehicleCubeGroup[] groups() {
        return groups;
    }

    /** Slot -> {@code AbstractVehicle} group index; set whenever that index is rebuilt. */
    public void index(Map<VehicleCubeGroup, Integer> indexOf) {
        for (int i = 0; i < groups.length; i++) {
            groupIndex[i] = indexOf.get(groups[i]);
        }
    }

    @Nullable
    public PartUnit<?> part(int node) {
        return parts[node];
    }

    /** A node that rides a detached part is that part entity's business; a weapon mount's, only while selected. */
    public boolean live(int node) {
        if (parts[node] == null) {
            return true;
        }
        String weapon = model.nodes().get(node).mesh().weapon();
        return !parts[node].isDetached() && (weapon == null || parts[node] instanceof WeaponUnit unit
                && unit.currentWeaponId() != null && weapon.equals(unit.currentWeaponId().toString()));
    }

    /** Nearest live surface (drawn) node along {@code from -> to}, or null. */
    @Nullable
    public Hit surface(Vec3 from, Vec3 to) {
        return trace(from, to, i -> live(i) && !model.nodes().get(i).mesh().module());
    }

    /** Nearest live {@code hit_*} module along {@code from -> to}, or null. */
    @Nullable
    public Hit module(Vec3 from, Vec3 to) {
        return trace(from, to, i -> live(i) && model.nodes().get(i).mesh().module());
    }

    @Nullable
    public Hit trace(Vec3 from, Vec3 to, IntPredicate nodes) {
        Quaternionf vehicleInverse = new Quaternionf(vehicle.rotYXZ()).conjugate();
        Vec3 c = vehicle.centerOffset;
        double bx = vehicle.getX() + c.x, by = vehicle.getY() + c.y, bz = vehicle.getZ() + c.z;
        // World -> vehicle frame in doubles first: world coordinates cancel.
        Vector3f o = vehicleInverse.transform(new Vector3f((float) (from.x - bx), (float) (from.y - by), (float) (from.z - bz)))
                .add((float) c.x, (float) c.y, (float) c.z);
        Vector3f d = vehicleInverse.transform(new Vector3f((float) (to.x - from.x), (float) (to.y - from.y),
                (float) (to.z - from.z)));

        double best = 1;
        int bestNode = -1;
        float bestArmor = 0;
        SurfaceMaterial bestMaterial = SurfaceMaterial.STEEL;
        Vector3f bestNormal = new Vector3f();
        Quaternionf[] groupInverse = new Quaternionf[groups.length];
        Vector3f[] groupO = new Vector3f[groups.length];
        Vector3f[] groupD = new Vector3f[groups.length];
        Vector3f no = new Vector3f();
        Vector3f nd = new Vector3f();
        float[] t = new float[1];
        List<HitModel.Node> all = model.nodes();
        for (int i = 0; i < all.size(); i++) {
            if (!nodes.test(i)) {
                continue;
            }
            HitModel.Node node = all.get(i);
            int g = node.group();
            if (groupInverse[g] == null) {
                int index = groupIndex[g];
                groupInverse[g] = new Quaternionf(vehicle.groupRotation(index)).conjugate();
                groupO[g] = groupInverse[g].transform(new Vector3f(o).sub(vehicle.groupOffset(index)));
                groupD[g] = groupInverse[g].transform(new Vector3f(d));
            }
            Quaternionf inverse = new Quaternionf(toGroup[i]).conjugate();
            inverse.transform(no.set(groupO[g]).sub(offset[i]));
            inverse.transform(nd.set(groupD[g]));
            if (slab(node.bounds(), no, nd, best) < 0) {
                continue;
            }
            TriangleBvh mesh = node.mesh().mesh();
            HitMesh.Disc disc = node.mesh().disc();
            if (mesh != null) {
                int triangle = mesh.trace(no.x, no.y, no.z, nd.x, nd.y, nd.z, (float) best, t);
                if (triangle >= 0) {
                    best = t[0];
                    bestNode = i;
                    mesh.normal(triangle, scratch);
                    bestNormal.set(scratch[0], scratch[1], scratch[2]);
                    bestArmor = mesh.armor(triangle);
                    bestMaterial = mesh.material(triangle);
                    toWorld(bestNormal, i, g);
                }
            } else if (disc != null) {
                double hit = disc(disc, no, nd, best, bestNormal);
                if (hit >= 0) {
                    best = hit;
                    bestNode = i;
                    bestArmor = 0;
                    bestMaterial = SurfaceMaterial.STEEL;
                    toWorld(bestNormal, i, g);
                }
            }
        }
        if (bestNode < 0) {
            return null;
        }
        Vector3f dir = new Vector3f((float) (to.x - from.x), (float) (to.y - from.y), (float) (to.z - from.z));
        if (bestNormal.dot(dir) > 0) {
            bestNormal.negate();
        }
        return new Hit(bestNode, best, from.add(to.subtract(from).scale(best)), bestNormal, bestArmor, bestMaterial);
    }

    /** Entry fraction into the cylinder (inside => 0), normal into {@code normal}; -1 when missed before {@code limit}. */
    private static double disc(HitMesh.Disc disc, Vector3f o, Vector3f d, double limit, Vector3f normal) {
        int a = disc.axis();
        int u = (a + 1) % 3;
        int w = (a + 2) % 3;
        double oa = o.get(a), da = d.get(a);
        double near = 0;
        double far = limit;
        boolean capEntry = false;
        if (Math.abs(da) < 1.0e-12) {
            if (oa < disc.lo() || oa > disc.hi()) {
                return -1;
            }
        } else {
            double t0 = (disc.lo() - oa) / da;
            double t1 = (disc.hi() - oa) / da;
            if (t0 > t1) {
                double swap = t0;
                t0 = t1;
                t1 = swap;
            }
            if (t0 > near) {
                near = t0;
                capEntry = true;
            }
            far = Math.min(far, t1);
        }
        double ou = o.get(u), ow = o.get(w), du = d.get(u), dw = d.get(w);
        double qa = du * du + dw * dw;
        double qb = 2 * (ou * du + ow * dw);
        double qc = ou * ou + ow * ow - (double) disc.radius() * disc.radius();
        if (qa < 1.0e-12) {
            if (qc > 0) {
                return -1;
            }
        } else {
            double root = qb * qb - 4 * qa * qc;
            if (root < 0) {
                return -1;
            }
            root = Math.sqrt(root);
            double t0 = (-qb - root) / (2 * qa);
            double t1 = (-qb + root) / (2 * qa);
            if (t0 > near) {
                near = t0;
                capEntry = false;
            }
            far = Math.min(far, t1);
        }
        if (near > far) {
            return -1;
        }
        normal.set(0, 0, 0);
        if (capEntry) {
            normal.setComponent(a, da > 0 ? -1 : 1);
        } else {
            normal.setComponent(u, (float) (ou + du * near));
            normal.setComponent(w, (float) (ow + dw * near));
            if (normal.lengthSquared() < 1.0e-12f) {
                normal.setComponent(a, 1);
            }
            normal.normalize();
        }
        return near;
    }

    private void toWorld(Vector3f normal, int node, int slot) {
        toGroup[node].transform(normal);
        vehicle.groupRotation(groupIndex[slot]).transform(normal);
        vehicle.rotYXZ().transform(normal);
    }

    /** Entry fraction into bounds {@code minX..maxZ} (origin inside => 0), or -1 when missed before {@code limit}. */
    private static double slab(float[] box, Vector3f o, Vector3f d, double limit) {
        double near = 0;
        double far = limit;
        for (int a = 0; a < 3; a++) {
            double origin = o.get(a);
            double dir = d.get(a);
            double lo = box[a];
            double hi = box[3 + a];
            if (Math.abs(dir) < 1.0e-12) {
                if (origin < lo || origin > hi) {
                    return -1;
                }
                continue;
            }
            double t0 = (lo - origin) / dir;
            double t1 = (hi - origin) / dir;
            if (t0 > t1) {
                double swap = t0;
                t0 = t1;
                t1 = swap;
            }
            near = Math.max(near, t0);
            far = Math.min(far, t1);
            if (near > far) {
                return -1;
            }
        }
        return near;
    }

    /** Grows pivot-relative vehicle-frame bounds {@code minX..maxZ} (as {@code updateOBBs} keeps them) by every live node. */
    public void extendBounds(double[] bounds) {
        if (groupBounds == null || boundsStale()) {
            cacheGroupBounds();
        }
        Vec3 c = vehicle.centerOffset;
        Vector3f corner = new Vector3f();
        for (int g = 0; g < groups.length; g++) {
            int o = g * 6;
            if (groupBounds[o] > groupBounds[o + 3]) {
                continue;
            }
            int index = groupIndex[g];
            Quaternionf rotation = vehicle.groupRotation(index);
            Vector3f groupOffset = vehicle.groupOffset(index);
            for (int k = 0; k < 8; k++) {
                corner.set(groupBounds[o + ((k & 1) == 0 ? 0 : 3)], groupBounds[o + ((k & 2) == 0 ? 1 : 4)],
                        groupBounds[o + ((k & 4) == 0 ? 2 : 5)]);
                rotation.transform(corner).add(groupOffset).sub((float) c.x, (float) c.y, (float) c.z);
                bounds[0] = Math.min(bounds[0], corner.x);
                bounds[1] = Math.min(bounds[1], corner.y);
                bounds[2] = Math.min(bounds[2], corner.z);
                bounds[3] = Math.max(bounds[3], corner.x);
                bounds[4] = Math.max(bounds[4], corner.y);
                bounds[5] = Math.max(bounds[5], corner.z);
            }
        }
    }

    /** Live-node bounds per model group, group frame; rotated group => AABB of its box (superset), unrotated exact. */
    private float[] groupBounds;
    /** Liveness inputs at the last cache: distinct parts' detached flag and weapon, clip pose version. */
    private PartUnit<?>[] keyParts;
    private boolean[] keyDetached;
    private ResourceLocation[] keyWeapon;
    private int keyClips;

    /** {@link #extendBounds} would reuse its cache: same live nodes, same clip poses. */
    public boolean boundsCurrent() {
        return groupBounds != null && !boundsStale();
    }

    private boolean boundsStale() {
        if (keyClips != posedVersion) {
            return true;
        }
        for (int i = 0; i < keyParts.length; i++) {
            PartUnit<?> part = keyParts[i];
            if (part.isDetached() != keyDetached[i]
                    || part instanceof WeaponUnit unit && unit.currentWeaponId() != keyWeapon[i]) {
                return true;
            }
        }
        return false;
    }

    private void cacheGroupBounds() {
        groupBounds = new float[groups.length * 6];
        for (int g = 0; g < groups.length; g++) {
            groupBounds[g * 6] = groupBounds[g * 6 + 1] = groupBounds[g * 6 + 2] = Float.MAX_VALUE;
            groupBounds[g * 6 + 3] = groupBounds[g * 6 + 4] = groupBounds[g * 6 + 5] = -Float.MAX_VALUE;
        }
        Vector3f corner = new Vector3f();
        List<HitModel.Node> all = model.nodes();
        for (int i = 0; i < all.size(); i++) {
            HitModel.Node node = all.get(i);
            float[] b = node.bounds();
            if (!live(i) || b[0] > b[3]) {
                continue;
            }
            int o = node.group() * 6;
            for (int k = 0; k < 8; k++) {
                corner.set((k & 1) == 0 ? b[0] : b[3], (k & 2) == 0 ? b[1] : b[4], (k & 4) == 0 ? b[2] : b[5]);
                toGroup[i].transform(corner).add(offset[i]);
                groupBounds[o] = Math.min(groupBounds[o], corner.x);
                groupBounds[o + 1] = Math.min(groupBounds[o + 1], corner.y);
                groupBounds[o + 2] = Math.min(groupBounds[o + 2], corner.z);
                groupBounds[o + 3] = Math.max(groupBounds[o + 3], corner.x);
                groupBounds[o + 4] = Math.max(groupBounds[o + 4], corner.y);
                groupBounds[o + 5] = Math.max(groupBounds[o + 5], corner.z);
            }
        }
        keyParts = Arrays.stream(parts).filter(Objects::nonNull).distinct().toArray(PartUnit<?>[]::new);
        keyDetached = new boolean[keyParts.length];
        keyWeapon = new ResourceLocation[keyParts.length];
        for (int i = 0; i < keyParts.length; i++) {
            keyDetached[i] = keyParts[i].isDetached();
            keyWeapon[i] = keyParts[i] instanceof WeaponUnit unit ? unit.currentWeaponId() : null;
        }
        keyClips = posedVersion;
    }

    /** World centre of a node's bounds at its current pose. */
    public Vec3 centre(int node) {
        float[] b = model.nodes().get(node).bounds();
        Vec3 at = vehicle.position(); // relative, then back to doubles: world coords overflow a float's precision
        Vector3f c = pose(node, at, new Matrix4f())
                .transformPosition(new Vector3f((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2));
        return at.add(c.x, c.y, c.z);
    }

    /** World centre of the AABB of {@code part}'s live drawn nodes at their current pose; null if none has a mesh. */
    @Nullable
    public Vec3 centre(PartUnit<?> part) {
        return centre(i -> parts[i] == part && !model.nodes().get(i).mesh().module());
    }

    /** World centre of the AABB of the matching live nodes with geometry, current pose; null if none. */
    @Nullable
    public Vec3 centre(IntPredicate nodes) {
        Vec3 at = vehicle.position();
        Matrix4f pose = new Matrix4f();
        Vector3f corner = new Vector3f();
        float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < parts.length; i++) {
            float[] b = model.nodes().get(i).bounds();
            if (b[0] > b[3] || !live(i) || !nodes.test(i)) {
                continue;
            }
            pose(i, at, pose);
            for (int k = 0; k < 8; k++) {
                pose.transformPosition(corner.set((k & 1) == 0 ? b[0] : b[3], (k & 2) == 0 ? b[1] : b[4],
                        (k & 4) == 0 ? b[2] : b[5]));
                for (int a = 0; a < 3; a++) {
                    box[a] = Math.min(box[a], corner.get(a));
                    box[a + 3] = Math.max(box[a + 3], corner.get(a));
                }
            }
        }
        return box[0] > box[3] ? null
                : at.add((box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2);
    }

    /**
     * Node frame -> world for drawing, relative to {@code view}: {@code out = T(vehicle) R(vehicle) T(group) R(group)
     * T(offset) R(toGroup)}.
     */
    public Matrix4f pose(int node, Vec3 view, Matrix4f out) {
        HitModel.Node n = model.nodes().get(node);
        int index = groupIndex[n.group()];
        Vec3 c = vehicle.centerOffset;
        return out.translation((float) (vehicle.getX() + c.x - view.x), (float) (vehicle.getY() + c.y - view.y),
                        (float) (vehicle.getZ() + c.z - view.z))
                .rotate(vehicle.rotYXZ())
                .translate((float) -c.x, (float) -c.y, (float) -c.z)
                .translate(vehicle.groupOffset(index))
                .rotate(vehicle.groupRotation(index))
                .translate(offset[node])
                .rotate(toGroup[node]);
    }
}
