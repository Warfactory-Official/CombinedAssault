package com.wf.combinedassault.ballistics.addon;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.structure.VehicleCubeGroup;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One vehicle's ERA and slat: the pack's mount points and what is on each. */
public final class AddonArmorLayer {

    /**
     * A panel this far behind the structure hull's entry still meets a ray first: structure boxes over-hang the
     * display skin panels are baked onto (ztz99a's body is one box, its glacis slopes inside it).
     */
    public static final double SKIN_INSET = 1.0;

    private List<AddonMount> mounts = List.of();
    private AddonMountState[] states = new AddonMountState[0];
    /** Instance groups; parts attach after {@link #bind} => resolved lazily on first pose. */
    @Nullable
    private VehicleCubeGroup[] groups;
    private int version;
    private boolean dirty;

    /** Keeps what is installed on every point whose id survives. */
    public void bind(List<AddonMount> newMounts) {
        Map<String, CompoundTag> kept = new HashMap<>();
        for (int i = 0; i < mounts.size(); i++) {
            kept.put(mounts.get(i).info.id, states[i].save(mounts.get(i).info.id));
        }
        mounts = newMounts;
        states = new AddonMountState[newMounts.size()];
        for (int i = 0; i < states.length; i++) {
            states[i] = new AddonMountState();
            CompoundTag old = kept.get(newMounts.get(i).info.id);
            if (old != null) {
                states[i].load(newMounts.get(i), old);
            }
        }
        groups = null;
        changed();
    }

    public List<AddonMount> mounts() {
        return mounts;
    }

    public AddonMountState state(int mount) {
        return states[mount];
    }

    public boolean isEmpty() {
        return mounts.isEmpty();
    }

