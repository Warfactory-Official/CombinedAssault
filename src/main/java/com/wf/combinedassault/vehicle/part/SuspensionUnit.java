package com.wf.combinedassault.vehicle.part;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix3d;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import com.wf.combinedassault.api.custom.sync.SyncDataSerializers;
import com.wf.combinedassault.custom.part.data.SuspensionUnitData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.util.PhysicsHelper;
import com.wf.combinedassault.util.VehiclePartSpawner;
import com.wf.combinedassault.vehicle.PhysicsEngine;
import com.wf.combinedassault.vehicle.collision.BoxBuffer;
import com.wf.combinedassault.vehicle.parenting.CarrierLink;
import com.wf.combinedassault.vehicle.structure.VehicleCubeGroup;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SuspensionUnit<T extends SuspensionUnitData> extends PartUnit<T> {

    private static final double CONTACT_EPSILON = 0.001;
    private static final double CONTACT_RECOVERY = 0.25;
    /** Strut -y below this (past 60 degrees off vertical) carries nothing: 0.25 propped a centauro on its side at 71 degrees. */
    private static final double STRUT_UPRIGHT = 0.5;
    /** Support probe pitch along a long wheel body (track run, dual wheel), blocks. */
    private static final double SUPPORT_SPACING = 1.0;
    private float restLength;
    /** {@code stow_when_towed} leg: 0 stowed .. 1 down; fraction of travel reachable. */
    private float deploy = 1;
    /** Per tick: full stroke in 2 s. */
    private static final float DEPLOY_RATE = 1 / 40F;
    private float maxCompression;
    private float compression;
    private float remoteCompression;
    private boolean grounded;
    private double contactCompression;
    /** Probe origins relative to the mount, parent-group frame, bind pose. */
    private Vec3[] supportOffsets = {Vec3.ZERO};
    private double[] supportRestLengths = new double[1];
    private double[] supportMaxCompressions = new double[1];
    /** Last simulate(): one row per probe that found ground. World xyz, unclamped compression, travel limits. */
    private int contactCount;
    private double[] contactXYZ = new double[3];
    /** Per contact row: hit a carrier deck box, not a block. */
    private boolean[] contactOnDeck = new boolean[1];
    private boolean probeOnDeck;
    private double[] contactCompressions = new double[1];
    private double[] contactMins = new double[1];
    private double[] contactMaxes = new double[1];
    private Vec3 direction = new Vec3(0, -1, 0);
    // Solve runs off-thread against a rig pose: mount and parent frame captured in vehicle space on the tick thread.
    private Vec3 mountLocal = Vec3.ZERO;
    private final Quaternionf frameLocal = new Quaternionf();
    /** isActive() at capture; damage lands on the tick thread mid-solve. */
    private boolean frameActive;

    private record CalibrationPoint(SuspensionUnit<?> unit, int index, Vec3 bottom, Vector3d arm) {}

    public SuspensionUnit(int index, AbstractVehicle vehicle, T data) {
        super(index, vehicle, data);
        restLength = data.getRestLength();
        maxCompression = data.getMaxCompression();
        supportRestLengths[0] = restLength;
        supportMaxCompressions[0] = maxCompression;
        compression = remoteCompression = getMinCompression();
        contactCompression = compression;
        syncData.define(SyncDataSerializers.FLOAT, value -> remoteCompression = value,
                this::getCompression, compression);
        syncData.define(SyncDataSerializers.BOOLEAN, value -> grounded = value, this::isGrounded, false);
        if (data.stowWhenTowed()) {
            syncData.define(SyncDataSerializers.FLOAT, value -> deploy = value, () -> deploy, deploy);
        }
    }

    @Override
    public void buildStructure(Map<VehicleCubeGroup, VehicleCubeGroup> groups) {
        super.buildStructure(groups);
        supportOffsets = buildSupportOffsets();
        int n = supportOffsets.length;
        supportRestLengths = new double[n];
        supportMaxCompressions = new double[n];
        Arrays.fill(supportRestLengths, restLength);
        Arrays.fill(supportMaxCompressions, maxCompression);
        contactCount = 0;
        contactXYZ = new double[n * 3];
        contactOnDeck = new boolean[n];
        contactCompressions = new double[n];
        contactMins = new double[n];
        contactMaxes = new double[n];
    }

    /** Wheel-bottom probes; a body longer than max(spacing, 1.5 x its height) gets one per spacing. */
    protected Vec3[] buildSupportOffsets() {
        if (structureGroup == null) {
            return new Vec3[]{Vec3.ZERO};
        }
        Quaternionf parentRotation = structureGroup.parent == null ? new Quaternionf()
                : structureGroup.parent.globalTransform().rotation();
        Quaternionf inverseParentRotation = new Quaternionf(parentRotation).invert();
        // Sample at wheel bottom: works for both wheel-center and wheel-bottom pivot conventions.
        double bottom = 0;
        List<AABB> bounds = new ArrayList<>();
        AABB total = null;
        for (VehicleCubeOBB cube : partCubeOBBs) {
            var transform = cube.group.globalTransform();
            Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
            Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
            for (int corner = 0; corner < 8; corner++) {
                Vector3f point = new Vector3f((float) (cube.x + ((corner & 1) == 0 ? 0 : cube.width)),
                        (float) (cube.y + ((corner & 2) == 0 ? 0 : cube.height)),
                        (float) (cube.z + ((corner & 4) == 0 ? 0 : cube.depth)));
                transform.rotation().transform(point);
                Vec3 relativePoint = transform.offset().add(new Vec3(point)).subtract(pivotOffset);
                Vector3f local = inverseParentRotation.transform(relativePoint.toVector3f());
                min.min(local);
                max.max(local);
                bottom = Math.min(bottom, local.y);
            }
            AABB box = new AABB(min.x, min.y, min.z, max.x, max.y, max.z);
            bounds.add(box);
            total = total == null ? box : total.minmax(box);
        }
        if (total == null) {
            return new Vec3[]{new Vec3(0, bottom, 0)};
        }
        double split = Math.max(SUPPORT_SPACING, total.getYsize() * 1.5);
        if (Math.max(total.getXsize(), total.getZsize()) <= split) {
            return new Vec3[]{new Vec3(0, bottom, 0)};
        }
        List<Vec3> offsets = new ArrayList<>();
        for (AABB box : bounds) {
            int countX = box.getXsize() > split ? (int) Math.ceil(box.getXsize() / SUPPORT_SPACING) : 1;
            int countZ = box.getZsize() > split ? (int) Math.ceil(box.getZsize() / SUPPORT_SPACING) : 1;
            for (int x = 0; x < countX; x++) {
                for (int z = 0; z < countZ; z++) {
                    Vec3 offset = new Vec3(box.minX + box.getXsize() * (x + 0.5) / countX,
                            box.minY, box.minZ + box.getZsize() * (z + 0.5) / countZ);
                    if (offsets.stream().noneMatch(existing -> existing.distanceToSqr(offset) < 1.0E-6)) {
                        offsets.add(offset);
                    }
                }
            }
        }
        return offsets.toArray(Vec3[]::new);
    }

    /**
     * Calibrated once at init from static gravity/torque balance; not redone for fuel or wheel damage.
     * Per probe; unit length = mean.
     */
    public static void calibrateRestLengths(AbstractVehicle vehicle) {
        List<SuspensionUnit<?>> units = vehicle.getSuspensionUnits().stream()
                .filter(unit -> unit.structureGroup != null && unit.data.getSpringStiffness() > 0).toList();
        if (units.isEmpty()) {
            return;
        }
        var physics = vehicle.physicsEngine;
        var cube = vehicle.getMainCubeOBB();
        Vec3 center = cube.offset().add(new Vec3(cube.selfRot().transform(physics.center.toVector3f())));
        Quaternionf bodyRotation = cube.group.globalTransform().rotation();
        double bodyHalfHeight = (Math.abs(bodyRotation.transform(new Vector3f(1, 0, 0)).y) * cube.width
                + Math.abs(bodyRotation.transform(new Vector3f(0, 1, 0)).y) * cube.height
                + Math.abs(bodyRotation.transform(new Vector3f(0, 0, 1)).y) * cube.depth) / 2;
        double ground = cube.offset().y - bodyHalfHeight;
        List<CalibrationPoint> points = new ArrayList<>();
        // Tiny regularizer: keeps the solve stable for two-wheel, collinear-support, locked-rotation configs.
        Matrix3d stiffness = new Matrix3d().scaling(1.0E-9);
        for (SuspensionUnit<?> unit : units) {
            Quaternionf parentRotation = unit.structureGroup.parent == null ? new Quaternionf()
                    : unit.structureGroup.parent.globalTransform().rotation();
            Vec3 direction = new Vec3(parentRotation.transform(new Vector3f(0, -1, 0)));
            if (direction.y >= -0.25) {
                return;
            }
            Vec3[] offsets = unit.supportOffsets;
            double k = PhysicsHelper.forcePerTick(unit.data.getSpringStiffness()) / offsets.length;
            for (int i = 0; i < offsets.length; i++) {
                Vec3 bottom = unit.pivotOffset.add(new Vec3(parentRotation.transform(offsets[i].toVector3f())));
                ground = Math.min(ground, bottom.y);
                Vec3 arm = bottom.subtract(center);
                Vector3d a = new Vector3d(1, physics.lockCenterRot ? 0 : -arm.z,
                        physics.lockCenterRot || physics.lockZRot ? 0 : arm.x).div(-direction.y);
                points.add(new CalibrationPoint(unit, i, bottom, a));
                stiffness.add(new Matrix3d(
                        k * a.x * a.x, k * a.x * a.y, k * a.x * a.z,
                        k * a.y * a.x, k * a.y * a.y, k * a.y * a.z,
                        k * a.z * a.x, k * a.z * a.y, k * a.z * a.z));
            }
        }
        Vector3d deflection = stiffness.invert().transform(new Vector3d(vehicle.curbWeight * PhysicsEngine.G, 0, 0));
        double[] staticCompression = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            staticCompression[i] = points.get(i).arm().dot(deflection);
            // Unbalanceable by upward support alone (negative/non-finite): keep the pack's own rest lengths.
            if (!Double.isFinite(staticCompression[i]) || staticCompression[i] < 0) {
                return;
            }
        }
        // 0.01 gap: avoids repeat bottom-out when bind-pose wheel/hull sits exactly on the ground.
        ground -= 0.01;
        for (int i = 0; i < points.size(); i++) {
            CalibrationPoint point = points.get(i);
            point.unit().supportRestLengths[point.index()] = staticCompression[i] + (point.bottom().y - ground) * point.arm().x;
            // Keep configured travel; widen only enough to include the no-load calibration point.
            point.unit().supportMaxCompressions[point.index()] = Math.max(point.unit().data.getMaxCompression(), staticCompression[i] + 0.01);
        }
        for (SuspensionUnit<?> unit : units) {
            unit.restLength = (float) Arrays.stream(unit.supportRestLengths).average().orElse(unit.restLength);
            unit.maxCompression = (float) Arrays.stream(unit.supportMaxCompressions).average().orElse(unit.maxCompression);
            unit.compression = unit.clampCompression(unit.compression);
            unit.remoteCompression = unit.clampCompression(unit.remoteCompression);
            unit.contactCompression = unit.compression;
        }
    }

    public float getMaxCompression() {
        return Math.max(maxCompression, getMinCompression());
    }

    public float getMinCompression() {
        return (float) minCompression(restLength);
    }

    // Compression zeroed at rest length; droop (getMaxDroop) zeroed at bind pose. Reach (length - min) x deploy:
    // stowed => min = length => drawn at bind, probe reaches nothing.
    private double minCompression(double length) {
        double min = Math.max(-data.getMaxExtension(), length - data.getMaxDroop());
        return length - deploy * (length - min);
    }

    protected boolean isActive() {
        return structureGroup != null && !isDetached() && !isDestroyed();
    }


    /**
     * Tick thread. Freezes mount and parent-group frame in vehicle space for
     * {@link #simulate(Vec3, Quaternionf, PhysicsEngine.CellTops)}.
     */
    public void captureFrame() {
        frameActive = isActive();
        if (structureGroup == null || structureGroup.parent == null) {
            mountLocal = pivotOffset.subtract(vehicle.centerOffset);
            frameLocal.identity();
            return;
        }
        VehicleCubeGroup parent = structureGroup.parent;
        mountLocal = parent.globalTransform(pivotOffset.subtract(parent.pivotOffset), true,
                group -> group.rotation, group -> group.offset).offset().subtract(vehicle.centerOffset);
        frameLocal.set(parent.globalTransform(Vec3.ZERO, false,
                group -> group.rotation, group -> group.offset).rotation());
    }

    /** Worker-safe given a captured frame: reads only the pose passed in and {@code tops}. */
    public void simulate(Vec3 position, Quaternionf vehicleRotation, PhysicsEngine.CellTops tops) {
        Quaternionf rotation = new Quaternionf(vehicleRotation).mul(frameLocal);
        Vec3 mountWorld = position.add(vehicle.centerOffset).add(new Vec3(vehicleRotation.transform(mountLocal.toVector3f())));
        direction = new Vec3(rotation.transform(new Vector3f(0, -1, 0))).normalize();
        grounded = false;
        contactCount = 0;
        compression = getMinCompression();
        contactCompression = compression;
        if (!frameActive || direction.y >= -STRUT_UPRIGHT) {
            return;
        }
        double nearest = Double.POSITIVE_INFINITY;
        Vector3f offset = new Vector3f();
        for (int i = 0; i < supportOffsets.length; i++) {
            rotation.transform(supportOffsets[i].toVector3f(), offset);
            double length = probe(mountWorld.x + offset.x, mountWorld.y + offset.y, mountWorld.z + offset.z,
                    direction, i, Math.max(CONTACT_RECOVERY, vehicle.maxUpStep() / -direction.y), tops);
            if (length == Double.POSITIVE_INFINITY) {
                continue;
            }
            int row = contactCount++;
            contactOnDeck[row] = probeOnDeck;
            contactXYZ[row * 3] = mountWorld.x + offset.x + direction.x * length;
            contactXYZ[row * 3 + 1] = mountWorld.y + offset.y + direction.y * length;
            contactXYZ[row * 3 + 2] = mountWorld.z + offset.z + direction.z * length;
            double min = minCompression(supportRestLengths[i]);
            contactCompressions[row] = supportRestLengths[i] - length;
            contactMins[row] = min;
            contactMaxes[row] = Math.max(supportMaxCompressions[i], min);
            nearest = Math.min(nearest, length);
        }
        if (contactCount > 0) {
            // Limit solve keeps the penetration (contactCompression); transform clamps to mechanical travel.
            contactCompression = restLength - nearest;
            compression = clampCompression((float) contactCompression);
            grounded = true;
        }
    }

    /**
     * Probe {@code index} from origin along direction: contact length, or +inf. {@code recovery}: reach above the
     * compressed bottom; past CONTACT_RECOVERY only onto a step.
     * Upstream: boxes = isSolid ? (half ? 0.5 : 1) tall full cells. Here: real collision top per cell, full-cell footprint.
     */
    private double probe(double ox, double oy, double oz, Vec3 direction, int index, double recovery,
                         PhysicsEngine.CellTops tops) {
        double rest = supportRestLengths[index];
        double min = minCompression(rest);
        double maxLength = rest - min;
        double minLength = rest - Math.max(supportMaxCompressions[index], min);
        // Recovers slight penetration; avoids a false airborne reading right after bottoming out.
        double probeMinLength = minLength - recovery;
        double sx = ox + direction.x * probeMinLength, sy = oy + direction.y * probeMinLength, sz = oz + direction.z * probeMinLength;
        double ex = ox + direction.x * maxLength, ey = oy + direction.y * maxLength, ez = oz + direction.z * maxLength;
        double minX = Math.min(sx, ex) - CONTACT_EPSILON;
        double minY = Math.min(sy, ey) - CONTACT_EPSILON;
        double minZ = Math.min(sz, ez) - CONTACT_EPSILON;
        double maxX = Math.max(sx, ex) + CONTACT_EPSILON;
        double maxY = Math.max(sy, ey) + CONTACT_EPSILON;
        double maxZ = Math.max(sz, ez) + CONTACT_EPSILON;
        double contactLength = Double.POSITIVE_INFINITY;
        // One cell below: a collision box may rise out of the cell beneath (fence, wall).
        for (int x = Mth.floor(minX); x <= Mth.floor(maxX); x++) {
            for (int z = Mth.floor(minZ); z <= Mth.floor(maxZ); z++) {
                for (int y = Mth.floor(minY) - 1; y <= Mth.floor(maxY); y++) {
                    double top = tops.top(x, y, z);
                    if (Double.isNaN(top)) {
                        continue;
                    }
                    if (Double.isNaN(tops.top(x, y + 1, z))) {
                        // Floor: the drive surface's fill raises it under the ray's hit point.
                        for (int pass = 0; pass < 2; pass++) {
                            double at = (top - oy) / direction.y;
                            top = tops.surface(x, y, z, Mth.clamp(ox + direction.x * at, x, x + 1),
                                    Mth.clamp(oz + direction.z * at, z, z + 1));
                        }
                    }
                    if (top <= minY) {
                        continue;
                    }
                    double length = (top - oy) / direction.y;
                    // Above the recovery window: a step only (empty above), else a wheel climbs a wall face.
                    if (length < minLength - CONTACT_RECOVERY && !Double.isNaN(tops.top(x, y + 1, z))) {
                        continue;
                    }
                    double cx = ox + direction.x * length, cz = oz + direction.z * length;
                    boolean inCell = cx >= x - CONTACT_EPSILON && cx <= x + 1 + CONTACT_EPSILON
                            && cz >= z - CONTACT_EPSILON && cz <= z + 1 + CONTACT_EPSILON;
                    if (inCell && length >= probeMinLength && length <= maxLength + CONTACT_EPSILON) {
                        contactLength = Math.min(contactLength, length);
                    }
                }
            }
        }
        double deck = deckLength(ox, oy, oz, direction, probeMinLength, maxLength + CONTACT_EPSILON);
        probeOnDeck = deck < contactLength;
        return Math.min(contactLength, deck);
    }

    /** Ray entry through a carrier deck box's top face, in [from, to]; +inf none. Side entry = wall, hull's. */
    private double deckLength(double ox, double oy, double oz, Vec3 direction, double from, double to) {
        CarrierLink link = vehicle.carrierLink();
        if (!link.active()) {
            return Double.POSITIVE_INFINITY;
        }
        BoxBuffer boxes = link.deckBoxes();
        Quaternionf deck = link.deckRotation();
        Quaternionf piece = new Quaternionf();
        Vector3f centre = new Vector3f(), o = new Vector3f(), d = new Vector3f();
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0, size = boxes.size(); i < size; i++) {
            if (!link.landing(i)) {
                continue;
            }
            deck.transform(centre.set(link.deckFrame(i, 0), link.deckFrame(i, 1), link.deckFrame(i, 2)));
            deck.mul(piece.set(link.deckFrame(i, 6), link.deckFrame(i, 7), link.deckFrame(i, 8), link.deckFrame(i, 9)), piece);
            piece.transformInverse(o.set((float) (ox - link.pivotX() - centre.x), (float) (oy - link.pivotY() - centre.y),
                    (float) (oz - link.pivotZ() - centre.z)));
            piece.transformInverse(d.set((float) direction.x, (float) direction.y, (float) direction.z));
            double near = Double.NEGATIVE_INFINITY, far = Double.POSITIVE_INFINITY;
            int axis = -1;
            for (int k = 0; k < 3 && near <= far; k++) {
                double e = link.deckFrame(i, 3 + k), ok = o.get(k), dk = d.get(k);
                if (Math.abs(dk) < 1.0e-9) {
                    if (Math.abs(ok) > e) {
                        far = Double.NEGATIVE_INFINITY;
                    }
                    continue;
                }
                double t1 = (-e - ok) / dk, t2 = (e - ok) / dk;
                if (t1 > t2) {
                    double t = t1;
                    t1 = t2;
                    t2 = t;
                }
                if (t1 > near) {
                    near = t1;
                    axis = k;
                }
                far = Math.min(far, t2);
            }
            if (axis == 1 && d.y < 0 && near <= far && near >= from && near <= to) {
                best = Math.min(best, near);
            }
        }
        return best;
    }

    public boolean contactOnDeck(int row) {
        return contactOnDeck[row];
    }

    @Override
    public void tick() {
        if (!vehicle.level().isClientSide()) {
            if (data.stowWhenTowed()) {
                deploy = Mth.approach(deploy, vehicle.towLink() == null ? 1 : 0, DEPLOY_RATE);
            }
            if (isDestroyed() && !isDetached() && !vehicle.isDestroyed() && isDetachable()) {
                VehiclePartSpawner.popOff(this);
            }
        }
        if (vehicle.level().isClientSide() && !vehicle.predicted()) {
            // Client: rebuild wheel geometry off interpolated hull; grounded flag stays server-synced.
            compression = getVisualCompression(1, Mth.lerp(0.5f, compression, remoteCompression));
        } else {
            // Server: resample mount axis after this tick's angular integration.
            captureFrame();
            simulate(vehicle.position(), vehicle.rotYXZ(), PhysicsEngine.CellTops.live(vehicle.level()));
        }
        updateTransform();
        super.tick();
    }

    private float clampCompression(float value) {
        return Mth.clamp(value, getMinCompression(), getMaxCompression());
    }

    private void updateTransform() {
        if (structureGroup == null) {
            return;
        }
        structureGroup.offsetO = structureGroup.offset;
        structureGroup.rotationO = new Quaternionf(structureGroup.rotation);
        setOffset(new Vec3(0, compression - restLength, 0));
        structureGroup.rotation = new Quaternionf(structureGroup.baseRotation);
    }

    public Vec3 getSuspensionDirection() {
        return direction;
    }

    private Vec3 getSuspensionDirection(float partialTick) {
        return new Vec3(getSuspensionRotation(partialTick).transform(new Vector3f(0, -1, 0))).normalize();
    }

    private Quaternionf getSuspensionRotation(float partialTick) {
        Quaternionf rotation = vehicle.rotYXZ(partialTick);
        if (structureGroup != null && structureGroup.parent != null) {
            rotation.mul(structureGroup.parent.globalTransform(Vec3.ZERO, false,
                    group -> getViewGroupRotation(group, partialTick), group -> group.offset).rotation());
        }
        return rotation;
    }

    public float getCompression() {
        return compression;
    }

    // Unclamped; feeds the limit solve.
    public double getContactCompression() {
        return contactCompression;
    }

    private float getVisualCompression(float partialTick, float fallback) {
        Vec3 direction = getSuspensionDirection(partialTick);
        if (!isActive() || direction.y >= -0.25) {
            return clampCompression(fallback);
        }
        Vec3 mount = worldPositionWithBaseRot(pivotOffset, partialTick);
        Quaternionf rotation = getSuspensionRotation(partialTick);
        PhysicsEngine.CellTops tops = PhysicsEngine.CellTops.live(vehicle.level());
        double compression = Double.NEGATIVE_INFINITY;
        Vector3f offset = new Vector3f();
        for (int i = 0; i < supportOffsets.length; i++) {
            rotation.transform(supportOffsets[i].toVector3f(), offset);
            double length = probe(mount.x + offset.x, mount.y + offset.y, mount.z + offset.z, direction, i,
                    CONTACT_RECOVERY, tops);
            compression = Math.max(compression, restLength - length);
        }
        return clampCompression(compression != Double.NEGATIVE_INFINITY ? (float) compression : fallback);
    }

    /**
     * Per probe (support offset order): drawn compression - its rest length, parent-frame +y. Inactive or tipped
     * strut: every probe the unit's {@code fallback} compression.
     */
    @OnlyIn(Dist.CLIENT)
    protected void probeTravel(float partialTick, float fallback, double[] out) {
        Vec3 direction = getSuspensionDirection(partialTick);
        if (!isActive() || direction.y >= -0.25) {
            Arrays.fill(out, clampCompression(fallback) - restLength);
            return;
        }
        Vec3 mount = worldPositionWithBaseRot(pivotOffset, partialTick);
        Quaternionf rotation = getSuspensionRotation(partialTick);
        PhysicsEngine.CellTops tops = PhysicsEngine.CellTops.live(vehicle.level());
        Vector3f offset = new Vector3f();
        for (int i = 0; i < supportOffsets.length; i++) {
            rotation.transform(supportOffsets[i].toVector3f(), offset);
            double length = probe(mount.x + offset.x, mount.y + offset.y, mount.z + offset.z, direction, i,
                    CONTACT_RECOVERY, tops);
            double min = minCompression(supportRestLengths[i]);
            double compression = length == Double.POSITIVE_INFINITY ? min
                    : Mth.clamp(supportRestLengths[i] - length, min, Math.max(supportMaxCompressions[i], min));
            out[i] = compression - supportRestLengths[i];
        }
    }

    /** Unit compression drawn this frame before ground probing: interpolated tick value. */
    @OnlyIn(Dist.CLIENT)
    protected float interpolatedCompression(float partialTick) {
        return (float) Mth.lerp(partialTick, structureGroup.offsetO.y + restLength, compression);
    }

    protected int supportCount() {
        return supportOffsets.length;
    }

    public boolean isGrounded() {
        return grounded;
    }

    @Override
    public Vec3 worldPivotPosition() {
        return worldPositionWithSelfRot(pivotOffset);
    }

    /** World probe bottoms at the drawn compression, rear first ({@code rearOnly}: rearmost probe only). */
    public void probeBottoms(float partialTick, boolean rearOnly, java.util.function.Consumer<Vec3> sink) {
        if (!isActive()) {
            return;
        }
        Quaternionf rotation = getSuspensionRotation(partialTick);
        Vec3 down = new Vec3(rotation.transform(new Vector3f(0, -1, 0)));
        Vec3 mount = worldPositionWithBaseRot(pivotOffset, partialTick);
        int rear = 0;
        for (int i = 1; i < supportOffsets.length; i++) {
            if (supportOffsets[i].z < supportOffsets[rear].z) {
                rear = i;
            }
        }
        for (int i = rearOnly ? rear : 0; i < (rearOnly ? rear + 1 : supportOffsets.length); i++) {
            Vec3 offset = new Vec3(rotation.transform(supportOffsets[i].toVector3f()));
            sink.accept(mount.add(offset).add(down.scale(supportRestLengths[i] - compression)));
        }
    }

    /** Grounded probes of the last simulate(). */
    public int contactCount() {
        return contactCount;
    }

    /** Probe share of the unit's spring. */
    public double contactWeight() {
        return 1.0 / supportOffsets.length;
    }

    public double contactX(int row) {
        return contactXYZ[row * 3];
    }

    public double contactY(int row) {
        return contactXYZ[row * 3 + 1];
    }

    public double contactZ(int row) {
        return contactXYZ[row * 3 + 2];
    }

    /** Unclamped: limit solve keeps the penetration. */
    public double contactCompression(int row) {
        return contactCompressions[row];
    }

    public double contactMin(int row) {
        return contactMins[row];
    }

    public double contactMax(int row) {
        return contactMaxes[row];
    }

    /** Render-bone travel off its rest, vehicle frame. */
    @OnlyIn(Dist.CLIENT)
    public Vec3 getVisualOffset(float partialTick) {
        if (structureGroup == null) {
            return Vec3.ZERO;
        }
        float visualCompression = getVisualCompression(partialTick, interpolatedCompression(partialTick));
        Vec3 visualOffset = new Vec3(0, visualCompression - restLength, 0);
        Vec3 position = structureGroup.globalTransform(Vec3.ZERO, false,
                group -> getViewGroupRotation(group, partialTick),
                group -> group == structureGroup ? visualOffset
                        : group.offsetO.lerp(group.offset, partialTick)).offset();
        Vec3 basePosition = structureGroup.globalTransform(Vec3.ZERO, false,
                group -> getViewGroupRotation(group, partialTick), group -> Vec3.ZERO).offset();
        return position.subtract(basePosition);
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = super.serializeNBT(provider);
        tag.putFloat("Compression", compression);
        tag.putBoolean("Grounded", grounded);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        super.deserializeNBT(provider, tag);
        if (tag.contains("Compression", Tag.TAG_ANY_NUMERIC)) {
            compression = remoteCompression = clampCompression(tag.getFloat("Compression"));
        }
        contactCompression = compression;
        grounded = tag.getBoolean("Grounded");
        updateTransform();
        if (structureGroup != null) {
            structureGroup.offsetO = structureGroup.offset;
            structureGroup.rotationO = new Quaternionf(structureGroup.rotation);
        }
    }

}
