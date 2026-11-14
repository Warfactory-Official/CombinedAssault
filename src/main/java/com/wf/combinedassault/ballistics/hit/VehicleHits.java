package com.wf.combinedassault.ballistics.hit;

import com.wf.combinedassault.dynamics.part.PartClipTracks;
import com.wf.combinedassault.dynamics.structure.VehicleCubeGroup;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.wflib.armor.api.hull.HullMesh;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
public final class VehicleHits implements HullMesh {

    /** Drawn surface first met eye -> point more than this short of it, m => hidden. Seats sit ~1 m inside the near side. */
    public static final double SIGHT_DEPTH = 1.5;

    /** Probe line of sight: eye -> anchor this close => reached through the model (obstructed handle). */
    public static final double PROBE_CLOSE = 1.0;
    /** Probe line of sight: blocker this far short of the anchor, m, tolerated (anchor on a surface). */
    public static final double PROBE_SLACK = 0.05;
    public static final IntPredicate NONE = i -> false;

    /** How far behind the struck surface an authored tagged piece still claims the hit: drawn inside the skin. */
    public static final double AUTHORED_DEPTH = 1.0;

    /**
     * @param fraction along the traced segment
     * @param normal   world, facing the shot
     * @param plate    id from the face's {@code armor:<id>} material, null = unbound
     */
    public record Hit(int node, double fraction, Vec3 point, Vector3f normal, @Nullable String plate,
                      SurfaceMaterial material) implements HullMesh.Strike {
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
    /** Per model group slot: frame node a clip drives that group by, else -1 ({@link ClipFrames}). */
    private final int[] groupNode;
    private final Matrix4f relative = new Matrix4f();
    private Matrix4f[] clipWorld;
    private boolean[] clipMoved;
    private int posedVersion = -1;

    public VehicleHits(AbstractVehicle vehicle, HitModel model, Map<VehicleCubeGroup, VehicleCubeGroup> copies,
                       Map<String, PartUnit<?>> partsById, @Nullable PartClipTracks clips,
                       @Nullable com.wf.combinedassault.dynamics.part.ClipFrames clipFrames) {
        this.vehicle = vehicle;
        this.model = model;
        List<VehicleCubeGroup> typeGroups = model.groups();
        groups = new VehicleCubeGroup[typeGroups.size()];
        for (int i = 0; i < groups.length; i++) {
            groups[i] = copies.getOrDefault(typeGroups.get(i), typeGroups.get(i));
        }
        groupIndex = new int[groups.length];
        groupNode = new int[groups.length];
        for (int i = 0; i < groups.length; i++) {
            groupNode[i] = clipFrames == null ? -1 : clipFrames.nodeOf(groups[i]);
        }
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
            int frame = groupNode[node.group()];
            if (frame >= 0 && clipMoved[frame]) {
                // The group already follows the clip: the node's pose in its frame.
                relative.set(clipWorld[frame]).invert().mul(clipWorld[i]);
                relative.getTranslation(offset[i]);
                relative.getUnnormalizedRotation(toGroup[i]).normalize();
                continue;
            }
            clipWorld[i].getTranslation(origin);
            clipWorld[i].getUnnormalizedRotation(rotation).normalize();
            model.toGroup(node.group(), origin, rotation, toGroup[i], offset[i]);
        }
    }