    public boolean anyInstalled() {
        for (AddonMountState state : states) {
            if (!state.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** kg: live ERA bricks, slat by intact bars. */
    public float mass() {
        float kg = 0;
        for (int i = 0; i < states.length; i++) {
            AddonMountState state = states[i];
            if (state.isEmpty()) {
                continue;
            }
            if (state.fit.kind == AddonKind.ERA) {
                kg += state.liveCells() * state.fit.kilograms;
            } else if (state.bars.length > 0) {
                kg += mounts.get(i).installCost(state.fit) * state.fit.kilograms * state.intactBars() / state.bars.length;
            }
        }
        return kg;
    }

    /** Bumped on every state change; the renderer rebuilds a mount's buffer when it moves. */
    public int version() {
        return version;
    }

    public void changed() {
        version++;
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    public CompoundTag save() {
        ListTag list = new ListTag();
        for (int i = 0; i < mounts.size(); i++) {
            if (!states[i].isEmpty()) {
                list.add(states[i].save(mounts.get(i).info.id));
            }
        }
        CompoundTag tag = new CompoundTag();
        tag.put("Mounts", list);
        return tag;
    }

    public void load(CompoundTag tag) {
        Map<String, CompoundTag> byId = new HashMap<>();
        for (Tag entry : tag.getList("Mounts", Tag.TAG_COMPOUND)) {
            CompoundTag mountTag = (CompoundTag) entry;
            byId.put(mountTag.getString("Id"), mountTag);
        }
        for (int i = 0; i < states.length; i++) {
            CompoundTag mountTag = byId.get(mounts.get(i).info.id);
            if (mountTag == null) {
                states[i].clear();
            } else {
                states[i].load(mounts.get(i), mountTag);
            }
        }
        changed();
    }

    /** This mount's bone group on {@code vehicle}, or null for the hull frame. */
    @Nullable
    public VehicleCubeGroup group(AbstractVehicle vehicle, int mount) {
        if (groups == null) {
            Map<String, VehicleCubeGroup> byName = new HashMap<>();
            for (VehicleCubeOBB cube : VectorUtil.hullCubes(vehicle)) {
                for (VehicleCubeGroup group = cube.group; group != null; group = group.parent) {
                    if (!group.name.isEmpty()) {
                        byName.putIfAbsent(group.name, group);
                    }
                }
            }
            VehicleCubeGroup[] resolved = new VehicleCubeGroup[mounts.size()];
            for (int i = 0; i < resolved.length; i++) {
                resolved[i] = byName.get(mounts.get(i).info.bone);
            }
            groups = resolved;
        }
        return groups[mount];
    }

    /** Panel -> world: {@code world = centre + rotation * p}. */
    public record Pose(Vec3 centre, Quaternionf rotation) {

        public Vec3 toWorld(float x, float y, float z) {
            Vector3f p = rotation.transform(new Vector3f(x, y, z));
            return centre.add(p.x, p.y, p.z);
        }
    }

    /** Same composition as {@code VehicleCubeOBB.update}. */
    public Pose pose(AbstractVehicle vehicle, int mount) {
        AddonMount m = mounts.get(mount);
        VehicleCubeGroup group = group(vehicle, mount);
        Vector3f local = new Vector3f(m.cx, m.cy, m.cz);
        Quaternionf rotation = new Quaternionf(m.rotation);
        if (group != null) {
            VehicleCubeGroup.GlobalTransform at = group.globalTransform();
            at.rotation().transform(local);
            local.add(at.offset().toVector3f());
            rotation.premul(at.rotation());
        }
        Vec3 c = vehicle.centerOffset;
        local.sub((float) c.x, (float) c.y, (float) c.z);
        Quaternionf vehicleRotation = vehicle.rotYXZ();
        vehicleRotation.transform(local);
        rotation.premul(vehicleRotation);
        return new Pose(new Vec3(vehicle.getX() + c.x + local.x, vehicle.getY() + c.y + local.y,
                vehicle.getZ() + c.z + local.z), rotation);
    }

    /**
     * One panel met by a segment.
     *
     * @param fraction along the segment, 0..1
     * @param x        panel-frame crossing (bar plane for slat, entry face for ERA)
     * @param cos      shot direction . panel normal
     */
    public record Hit(int mount, double fraction, Vec3 point, float x, float y, float cos) {
    }

    /** Every mount's {@link #pose}, for many traces against one vehicle state. */
    public Pose[] poses(AbstractVehicle vehicle) {
        Pose[] poses = new Pose[mounts.size()];
        for (int i = 0; i < poses.length; i++) {
            poses[i] = pose(vehicle, i);
        }
        return poses;
    }

    /** Every panel the segment crosses, nearest first. */
    public List<Hit> trace(AbstractVehicle vehicle, Vec3 start, Vec3 end, boolean installedOnly) {
        return trace(vehicle, start, end, installedOnly, null);
    }

    /** @param poses from {@link #poses}; null = pose each mount now */
    public List<Hit> trace(AbstractVehicle vehicle, Vec3 start, Vec3 end, boolean installedOnly,
                           @Nullable Pose[] poses) {
        List<Hit> hits = new ArrayList<>(2);
        for (int i = 0; i < mounts.size(); i++) {
            AddonFit fit = states[i].fit;
            if (installedOnly && fit == AddonFit.NONE) {
                continue;
            }
            Hit hit = clip(poses != null ? poses[i] : pose(vehicle, i), i,
                    fit == AddonFit.NONE ? defaultFit(mounts.get(i)) : fit, start, end);
            if (hit != null) {
                hits.add(hit);
            }
        }
        hits.sort((a, b) -> Double.compare(a.fraction, b.fraction));
        return hits;
    }

    @Nullable
    public Hit first(AbstractVehicle vehicle, Vec3 start, Vec3 end, boolean installedOnly) {
        List<Hit> hits = trace(vehicle, start, end, installedOnly);
        return hits.isEmpty() ? null : hits.getFirst();
    }

    private static boolean nearSegment(Vec3 c, double radius, Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        double ox = c.x - a.x, oy = c.y - a.y, oz = c.z - a.z;
        double len = dx * dx + dy * dy + dz * dz;
        double t = len < 1.0e-12 ? 0 : Math.max(0, Math.min(1, (ox * dx + oy * dy + oz * dz) / len));
        double ex = ox - dx * t, ey = oy - dy * t, ez = oz - dz * t;
        return ex * ex + ey * ey + ez * ez <= radius * radius;
    }

    private static AddonFit defaultFit(AddonMount mount) {
        return mount.info.kind == AddonKind.ERA ? AddonFit.ERA_LIGHT : AddonFit.SLAT;
    }

    @Nullable
    private Hit clip(Pose pose, int mount, AddonFit fit, Vec3 start, Vec3 end) {
        AddonMount m = mounts.get(mount);
        if (!nearSegment(pose.centre(), m.boundingRadius(), start, end)) {
            return null;
        }
        Quaternionf inverse = new Quaternionf(pose.rotation()).conjugate();
        Vector3f o = inverse.transform(new Vector3f((float) (start.x - pose.centre().x),
                (float) (start.y - pose.centre().y), (float) (start.z - pose.centre().z)));
        Vector3f d = inverse.transform(new Vector3f((float) (end.x - start.x), (float) (end.y - start.y),
                (float) (end.z - start.z)));
        float[] min = {-m.halfWidth, -m.halfHeight, m.zMin(fit)};
        float[] max = {m.halfWidth, m.halfHeight, m.zMax(fit)};
        float[] oo = {o.x, o.y, o.z};
        float[] dd = {d.x, d.y, d.z};
        double enter = 0;
        double exit = 1;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(dd[axis]) < 1.0e-9f) {
                if (oo[axis] < min[axis] || oo[axis] > max[axis]) {
                    return null;
                }
                continue;
            }
            double t0 = (min[axis] - oo[axis]) / dd[axis];
            double t1 = (max[axis] - oo[axis]) / dd[axis];
            if (t0 > t1) {
                double swap = t0;
                t0 = t1;
                t1 = swap;
            }
            enter = Math.max(enter, t0);
            exit = Math.min(exit, t1);
            if (enter > exit) {
                return null;
            }
        }
        double at = enter;
        if (fit.kind == AddonKind.SLAT && Math.abs(d.z) > 1.0e-9f) {
            double plane = -o.z / d.z;
            if (plane >= enter && plane <= exit) {
                at = plane;
            }
        }
        float x = (float) (o.x + d.x * at);
        float y = (float) (o.y + d.y * at);
        float length = d.length();
        float cos = length < 1.0e-9f ? 1.0f : -d.z / length;
        Vec3 point = start.add(end.subtract(start).scale(at));
        return new Hit(mount, at, point, x, y, cos);
    }
}