    @Override
    @Nullable
    public String partOf(int node) {
        return model().nodes().get(node).part();
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

    /** A node that rides a detached part is that part entity's business; a kit mount's, only while fitted. */
    public boolean live(int node) {
        if (parts[node] == null) {
            return true;
        }
        String kit = model.nodes().get(node).mesh().kit();
        return !parts[node].isDetached() && (kit == null || parts[node] instanceof WeaponUnit unit
                && unit.kit() != null && kit.equals(unit.kit().toString()));
    }

    /** {@code at} further than {@link #SIGHT_DEPTH} behind the first hull surface on {@code eye -> at}. */
    public boolean hidden(Vec3 eye, Vec3 at) {
        Hit hit = surface(eye, at);
        return hit != null && (1 - hit.fraction()) * eye.distanceTo(at) > SIGHT_DEPTH;
    }

    /** {@code part}'s hit centre, else its pivot. */
    public static Vec3 anchor(@Nullable VehicleHits hits, PartUnit<?> part) {
        Vec3 centre = hits == null ? null : hits.centre(part);
        return centre != null ? centre : part.worldPivotPosition();
    }

    /** World origin of {@code node} at its current pose (clips applied). */
    public Vec3 origin(int node) {
        Vec3 at = vehicle.position();
        Vector3f o = pose(node, at, new Matrix4f()).getTranslation(new Vector3f());
        return at.add(o.x, o.y, o.z);
    }

    /** Owning part of {@code node}, or null (hull). */
    @Nullable
    public PartUnit<?> partUnitOf(int node) {
        return parts[node];
    }

    /** Nodes of {@code part}. */
    public IntPredicate ofPart(PartUnit<?> part) {
        return i -> parts[i] == part;
    }

    /**
     * {@code eye -> at} within {@link #PROBE_CLOSE}, or no live surface outside {@code own} more than
     * {@link #PROBE_SLACK} short of {@code at}.
     */
    public boolean probeReaches(Vec3 eye, Vec3 at, IntPredicate own) {
        return probeReaches(eye, at, own, PROBE_CLOSE);
    }

    public boolean probeReaches(Vec3 eye, Vec3 at, IntPredicate own, double close) {
        double distance = eye.distanceTo(at);
        if (distance <= close) {
            return true;
        }
        Hit hit = trace(eye, at, i -> !own.test(i) && live(i) && !model.nodes().get(i).mesh().module());
        return hit == null || (1 - hit.fraction()) * distance <= PROBE_SLACK;
    }

    /** Nearest live surface (drawn) node along {@code from -> to}, or null. */
    @Nullable
    @Override
    public Hit surface(Vec3 from, Vec3 to) {
        return trace(from, to, i -> live(i) && !model.nodes().get(i).mesh().module());
    }

    /** mm RHA {@code node}'s module consumes: its {@code rha} key, else its system kind's default. */
    public float moduleRha(int node) {
        HitModel.Node n = model.nodes().get(node);
        float rha = n.mesh().rha();
        return rha == rha ? rha : n.kind() == null ? 0.0f : n.kind().moduleRha();
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
        String bestPlate = null;
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
                    bestPlate = model.mesh().plateId(mesh.plate(triangle));
                    bestMaterial = mesh.material(triangle);
                    toWorld(bestNormal, i, g);
                }
            } else if (disc != null) {
                double hit = disc(disc, no, nd, best, bestNormal);
                if (hit >= 0) {
                    best = hit;
                    bestNode = i;
                    bestPlate = null;
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
        return new Hit(bestNode, best, from.add(to.subtract(from).scale(best)), bestNormal, bestPlate, bestMaterial);
    }

    /** Per-thread query scratch: {@link #surfaces} and {@link #modules} allocate only their results. */
    private static final class Scratch {
        final int[] stack = new int[TriangleBvh.STACK];
        final float[] nodeT = new float[MAX_CROSSINGS];
        final int[] nodeTri = new int[MAX_CROSSINGS];
        final float[] mergedT = new float[MAX_CROSSINGS];
        final int[] mergedNode = new int[MAX_CROSSINGS];
        /** Triangle, or -1 for a rotor disc. */
        final int[] mergedTri = new int[MAX_CROSSINGS];
        final float[] tOut = new float[1];
        final float[] normalOut = new float[3];
        final Quaternionf vehicleInverse = new Quaternionf();
        final Quaternionf nodeInverse = new Quaternionf();
        final Vector3f o = new Vector3f();
        final Vector3f d = new Vector3f();
        final Vector3f no = new Vector3f();
        final Vector3f nd = new Vector3f();
        final Vector3f normal = new Vector3f();
        final Vector3f world = new Vector3f();
        Quaternionf[] groupInverse = new Quaternionf[0];
        Vector3f[] groupO = new Vector3f[0];
        Vector3f[] groupD = new Vector3f[0];
        boolean[] ready = new boolean[0];

        void groups(int n) {
            if (groupInverse.length < n) {
                groupInverse = new Quaternionf[n];
                groupO = new Vector3f[n];
                groupD = new Vector3f[n];
                ready = new boolean[n];
                for (int i = 0; i < n; i++) {
                    groupInverse[i] = new Quaternionf();
                    groupO[i] = new Vector3f();
                    groupD[i] = new Vector3f();
                }
            }
            Arrays.fill(ready, false);
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /** World {@code from -> to} into the vehicle frame ({@code s.o}, {@code s.d}); groups not yet posed. */
    private void begin(Scratch s, Vec3 from, Vec3 to) {
        s.vehicleInverse.set(vehicle.rotYXZ()).conjugate();
        Vec3 c = vehicle.centerOffset;
        double bx = vehicle.getX() + c.x, by = vehicle.getY() + c.y, bz = vehicle.getZ() + c.z;
        s.vehicleInverse.transform(s.o.set((float) (from.x - bx), (float) (from.y - by), (float) (from.z - bz)))
                .add((float) c.x, (float) c.y, (float) c.z);
        s.vehicleInverse.transform(s.d.set((float) (to.x - from.x), (float) (to.y - from.y), (float) (to.z - from.z)));
        s.groups(groups.length);
    }

    /** The ray of {@link #begin} in node {@code i}'s frame ({@code s.no}, {@code s.nd}). */
    private void nodeRay(Scratch s, int i, HitModel.Node node) {
        int g = node.group();
        if (!s.ready[g]) {
            s.ready[g] = true;
            int index = groupIndex[g];
            s.groupInverse[g].set(vehicle.groupRotation(index)).conjugate();
            s.groupInverse[g].transform(s.groupO[g].set(s.o).sub(vehicle.groupOffset(index)));
            s.groupInverse[g].transform(s.groupD[g].set(s.d));
        }
        s.nodeInverse.set(toGroup[i]).conjugate();
        s.nodeInverse.transform(s.no.set(s.groupO[g]).sub(offset[i]));
        s.nodeInverse.transform(s.nd.set(s.groupD[g]));
    }

    /**
     * Every live drawn surface along {@code from -> to}, nearest first, at most {@link HullMesh#MAX_CROSSINGS}: BVH
     * all-hits per node, merged; faces within {@link HullMesh#CROSSING_STEP} of the previous one are one crossing (the
     * plate-bound one kept: coincident back faces, shared triangle edges). {@code entering} alternates from true,
     * winding is not trusted. Rotor discs: their entry only. Normals face the shot.
     */
    @Override
    public int surfaces(Vec3 from, Vec3 to, List<HullMesh.Crossing> out) {
        double length = from.distanceTo(to);
        if (length <= CROSSING_STEP) {
            return 0;
        }
        Scratch s = SCRATCH.get();
        begin(s, from, to);
        int merged = 0;
        List<HitModel.Node> all = model.nodes();
        for (int i = 0; i < all.size(); i++) {
            if (!live(i) || all.get(i).mesh().module()) {
                continue;
            }
            HitModel.Node node = all.get(i);
            float worst = merged == MAX_CROSSINGS ? s.mergedT[merged - 1] : 1.0f;
            nodeRay(s, i, node);
            if (slab(node.bounds(), s.no, s.nd, worst) < 0) {
                continue;
            }
            TriangleBvh mesh = node.mesh().mesh();
            HitMesh.Disc disc = node.mesh().disc();
            if (mesh != null) {
                int n = mesh.traceAll(s.no.x, s.no.y, s.no.z, s.nd.x, s.nd.y, s.nd.z, worst, s.stack, s.nodeT, s.nodeTri);
                for (int k = 0; k < n; k++) {
                    merged = merge(s, merged, s.nodeT[k], i, s.nodeTri[k]);
                }
            } else if (disc != null) {
                double hit = disc(disc, s.no, s.nd, worst, s.normal);
                if (hit >= 0) {
                    merged = merge(s, merged, (float) hit, i, -1);
                }
            }
        }
        double scale = length;
        int n = 0;
        double lastAt = Double.NEGATIVE_INFINITY;
        String lastPlate = null;
        Vec3 line = to.subtract(from);
        Vector3f dir = s.world.set((float) line.x, (float) line.y, (float) line.z);
        for (int k = 0; k < merged; k++) {
            int node = s.mergedNode[k];
            int tri = s.mergedTri[k];
            TriangleBvh mesh = all.get(node).mesh().mesh();
            String plate = tri < 0 ? null : model.mesh().plateId(mesh.plate(tri));
            double at = s.mergedT[k] * scale;
            if (n > 0 && at - lastAt < CROSSING_STEP) {
                if (lastPlate != null || plate == null) {
                    continue;
                }
                n--;
                out.remove(out.size() - 1);
            }
            Vector3f normal = new Vector3f();
            if (tri >= 0) {
                mesh.normal(tri, s.normalOut);
                normal.set(s.normalOut[0], s.normalOut[1], s.normalOut[2]);
            } else {
                nodeRay(s, node, all.get(node));
                HitMesh.Disc disc = all.get(node).mesh().disc();
                disc(disc, s.no, s.nd, s.mergedT[k] + 1.0f, normal);
            }
            toWorld(normal, node, all.get(node).group());
            if (normal.dot(dir) > 0) {
                normal.negate();
            }
            out.add(new HullMesh.Ordered(node, from.add(line.scale(s.mergedT[k])), normal, plate, n % 2 == 0));
            n++;
            lastAt = at;
            lastPlate = plate;
        }
        return n;
    }

    /** Insertion into the merged list, nearest {@link HullMesh#MAX_CROSSINGS}; returns the new count. */
    private static int merge(Scratch s, int count, float t, int node, int tri) {
        int at;
        if (count < MAX_CROSSINGS) {
            at = count++;
        } else if (t < s.mergedT[count - 1]) {
            at = count - 1;
        } else {
            return count;
        }
        while (at > 0 && s.mergedT[at - 1] > t) {
            s.mergedT[at] = s.mergedT[at - 1];
            s.mergedNode[at] = s.mergedNode[at - 1];
            s.mergedTri[at] = s.mergedTri[at - 1];
            at--;
        }
        s.mergedT[at] = t;
        s.mergedNode[at] = node;
        s.mergedTri[at] = tri;
        return count;
    }

    /**
     * Live {@code hit_*} modules along {@code from -> to}, each at its first entry, nearest first, into {@code out}
     * (caller clears).
     *
     * @return modules appended
     */
    public int modules(Vec3 from, Vec3 to, List<Hit> out) {
        if (from.distanceToSqr(to) < 1.0e-12) {
            return 0;
        }
        Scratch s = SCRATCH.get();
        begin(s, from, to);
        List<HitModel.Node> all = model.nodes();
        int n = 0;
        Vec3 line = to.subtract(from);
        Vector3f dir = s.world.set((float) line.x, (float) line.y, (float) line.z);
        for (int i = 0; i < all.size(); i++) {
            HitModel.Node node = all.get(i);
            TriangleBvh mesh = node.mesh().mesh();
            if (mesh == null || !node.mesh().module() || !live(i)) {
                continue;
            }
            nodeRay(s, i, node);
            if (slab(node.bounds(), s.no, s.nd, 1.0) < 0) {
                continue;
            }
            int tri = mesh.trace(s.no.x, s.no.y, s.no.z, s.nd.x, s.nd.y, s.nd.z, 1.0f, s.tOut, s.stack);
            if (tri < 0) {
                continue;
            }
            mesh.normal(tri, s.normalOut);
            Vector3f normal = new Vector3f(s.normalOut[0], s.normalOut[1], s.normalOut[2]);
            toWorld(normal, i, node.group());
            if (normal.dot(dir) > 0) {
                normal.negate();
            }
            double t = s.tOut[0];
            Hit hit = new Hit(i, t, from.add(line.scale(t)), normal, null, mesh.material(tri));
            int at = out.size();
            while (at > 0 && out.get(at - 1).fraction() > t) {
                at--;
            }
            out.add(at, hit);
            n++;
        }
        return n;
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

    /** World point -> model frame with {@code node}'s group at rest (armour zones). */
    @Override
    public Vector3f restPoint(int node, Vec3 world) {
        Vec3 c = vehicle.centerOffset;
        Vector3f v = new Vector3f((float) (world.x - vehicle.getX() - c.x), (float) (world.y - vehicle.getY() - c.y),
                (float) (world.z - vehicle.getZ() - c.z));
        new Quaternionf(vehicle.rotYXZ()).conjugate().transform(v).add((float) c.x, (float) c.y, (float) c.z);
        int slot = model.nodes().get(node).group();
        int index = groupIndex[slot];
        new Quaternionf(vehicle.groupRotation(index)).conjugate().transform(v.sub(vehicle.groupOffset(index)));
        return model.fromGroup(slot, v, true);
    }

    /** World direction -> model frame with {@code node}'s group at rest. */
    @Override
    public Vector3f restDirection(int node, Vector3f world) {
        int slot = model.nodes().get(node).group();
        Vector3f v = new Quaternionf(vehicle.rotYXZ()).conjugate().transform(new Vector3f(world));
        new Quaternionf(vehicle.groupRotation(groupIndex[slot])).conjugate().transform(v);
        return model.fromGroup(slot, v, false);
    }

    /** Inverse of {@link #restDirection}. */
    @Override
    public Vector3f worldDirection(int node, Vector3f rest) {
        int slot = model.nodes().get(node).group();
        Vector3f v = model.toGroupDirection(slot, new Vector3f(rest));
        vehicle.groupRotation(groupIndex[slot]).transform(v);
        return vehicle.rotYXZ().transform(v);
    }
}
