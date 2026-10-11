package com.wf.combinedassault.entity.vehicle;

import com.wf.combinedassault.api.collision.CollisionProvider;
import com.wf.combinedassault.api.collision.OrientedBoxes;
import com.wf.combinedassault.tow.HitchInfo;
import com.wf.combinedassault.tow.TowLink;
import com.wf.combinedassault.tow.TowLinks;
import com.wf.combinedassault.tow.TowMode;
import com.wf.combinedassault.tow.TowSolver;
import com.wf.combinedassault.tow.TowableInfo;
import com.wf.combinedassault.vehicle.PhysicsEngine;
import com.wf.combinedassault.vehicle.PhysicsRig;
import com.wf.combinedassault.vehicle.collision.BoxBuffer;
import com.wf.combinedassault.vehicle.collision.ChunkCollisionCache;
import com.wf.combinedassault.vehicle.collision.CompoundBody;
import com.wf.combinedassault.vehicle.collision.DriveSurface;
import com.wf.combinedassault.vehicle.collision.SurfaceSection;
import com.wf.combinedassault.vehicle.parenting.CarrierLink;
import com.wf.combinedassault.vehicle.part.SuspensionUnit;
import com.wf.combinedassault.vehicle.rigid.ContactBuffer;
import com.wf.combinedassault.vehicle.rigid.ConvexContacts;
import com.wf.combinedassault.vehicle.rigid.NarrowNative;
import com.wf.combinedassault.vehicle.rigid.Polytope;
import com.wf.combinedassault.vehicle.rigid.RigidSolver;
import com.wf.combinedassault.vehicle.structure.ConvexHull;
import com.wf.combinedassault.util.PhysicsHelper;
import com.wf.wflib.util.OBB;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;

/**
 * One vehicle's share of {@link RigidStep}: inputs captured on the tick thread, contacts and integration in its island
 * task, pose applied at flush. Handoffs as {@link PhysicsRig}.
 */
public final class VehicleStep {

    /** Speculative reach beyond the tick's own motion, blocks. */
    static final float MARGIN = 0.1f;
    static final float FRICTION = 0.5f;
    static final float HULL_RESTITUTION = 0.05f;
    /** n.y above: support (ground, skirt never drops it). */
    public static final float SUPPORT_NORMAL = 0.7f;
    private static final float MATCH_DISTANCE_SQ = 0.15f * 0.15f;
    private static final float MATCH_NORMAL = 0.95f;
    /** Contact point moved this far into the box names its cell. */
    private static final float CELL_PROBE = 0.01f;
    /** {@link ContactBuffer#flags}: world support => {@link RigidSolver.Contact#lift}. */
    private static final int LIFT = 1, OFF_FACE = 2;
    /** Pitch/roll spin kept per tick on the belly, < half the wheels down, up.y above {@link #BELLY_SPIN_UP}. */
    private static final float BELLY_SPIN_RETENTION = 0.6f;
    /** cos 45 deg; past it the hull falls free. */
    private static final float BELLY_SPIN_UP = 0.707f;

    final AbstractVehicle vehicle;
    boolean active;
    /** Joined without its own entity tick this tick ({@link AbstractVehicle#wakeForStep}). */
    boolean woken;
    /** Island on the tick thread: live world (providers, trace, sync config). */
    boolean live;
    /** Client prediction ({@link RigidStep#predict}): live world, no providers. */
    boolean predicting;

    double pivotX, pivotY, pivotZ;
    double comX, comY, comZ;
    double hullX, hullY, hullZ;
    final Quaternionf entityRotation = new Quaternionf();
    final Quaternionf hullRotation = new Quaternionf();
    final Quaternionf hullLocalRotation = new Quaternionf();
    /** Entity frame, from pivot: hull centre, COM. */
    final Vector3f hullLocal = new Vector3f();
    final Vector3f comLocal = new Vector3f();
    final Vector3f hullExtents = new Vector3f();

    float inverseMass;
    final Matrix3f inverseInertia = new Matrix3f();
    final Vector3f velocity = new Vector3f();
    final Vector3f spin = new Vector3f();
    final Vector3f acceleration = new Vector3f();
    float spinRetention;
    float maxSpin;
    float margin;
    boolean sprung;
    boolean wheelsWereDown;
    AABB bounds;
    AABB gather;

    /** Active OBBs at capture, hull frame (centre, rotation): vehicle-vehicle shapes. */
    private float[] pieceFrames = new float[0];
    private int pieceCount;
    /** Strut off (up.y < STRUT_FADE_END): wheel boxes as world shapes, tyre sidewalls. Same layout as pieceFrames. */
    private float[] wheelFrames = new float[0];
    private int wheelCount;

    int body;
    /** World shape frames ({@link NarrowNative#FRAME} each), hull (null = box), native hull handle. */
    private float[] worldFrames = new float[0];
    private ConvexHull[] worldHulls = new ConvexHull[0];
    private long[] worldHandles = new long[0];
    private int worldFrameCount;
    final List<Polytope> worldShapes = new ArrayList<>();
    final List<Polytope> hullShapes = new ArrayList<>();
    int worldShapeCount, hullShapeCount;
    int contactFrom, contactTo, springFrom, springTo;
    final BoxBuffer boxes = new BoxBuffer();
    /** Live islands only (providers read the world). Contact tag = -1 - deck boxes - i. */
    final OrientedBoxes providerBoxes = new OrientedBoxes();
    final List<CollisionProvider.Session> sessions = new ArrayList<>();
    /** Session s owns provider boxes [sessionBase[s], sessionBase[s + 1]). */
    final IntArrayList sessionBase = new IntArrayList();
    /** World contact k's tag, k from contactFrom. */
    private int[] contactTags = new int[0];
    /** Towed, coupling closed this tick: world points at capture (coupling, hitch, axle patch). Null => free. */
    @Nullable
    TowLink tow;
    final double[] towPoints = new double[9];
    final Vector3f towLateral = new Vector3f();
    /** Rope length, < 0 ball; articulation (rad, +y) and its stop (NaN none); fifth-wheel roll (rad, NaN free). */
    float towLength, towYaw, towYawLimit, towRoll;
    final Vector3f towRollAxis = new Vector3f();
    /** Island task: this tick's joint index, -1 none. */
    int towJoint = -1;
    /** Captured on the tick thread (bakes); read by the island task. */
    DriveSurface.View surface;
    /** {@link AbstractVehicle#weightCarried}, at capture. */
    boolean carried;
    /** Prism depth behind a fill triangle, blocks. 0.25: hull at 0.47 b/t on a 1:2 ramp went 0.23 deep, past the prism. */
    private static final float FILL_DEPTH = 1.0f;
    /** Hull point this far past a box's xz footprint still reads as over its top, blocks. */
    private static final double TOP_SLACK = 0.02;
    final Polytope box = new Polytope();
    /**
     * Fill steeper and met faster than CRAWL (b/t, horizontal into the facet) => plain contact: lift on a 45 degree
     * facet = dv_y v_h (energy x2), threw a rolled m983 0.39 b/t up; plain at crawl stopped tanks on 1:1 stairs.
     */
    private static final float LIFT_NORMAL = 0.8f, CRAWL = 0.15f;
    /** Strut -y: full spring at cos 45, none at cos 60 (SuspensionUnit probes to there). */
    private static final double STRUT_FADE_START = 0.7071, STRUT_FADE_END = 0.5;
    /** Overlap past which opposed world contacts read as a wedge (humvee in a 3-block arena: launched 2.4 b/t). */
    private static final float WEDGE_DEPTH = 0.05f;
    /** Probe past a box face for a neighbour, blocks. */
    private static final double FACE_STEP = 1.0e-3;
    /** Normal component below this is no face's. */
    private static final float FACE_COMPONENT = 0.1f;
    /** Blocks; past it a pose is broken, and preparing the sweep allocates collision columns without bound. */
    private static final double MAX_GATHER = 1024;
    /** One hill triangle's vertices, section-local. */
    private final float[] triangle = new float[9];
    /** Union of the world shapes' bounds: one triangle walk per tick, not per shape. */
    private final Polytope reach = new Polytope();
    /** Fill prisms meeting {@link #reach}, walk order; per-prism section and section-local vertices. */
    private final List<Polytope> fills = new ArrayList<>();
    private final List<SurfaceSection> fillSections = new ArrayList<>();
    private float[] fillVertices = new float[9 * 16];
    /** Candidate boxes [6] origin-relative; triangles [13]: section-local vertices, origin - section corner, tag bits. */
    private float[] candidateBoxes = new float[6 * 64];
    private float[] candidateVertices = new float[13 * 64];
    /** Origin-relative bounds (min xyz, max xyz) of every candidate box and fill prism; pieces wholly outside are never posed. */
    private final float[] envelope = new float[6];
    private final Matrix3f rotationMatrix = new Matrix3f();
    private int fillCount;
    /** Hill triangles over the gather columns, walk order. */
    private final List<SurfaceSection> candidateSections = new ArrayList<>();
    private final IntArrayList candidateTriangles = new IntArrayList();

    /** Warm start: hull-frame anchor (from COM), world normal, impulses (n, t1, t2). */
    private float[] cache = new float[0];
    private int cacheCount;
    private float[] springCache = new float[0];

    // Results (task -> flush).
    boolean failed;
    boolean grounded;
    float wheelLoad;
    final List<AbstractVehicle> touched = new ArrayList<>();
    final LongArrayList blockingCells = new LongArrayList();
    double ramSpeed;
    double startX, startY, startZ;
    float startXRot, startYRot, startZRot;

    VehicleStep(AbstractVehicle vehicle) {
        this.vehicle = vehicle;
    }

    /** Tick thread. Hull pose from the entity's (steering turned it after the last updateOBBs). */
    void capture(boolean async) {
        AbstractVehicle v = vehicle;
        PhysicsEngine engine = v.physicsEngine;
        active = true;
        failed = false;
        grounded = false;
        touched.clear();
        engine.prepareInertia();
        Vec3 offset = v.centerOffset;
        pivotX = v.getX() + offset.x;
        pivotY = v.getY() + offset.y;
        pivotZ = v.getZ() + offset.z;
        entityRotation.set(v.rotYXZShared());
        var main = v.mainCubeOBB;
        hullLocal.set(main.localCenter());
        hullLocalRotation.set(main.localRotation());
        entityRotation.mul(hullLocalRotation, hullRotation);
        Vector3f arm = entityRotation.transform(new Vector3f(hullLocal));
        hullX = pivotX + arm.x;
        hullY = pivotY + arm.y;
        hullZ = pivotZ + arm.z;
        hullExtents.set(main.obb().extents());
        Vec3 center = engine.center;
        hullLocalRotation.transform(comLocal.set((float) center.x, (float) center.y, (float) center.z)).add(hullLocal);
        entityRotation.transform(arm.set(comLocal));
        comX = pivotX + arm.x;
        comY = pivotY + arm.y;
        comZ = pivotZ + arm.z;
        capturePieces(main.obb());

        inverseMass = 1.0f / Math.max(engine.mass, 1.0e-3f);
        if (engine.lockCenterRot) {
            inverseInertia.zero();
        } else {
            engine.inverseInertiaWorld(hullRotation, inverseInertia);
        }
        Vec3 movement = v.getDeltaMovement();
        velocity.set((float) movement.x, (float) movement.y, (float) movement.z);
        spin.set(engine.angularVelocity);
        if (engine.lockCenterRot) {
            spin.zero();
        }
        sprung = engine.suspended();
        wheelsWereDown = engine.wheelsGrounded();
        captureWheels(main.obb());
        captureTow();
        carried = v.weightCarried();
        acceleration.set(0, v.isNoGravity() || carried ? 0 : -PhysicsEngine.G, 0);
        engine.buoyancy(velocity, acceleration);
        if (sprung) {
            spin.y = engine.decayYawSpin(spin.y, v.onGround());
            if (wheelsWereDown) {
                engine.rollingResistance(velocity);
            }
        } else if (v.towedOnWheels() && v.onGround()) {
            engine.rollingResistance(velocity);
        }
        driveTorque(engine);
        spinRetention = v.onGround() ? 1 : engine.tumbleAirDamping;
        maxSpin = engine.effectiveMaxRotV();

        float reach = hullExtents.length();
        CompoundBody compound = v.collisionBody();
        if (compound.hasPieces()) {
            reach = Math.max(reach, compound.reachRadius());
        }
        margin = MARGIN + velocity.length() + spin.length() * reach;
        bounds = shapeBounds(v);
        gather = bounds.expandTowards(movement).expandTowards(0, v.maxUpStep(), 0).inflate(margin);
        if (!(Math.max(gather.getXsize(), Math.max(gather.getYsize(), gather.getZsize())) < MAX_GATHER)) {
            throw new IllegalStateException(v + " step bounds " + gather);
        }
        live = predicting || !async || v.physicsTrace() != null || v.level().isDebug() || v.providerNear(gather);
        v.bindStepWorld(this, gather);
        surface = DriveSurface.of(v.level()).view(v.level(), gather, PhysicsEngine.CellTops.snapshot(v.physicsRig.cursor));
        engine.prepareSuspension();
    }

    /** Sprung: the drive's dv acts at the wheels' contact plane, not the COM => squat, dive, lean. */
    private void driveTorque(PhysicsEngine engine) {
        Vector3f drive = engine.driveDv();
        double height = comY - engine.groundY();
        if (!sprung || !wheelsWereDown || drive.lengthSquared() == 0 || !(height > 0)) {
            return;
        }
        float planar = drive.length(), grip = (float) (PhysicsEngine.TYRE_GRIP * PhysicsEngine.G);
        Vector3f impulse = new Vector3f(drive).mul((planar > grip ? grip / planar : 1) / inverseMass);
        Vector3f torque = new Vector3f(0, (float) -height, 0).cross(impulse);
        spin.add(inverseInertia.transform(torque));
    }

    private AABB shapeBounds(AbstractVehicle v) {
        AABB own = v.getMainCubeOBB().obb().bounds();
        List<OBB> obbs = v.getOBBs();
        AABB all = obbs.isEmpty() ? own : own.minmax(OBB.toAABB(obbs));
        // Captured pose differs from the OBBs' by this tick's steering: one radius covers any yaw.
        double dx = hullX - v.getMainCubeOBB().obb().centerX();
        double dy = hullY - v.getMainCubeOBB().obb().centerY();
        double dz = hullZ - v.getMainCubeOBB().obb().centerZ();
        return all.expandTowards(dx, dy, dz).inflate(Math.min(hullExtents.length(), 1.0));
    }

    /** Active OBBs relative to the stale main OBB they were posed with. */
    private void capturePieces(OBB stale) {
        List<OBB> obbs = vehicle.getOBBs();
        pieceCount = obbs.size();
        pieceFrames = frames(obbs, stale, pieceFrames);
    }

    /**
     * Non-solid wheel colliders never touch the world: a centauro on its side tipped off its sloped hull side onto
     * nothing, rolled upright until the struts re-engaged and threw it over.
     */
    private void captureWheels(OBB stale) {
        wheelCount = 0;
        if (!sprung || hullRotation.transform(new Vector3f(0, 1, 0)).y >= STRUT_FADE_END) {
            return;
        }
        List<OBB> wheels = new ArrayList<>();
        for (SuspensionUnit<?> unit : vehicle.getSuspensionUnits()) {
            if (!unit.isDetached()) {
                wheels.addAll(unit.getOBBs());
            }
        }
        wheelCount = wheels.size();
        wheelFrames = frames(wheels, stale, wheelFrames);
    }

    /** Coupling geometry against the tractor's current (pre-step) pose; parted-far couplings left to {@link TowSolver}. */
    private void captureTow() {
        tow = null;
        TowLink link = vehicle.towLink();
        HitchInfo hitch = link == null ? null : link.hitch();
        if (hitch == null || link.tractor().isRemoved() || link.tractor().level() != vehicle.level()) {
            return;
        }
        AbstractVehicle tractor = link.tractor();
        TowMode mode = link.mode();
        Vec3 hitchAt = TowLinks.hitchPoint(tractor, hitch);
        Vec3 attach = TowLinks.attachPoint(vehicle, mode);
        towLength = mode == TowMode.HITCH ? -1 : (float) link.cable();
        if (hitchAt.distanceTo(attach) - Math.max(towLength, 0) > TowSolver.BREAK_MARGIN) {
            return;
        }
        tow = link;
        towPoints[0] = attach.x;
        towPoints[1] = attach.y;
        towPoints[2] = attach.z;
        towPoints[3] = hitchAt.x;
        towPoints[4] = hitchAt.y;
        towPoints[5] = hitchAt.z;
        towYawLimit = Float.NaN;
        towRoll = Float.NaN;
        if (mode == TowMode.SLING) {
            return;
        }
        TowableInfo towable = vehicle.towing.towable;
        Vec3 axle = TowLinks.worldPoint(vehicle, new Vec3(towable.axle.x, 0, towable.axle.z));
        towPoints[6] = axle.x;
        towPoints[7] = axle.y;
        towPoints[8] = axle.z;
        entityRotation.transform(towLateral.set(1, 0, 0));
        if (mode == TowMode.HITCH) {
            Vector3f own = entityRotation.transform(new Vector3f(0, 0, 1));
            Vector3f lead = tractor.rotYXZ().transform(new Vector3f(0, 0, 1));
            // Coupling behind the axle => towed tail-first.
            double flip = towable.offset.z < towable.axle.z ? Math.PI : 0;
            towYaw = (float) Mth.wrapDegrees(Math.toDegrees(Math.atan2(own.x, own.z) + flip - Math.atan2(lead.x, lead.z)));
            towYaw = (float) Math.toRadians(towYaw);
            towYawLimit = (float) Math.toRadians(TowSolver.JACKKNIFE_LIMIT);
            Quaternionf leadRotation = tractor.rotYXZ();
            leadRotation.transform(towRollAxis.set(0, 0, 1));
            Vector3f leadUp = leadRotation.transform(new Vector3f(0, 1, 0));
            Vector3f ownUp = entityRotation.transform(new Vector3f(0, 1, 0));
            towRoll = (float) Math.atan2(new Vector3f(leadUp).cross(ownUp).dot(towRollAxis), leadUp.dot(ownUp));
        }
    }

    /** Island task: coupling rows to the tractor's body. Tick-thread world points from {@link #captureTow}. */
    void towJoint(RigidSolver solver, VehicleStep tractor) {
        double[] o = solver.origin, p = towPoints;
        RigidSolver.Joint joint = solver.addJoint(tractor.body, body,
                (float) (p[3] - o[0]), (float) (p[4] - o[1]), (float) (p[5] - o[2]),
                (float) (p[0] - o[0]), (float) (p[1] - o[1]), (float) (p[2] - o[2]), towLength);
        joint.yawLimit = towYawLimit;
        joint.yaw = towYaw;
        joint.roll = towRoll;
        joint.rollAxis.set(towRollAxis);
        if (tow.mode() != TowMode.SLING) {
            joint.grip = (float) PhysicsEngine.TYRE_GRIP;
            joint.axleAnchor.set((float) (p[6] - o[0]), (float) (p[7] - o[1]), (float) (p[8] - o[2]))
                    .sub(solver.body(body).com);
            joint.axleAxis.set(towLateral);
        }
        tow.warm(joint);
    }

    /** Per OBB: centre (3), rotation (4), extents (3) in the stale main OBB's frame. */
    private static float[] frames(List<OBB> obbs, OBB stale, float[] frames) {
        int count = obbs.size();
        if (frames.length < count * 10) {
            frames = new float[count * 10];
        }
        Quaternionf inverse = new Quaternionf(stale.rotation()).conjugate();
        Vector3f local = new Vector3f();
        Quaternionf rotation = new Quaternionf();
        for (int i = 0; i < count; i++) {
            OBB obb = obbs.get(i);
            inverse.transform(local.set((float) (obb.centerX() - stale.centerX()), (float) (obb.centerY() - stale.centerY()),
                    (float) (obb.centerZ() - stale.centerZ())));
            inverse.mul(obb.rotation(), rotation);
            int o = i * 10;
            frames[o] = local.x;
            frames[o + 1] = local.y;
            frames[o + 2] = local.z;
            frames[o + 3] = rotation.x;
            frames[o + 4] = rotation.y;
            frames[o + 5] = rotation.z;
            frames[o + 6] = rotation.w;
            Vector3f extents = obb.extents();
            frames[o + 7] = extents.x;
            frames[o + 8] = extents.y;
            frames[o + 9] = extents.z;
        }
        return frames;
    }

    /** Island task. */
    void addBody(RigidSolver solver) {
        body = solver.bodyCount();
        RigidSolver.Body b = solver.addBody();
        b.inverseMass = inverseMass;
        b.inverseInertia.set(inverseInertia);
        b.com.set((float) (comX - solver.origin[0]), (float) (comY - solver.origin[1]), (float) (comZ - solver.origin[2]));
        b.velocity.set(velocity);
        b.spin.set(spin);
        b.acceleration.set(acceleration);
        b.spinRetention = spinRetention;
        b.maxSpin = maxSpin;
    }

    /** Island task: vehicle-vehicle (active OBBs) shapes, origin-relative. */
    void poseShapes(double[] origin) {
        float hx = (float) (hullX - origin[0]), hy = (float) (hullY - origin[1]), hz = (float) (hullZ - origin[2]);
        Vector3f offset = new Vector3f();
        Quaternionf rotation = new Quaternionf();
        hullShapeCount = 0;
        for (int i = 0; i < pieceCount; i++) {
            int o = i * 10;
            hullRotation.transform(offset.set(pieceFrames[o], pieceFrames[o + 1], pieceFrames[o + 2]));
            hullRotation.mul(rotation.set(pieceFrames[o + 3], pieceFrames[o + 4], pieceFrames[o + 5], pieceFrames[o + 6]),
                    rotation);
            Polytope piece = shape(hullShapes, hullShapeCount++);
            piece.box(hx + offset.x, hy + offset.y, hz + offset.z, rotation, pieceFrames[o + 7], pieceFrames[o + 8],
                    pieceFrames[o + 9]);
            piece.tag = i;
        }
    }

    /**
     * World shapes (main, compound pieces, wheels; frame index = tag), origin-relative: {@link #worldFrames},
     * {@link #worldHulls}; {@code handles} => {@link #worldHandles} current.
     */
    private void worldFrames(double[] origin, boolean handles) {
        float hx = (float) (hullX - origin[0]), hy = (float) (hullY - origin[1]), hz = (float) (hullZ - origin[2]);
        CompoundBody compound = vehicle.collisionBody();
        int count = 1 + compound.pieceCount() + wheelCount;
        if (worldHulls.length < count) {
            worldHulls = Arrays.copyOf(worldHulls, count);
            worldHandles = Arrays.copyOf(worldHandles, count);
            worldFrames = new float[count * NarrowNative.FRAME];
        }
        worldFrameCount = count;
        ConvexHull mainShape = vehicle.mainCubeOBB.hull();
        frame(0, mainShape, hx, hy, hz, hullRotation, hullExtents.x, hullExtents.y, hullExtents.z,
                mainShape == null ? hullExtents.length() : Float.NaN, handles);
        Vector3f offset = new Vector3f();
        Quaternionf rotation = new Quaternionf();
        for (int i = 0, pieces = compound.pieceCount(); i < pieces; i++) {
            hullRotation.transform(offset.set(compound.pieceOffset(i)));
            hullRotation.mul(compound.pieceRotation(i), rotation);
            frame(1 + i, compound.pieceShape(i), hx + offset.x, hy + offset.y, hz + offset.z, rotation, 0, 0, 0, Float.NaN,
                    handles);
        }
        for (int i = 0, base = 1 + compound.pieceCount(); i < wheelCount; i++) {
            int o = i * 10;
            hullRotation.transform(offset.set(wheelFrames[o], wheelFrames[o + 1], wheelFrames[o + 2]));
            float px = hx + offset.x, py = hy + offset.y, pz = hz + offset.z;
            hullRotation.mul(rotation.set(wheelFrames[o + 3], wheelFrames[o + 4], wheelFrames[o + 5], wheelFrames[o + 6]),
                    rotation);
            float ex = wheelFrames[o + 7], ey = wheelFrames[o + 8], ez = wheelFrames[o + 9];
            frame(base + i, null, px, py, pz, rotation, ex, ey, ez, offset.set(ex, ey, ez).length(), handles);
        }
    }

    private void frame(int s, @Nullable ConvexHull hull, float x, float y, float z, Quaternionf rotation, float ex, float ey,
                       float ez, float radius, boolean handles) {
        float[] f = worldFrames;
        int o = s * NarrowNative.FRAME;
        f[o] = x;
        f[o + 1] = y;
        f[o + 2] = z;
        f[o + 3] = rotation.x;
        f[o + 4] = rotation.y;
        f[o + 5] = rotation.z;
        f[o + 6] = rotation.w;
        f[o + 7] = ex;
        f[o + 8] = ey;
        f[o + 9] = ez;
        f[o + 10] = radius;
        if (worldHulls[s] != hull) {
            worldHulls[s] = hull;
            worldHandles[s] = 0;
        }
        if (handles && hull != null && worldHandles[s] == 0) {
            worldHandles[s] = NarrowNative.hull(hull);
        }
    }

    /** {@link #worldFrames} posed; {@code cull} => shapes whose posed local bounds miss the envelope (+ margin) skipped. */
    private void poseWorldShapes(boolean cull) {
        worldShapeCount = 0;
        Quaternionf rotation = new Quaternionf();
        Vector3f scratch = new Vector3f();
        float[] f = worldFrames;
        for (int s = 0; s < worldFrameCount; s++) {
            int o = s * NarrowNative.FRAME;
            ConvexHull hull = worldHulls[s];
            rotation.set(f[o + 3], f[o + 4], f[o + 5], f[o + 6]);
            if (cull && !(hull == null ? reaches(f[o], f[o + 1], f[o + 2], f[o + 10])
                    : reaches(hull, f[o], f[o + 1], f[o + 2], rotation, scratch))) {
                continue;
            }
            Polytope shape = shape(worldShapes, worldShapeCount++);
            if (hull != null) {
                shape.hull(hull, f[o], f[o + 1], f[o + 2], rotation);
            } else {
                shape.box(f[o], f[o + 1], f[o + 2], rotation, f[o + 7], f[o + 8], f[o + 9]);
            }
            shape.tag = s;
        }
    }

    /** Hull posed at (x, y, z) by rotation: its local bounds box, posed, meets the envelope. */
    private boolean reaches(ConvexHull hull, float x, float y, float z, Quaternionf rotation, Vector3f scratch) {
        rotation.transform(scratch.set((hull.minX() + hull.maxX()) * 0.5f, (hull.minY() + hull.maxY()) * 0.5f,
                (hull.minZ() + hull.maxZ()) * 0.5f));
        float cx = x + scratch.x, cy = y + scratch.y, cz = z + scratch.z;
        float hx = (hull.maxX() - hull.minX()) * 0.5f, hy = (hull.maxY() - hull.minY()) * 0.5f, hz = (hull.maxZ() - hull.minZ()) * 0.5f;
        Matrix3f r = rotation.get(this.rotationMatrix);
        return reaches(cx, cy, cz, Math.abs(r.m00) * hx + Math.abs(r.m10) * hy + Math.abs(r.m20) * hz,
                Math.abs(r.m01) * hx + Math.abs(r.m11) * hy + Math.abs(r.m21) * hz,
                Math.abs(r.m02) * hx + Math.abs(r.m12) * hy + Math.abs(r.m22) * hz);
    }

    private boolean reaches(float x, float y, float z, float radius) {
        return reaches(x, y, z, radius, radius, radius);
    }

    /** Half extents (rx, ry, rz) about (x, y, z); slack (posing rounding) => a skipped shape's bounds miss for sure. */
    private boolean reaches(float x, float y, float z, float rx, float ry, float rz) {
        float slack = 1.0e-3f + margin;
        rx = rx * 1.001f + slack;
        ry = ry * 1.001f + slack;
        rz = rz * 1.001f + slack;
        float[] e = envelope;
        return x - rx <= e[3] && x + rx >= e[0] && y - ry <= e[4] && y + ry >= e[1] && z - rz <= e[5] && z + rz >= e[2];
    }

    private static Polytope shape(List<Polytope> pool, int index) {
        if (index == pool.size()) {
            pool.add(new Polytope());
        }
        return pool.get(index);
    }

    /** Island task: block, drive-surface wedge and deck contacts (static body 0), wheel springs. */
    /** {@code native_} null => Java narrowphase. */
    void worldContacts(RigidSolver solver, ConvexContacts narrow, @Nullable NarrowNative native_, ContactBuffer buffer) {
        PhysicsRig rig = vehicle.physicsRig;
        double[] origin = solver.origin;
        buffer.clear();
        boxes.clear();
        ChunkCollisionCache.collectBoxes(rig.sections, gather, boxes);
        sessions.clear();
        sessionBase.clear();
        providerBoxes.clear();
        if (live && !predicting) {
            vehicle.collectProviderBoxes(gather, sessions, sessionBase, providerBoxes);
        }
        sessionBase.add(providerBoxes.size());
        DriveSurface.View view = surface;
        CarrierLink link = vehicle.carrierLink();
        BoxBuffer deck = link.active() ? link.deckBoxes() : null;
        int deckCount = deck == null ? 0 : deck.size();
        candidateSections.clear();
        candidateTriangles.clear();
        view.triangles(gather, (section, t) -> {
            candidateSections.add(section);
            candidateTriangles.add(t);
        });
        // Nothing solid in reach (open air, open water): no shape to pose, no contact to find.
        boolean reachable = !boxes.isEmpty() || !candidateTriangles.isEmpty() || deckCount > 0 || providerBoxes.size() > 0;
        worldShapeCount = 0;
        worldFrameCount = 0;
        // Deck / provider boxes are collided by the Java narrowphase per posed shape: no envelope.
        boolean cull = deckCount == 0 && providerBoxes.size() == 0;
        boolean javaPosed = native_ == null || !cull;
        if (reachable) {
            candidates(origin);
            worldFrames(origin, native_ != null);
            if (javaPosed) {
                poseWorldShapes(cull);
            }
        }
        int nativeCount = 0;
        if (native_ != null && worldFrameCount > 0) {
            nativeCount = native_.worldContacts(worldFrameCount, worldFrames, worldHandles, cull, envelope, candidateBoxes,
                    boxes.size(), candidateVertices, candidateTriangles.size(), margin, FILL_DEPTH);
        } else {
            reach.enclose(worldShapes, worldShapeCount);
        }
        fillCount = 0;
        fillSections.clear();
        for (int c = 0, size = worldShapeCount > 0 && native_ == null ? candidateTriangles.size() : 0; c < size; c++) {
            SurfaceSection section = candidateSections.get(c);
            int t = candidateTriangles.getInt(c);
            float ox = candidateVertices[c * 13 + 9], oy = candidateVertices[c * 13 + 10], oz = candidateVertices[c * 13 + 11];
            System.arraycopy(candidateVertices, c * 13, triangle, 0, 9);
            Polytope fill = shape(fills, fillCount);
            if (fill.prismMeets(triangle, 0, ox, oy, oz, FILL_DEPTH, reach, margin)) {
                fill.tag = t;
                fillSections.add(section);
                if (fillVertices.length < (fillCount + 1) * 9) {
                    fillVertices = Arrays.copyOf(fillVertices, fillVertices.length * 2);
                }
                System.arraycopy(triangle, 0, fillVertices, fillCount * 9, 9);
                fillCount++;
            }
        }
        // Reduced per shape: across shapes the kept deepest point flips between pieces (see RigidStep#hullContacts).
        // Native: shape = frame index (culled frames meet nothing); Java posed all when deck / provider boxes.
        for (int s = 0, next = 0, shapes = native_ != null ? worldFrameCount : worldShapeCount; s < shapes; s++) {
            Polytope shape = javaPosed ? worldShapes.get(s) : null;
            int from = buffer.size();
            int fillFrom;
            if (native_ != null) {
                for (; next < nativeCount && native_.shape(next) == s && native_.source(next) >= 0; next++) {
                    copy(native_, next, buffer);
                }
                fillFrom = buffer.size();
                while (next < nativeCount && native_.shape(next) == s) {
                    int source = native_.source(next), before = buffer.size(), c = -1 - source;
                    for (; next < nativeCount && native_.shape(next) == s && native_.source(next) == source; next++) {
                        copy(native_, next, buffer);
                    }
                    System.arraycopy(candidateVertices, c * 13, triangle, 0, 9);
                    faceNormal(buffer, before, candidateSections.get(c), candidateTriangles.getInt(c), triangle, origin);
                }
            } else {
                for (int i = 0, size = boxes.size(); i < size; i++) {
                    float minX = (float) (boxes.minX(i) - origin[0]), minY = (float) (boxes.minY(i) - origin[1]),
                            minZ = (float) (boxes.minZ(i) - origin[2]), maxX = (float) (boxes.maxX(i) - origin[0]),
                            maxY = (float) (boxes.maxY(i) - origin[1]), maxZ = (float) (boxes.maxZ(i) - origin[2]);
                    if (Polytope.boundsMeet(minX, minY, minZ, maxX, maxY, maxZ, shape, margin)) {
                        box.box(minX, minY, minZ, maxX, maxY, maxZ);
                        box.tag = i;
                        narrow.collide(box, shape, margin, buffer);
                    }
                }
                fillFrom = buffer.size();
                for (int f = 0; f < fillCount; f++) {
                    Polytope fill = fills.get(f);
                    if (fill.boundsMeet(shape, margin)) {
                        int before = buffer.size();
                        narrow.collide(fill, shape, margin, buffer);
                        System.arraycopy(fillVertices, f * 9, triangle, 0, 9);
                        faceNormal(buffer, before, fillSections.get(f), fill.tag, triangle, origin);
                    }
                }
            }
            int fillTo = buffer.size();
            if (!cull) {
                if (deck != null) {
                    deckContacts(narrow, buffer, shape, link, deck, origin);
                }
                providerContacts(narrow, buffer, shape, deckCount, origin);
            }
            // Fill => face-normal lift; off-triangle points and steep sides dropped. Blocks inside the hill's cover band
            // are the hill's (step faces, cut crests); other block support => top-face lift.
            buffer.retain(from, k -> {
                if (k >= fillTo) {
                    return true;
                }
                if (k >= fillFrom) {
                    if (buffer.flags(k) == OFF_FACE || buffer.normalY(k) <= SUPPORT_NORMAL) {
                        return false;
                    }
                    if (buffer.normalY(k) > LIFT_NORMAL
                            || -(velocity.x * buffer.normalX(k) + velocity.z * buffer.normalZ(k)) < CRAWL) {
                        buffer.flag(k, LIFT);
                    }
                    return true;
                }
                if (!exposed(buffer, k, boxes, origin)) {
                    return false;
                }
                // Block-side point = contact clamped into its box: edge-edge midpoints float up to ~0.6 off both edges.
                // Downward normal => ceiling underside (slab-thick cave roof under a hill), never the hill's.
                int i = buffer.tagA(k);
                if (buffer.normalY(k) >= -SUPPORT_NORMAL && view.covers(Mth.clamp(buffer.x(k) + origin[0], boxes.minX(i), boxes.maxX(i)),
                        Mth.clamp(buffer.y(k) + origin[1], boxes.minY(i), boxes.maxY(i)),
                        Mth.clamp(buffer.z(k) + origin[2], boxes.minZ(i), boxes.maxZ(i)),
                        buffer.y(k) + buffer.normalY(k) * buffer.separation(k) * 0.5 + origin[1])) {
                    return false;
                }
                if (buffer.normalY(k) > SUPPORT_NORMAL) {
                    buffer.flag(k, LIFT);
                    return topFace(buffer, k, boxes, origin);
                }
                return true;
            });
            buffer.reduce(from);
        }
        contactFrom = solver.contactCount();
        // Upright sprung belly frictionless, wheels down or not: high-centred on stair edges (tracks hanging) a 0.5
        // belly braked a T-90 to a stop on a 1:4 hill. Lift contacts => no slide down slopes.
        // Towed on wheels: rolls; the axle row grips sideways (unsprung MIM-104 dragged its belly at 0.5, train stalled).
        // Carried (afloat): heave spring presses a grounded bow into the bed => 0.5 grip >> screw thrust, never backed off.
        boolean rolling = tow != null && tow.mode() != TowMode.SLING;
        float friction = rolling || carried || sprung && hullRotation.transform(new Vector3f(0, 1, 0)).y > BELLY_SPIN_UP ? 0 : FRICTION;
        float restitution = vehicle.physicsEngine.bounce;
        int deckSupports = 0;
        if (contactTags.length < buffer.size()) {
            contactTags = new int[Math.max(buffer.size(), contactTags.length * 2)];
        }
        for (int k = 0, size = buffer.size(); k < size; k++) {
            contactTags[k] = buffer.tagA(k);
            boolean wedged = false;
            if (buffer.separation(k) < -WEDGE_DEPTH) {
                for (int j = 0; j < size && !wedged; j++) {
                    wedged = buffer.separation(j) < -WEDGE_DEPTH && buffer.normalX(k) * buffer.normalX(j)
                            + buffer.normalY(k) * buffer.normalY(j) + buffer.normalZ(k) * buffer.normalZ(j) < -0.5f;
                }
            }
            RigidSolver.Contact c = solver.addContact(0, body, buffer.x(k), buffer.y(k), buffer.z(k),
                    buffer.normalX(k), buffer.normalY(k), buffer.normalZ(k), buffer.separation(k));
            // Deck: belly is where a sprung rider rests (wheels probe blocks only); frictionless => slid off a pitching boat.
            c.friction = buffer.tagA(k) < 0 ? FRICTION : friction;
            c.restitution = restitution;
            c.lift = buffer.flags(k) == LIFT;
            c.wedged = wedged;
            warm(c, c.anchorB);
            if (buffer.tagA(k) < 0 && -1 - buffer.tagA(k) < deckCount && buffer.normalY(k) > SUPPORT_NORMAL) {
                deckSupports++;
            }
        }
        contactTo = solver.contactCount();
        link.supportContacts(deckSupports);
        springs(solver);
    }

    private void deckContacts(ConvexContacts narrow, ContactBuffer buffer, Polytope shape, CarrierLink link, BoxBuffer deck,
                              double[] origin) {
        Quaternionf rotation = link.deckRotation();
        Vector3f centre = new Vector3f();
        for (int i = 0, size = deck.size(); i < size; i++) {
            rotation.transform(centre.set((float) ((deck.minX(i) + deck.maxX(i)) * 0.5), (float) ((deck.minY(i) + deck.maxY(i)) * 0.5),
                    (float) ((deck.minZ(i) + deck.maxZ(i)) * 0.5)));
            box.box((float) (link.pivotX() + centre.x - origin[0]), (float) (link.pivotY() + centre.y - origin[1]),
                    (float) (link.pivotZ() + centre.z - origin[2]), rotation, (float) ((deck.maxX(i) - deck.minX(i)) * 0.5),
                    (float) ((deck.maxY(i) - deck.minY(i)) * 0.5), (float) ((deck.maxZ(i) - deck.minZ(i)) * 0.5));
            box.tag = -1 - i;
            if (box.boundsMeet(shape, margin)) {
                narrow.collide(box, shape, margin, buffer);
            }
        }
    }

    private void providerContacts(ConvexContacts narrow, ContactBuffer buffer, Polytope shape, int deckCount, double[] origin) {
        OrientedBoxes boxes = providerBoxes;
        Quaternionf rotation = new Quaternionf();
        for (int i = 0, size = boxes.size(); i < size; i++) {
            rotation.set(boxes.frame(i, 0), boxes.frame(i, 1), boxes.frame(i, 2), boxes.frame(i, 3));
            box.box((float) (boxes.centreX(i) - origin[0]), (float) (boxes.centreY(i) - origin[1]), (float) (boxes.centreZ(i) - origin[2]),
                    rotation, boxes.frame(i, 4), boxes.frame(i, 5), boxes.frame(i, 6));
            box.tag = -1 - deckCount - i;
            if (box.boundsMeet(shape, margin)) {
                narrow.collide(box, shape, margin, buffer);
            }
        }
    }

    /** Tick thread (live island): loaded provider boxes reported, sessions closed. */
    private void endSessions(RigidSolver solver) {
        if (sessions.isEmpty()) {
            return;
        }
        int deckCount = vehicle.carrierLink().active() ? vehicle.carrierLink().deckBoxes().size() : 0;
        for (int k = contactFrom; k < contactTo; k++) {
            int i = -1 - deckCount - contactTags[k - contactFrom];
            if (i < 0 || solver.contact(k).totalNormalImpulse <= 0) {
                continue;
            }
            int s = 0;
            while (sessionBase.getInt(s + 1) <= i) {
                s++;
            }
            sessions.get(s).touched(i - sessionBase.getInt(s));
        }
        for (int s = 0, size = sessions.size(); s < size; s++) {
            sessions.get(s).end();
        }
        sessions.clear();
    }

    private void springs(RigidSolver solver) {
        PhysicsEngine engine = vehicle.physicsEngine;
        springFrom = solver.springCount();
        if (!sprung) {
            springTo = springFrom;
            return;
        }
        PhysicsRig rig = vehicle.physicsRig;
        engine.simulateSuspensions(rig, surface);
        List<SuspensionUnit<?>> units = vehicle.getSuspensionUnits();
        int slot = 0;
        for (int u = 0, size = units.size(); u < size; u++) {
            SuspensionUnit<?> unit = units.get(u);
            int rows = unit.contactCount();
            if (springCache.length < (slot + rows) * 2) {
                springCache = Arrays.copyOf(springCache, Math.max(springCache.length * 2, (slot + rows) * 2));
            }
            if (!unit.isGrounded()) {
                Arrays.fill(springCache, slot * 2, (slot + rows) * 2, 0);
                slot += rows;
                continue;
            }
            double upright = -unit.getSuspensionDirection().y;
            float ratio = (float) (1 / upright);
            // 1/cos ratio + a hard cut at 60 degrees: a strut re-entering the ground deep threw a centauro on its side upright.
            double weight = unit.contactWeight() * Mth.clamp((upright - STRUT_FADE_END) / (STRUT_FADE_START - STRUT_FADE_END), 0, 1);
            float stiffness = (float) (PhysicsHelper.forcePerTick(unit.getData().getSpringStiffness()) * weight);
            float damping = (float) (PhysicsHelper.dampingPerTick(unit.getData().getDamping()) * weight);
            for (int row = 0; row < rows; row++, slot++) {
                RigidSolver.Spring spring = solver.addSpring(body, (float) (unit.contactX(row) - solver.origin[0]),
                        (float) (unit.contactY(row) - solver.origin[1]), (float) (unit.contactZ(row) - solver.origin[2]), 0, 1, 0);
                spring.ratio = ratio;
                spring.compression = (float) unit.contactCompression(row);
                spring.min = (float) unit.contactMin(row);
                spring.max = weight < unit.contactWeight() ? Float.MAX_VALUE : (float) unit.contactMax(row);
                spring.stiffness = stiffness;
                spring.damping = damping;
                spring.impulse = springCache[slot * 2];
                spring.stopImpulse = springCache[slot * 2 + 1];
            }
        }
        springTo = solver.springCount();
    }

    /** Previous tick's impulses for the nearest matching point. */
    private void warm(RigidSolver.Contact c, Vector3f anchor) {
        Vector3f local = hullRotation.transformInverse(new Vector3f(anchor));
        int best = -1;
        float bestDistance = MATCH_DISTANCE_SQ;
        for (int i = 0; i < cacheCount; i++) {
            int o = i * 9;
            float dx = cache[o] - local.x, dy = cache[o + 1] - local.y, dz = cache[o + 2] - local.z;
            float distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance && cache[o + 3] * c.normal.x + cache[o + 4] * c.normal.y + cache[o + 5] * c.normal.z > MATCH_NORMAL) {
                bestDistance = distance;
                best = i;
            }
        }
        if (best >= 0) {
            c.normalImpulse = cache[best * 9 + 6];
            c.tangentImpulse1 = cache[best * 9 + 7];
            c.tangentImpulse2 = cache[best * 9 + 8];
        }
    }

    /** Island task, after the solve: results, caches, impacts, then rotation + swept translation. */
    void finish(RigidSolver solver) {
        RigidSolver.Body b = solver.body(body);
        PhysicsEngine engine = vehicle.physicsEngine;
        PhysicsRig rig = vehicle.physicsRig;
        storeCache(solver);
        endSessions(solver);
        blockingCells.clear();
        ramSpeed = 0;
        double impactX = 0, impactY = 0, impactZ = 0, impactWeight = 0;
        boolean belly = false;
        double[] origin = solver.origin;
        for (int k = contactFrom; k < contactTo; k++) {
            RigidSolver.Contact c = solver.contact(k);
            if (c.totalNormalImpulse <= 0) {
                continue;
            }
            belly = true;
            impactWeight += c.totalNormalImpulse;
            impactX += c.totalNormalImpulse * (c.anchorB.x + b.com.x);
            impactY += c.totalNormalImpulse * (c.anchorB.y + b.com.y);
            impactZ += c.totalNormalImpulse * (c.anchorB.z + b.com.z);
            if (c.normal.y > SUPPORT_NORMAL) {
                grounded = true;
                continue;
            }
            double px = c.anchorB.x + b.com.x + origin[0] - c.normal.x * CELL_PROBE;
            double py = c.anchorB.y + b.com.y + origin[1] - c.normal.y * CELL_PROBE;
            double pz = c.anchorB.z + b.com.z + origin[2] - c.normal.z * CELL_PROBE;
            long cell = BlockPos.asLong(Mth.floor(px), Mth.floor(py), Mth.floor(pz));
            if (!blockingCells.contains(cell)) {
                blockingCells.add(cell);
            }
            ramSpeed = Math.max(ramSpeed, -c.approach());
        }
        wheelLoad = 0;
        double groundWeight = 0, groundY = 0;
        for (int k = springFrom; k < springTo; k++) {
            RigidSolver.Spring s = solver.spring(k);
            float load = s.totalImpulse * s.ratio;
            wheelLoad += load;
            groundWeight += load;
            groundY += load * (s.anchor.y + b.com.y + origin[1]);
        }
        float bellyLoad = 0;
        for (int k = contactFrom; k < contactTo; k++) {
            RigidSolver.Contact c = solver.contact(k);
            if (c.normal.y > SUPPORT_NORMAL) {
                bellyLoad += c.totalNormalImpulse;
            }
        }
        grounded |= engine.wheelsGrounded();
        if (sprung && belly && hullRotation.transform(new Vector3f(0, 1, 0)).y > BELLY_SPIN_UP) {
            List<SuspensionUnit<?>> units = vehicle.getSuspensionUnits();
            int down = 0;
            for (int u = 0, size = units.size(); u < size; u++) {
                down += units.get(u).isGrounded() ? 1 : 0;
            }
            // Frictionless belly rows kept every kick on a box edge: RoughBench flips 7/96 -> 0/96 (pre-rewrite);
            // landing straddling a terrace edge rolled an M983 over at 7 deg/t. Coulomb belly FORBIDDEN (tripped
            // flat turns). Yaw kept.
            if (down * 2 < units.size()) {
                b.spin.x *= BELLY_SPIN_RETENTION;
                b.spin.z *= BELLY_SPIN_RETENTION;
            }
        }

        // |dv| the contacts cost beyond free flight; gravity support alone stays far under the threshold.
        double ex = b.velocity.x - velocity.x - acceleration.x, ey = b.velocity.y - velocity.y - acceleration.y;
        double ez = b.velocity.z - velocity.z - acceleration.z;
        double velocityDiff = Math.sqrt(ex * ex + ey * ey + ez * ez);
        rig.impactVelocityDiff = 0;
        if (impactWeight > 0 && velocityDiff * PhysicsHelper.TICKS_PER_SECOND > vehicle.impactSpeedThreshold()) {
            rig.impactVelocityDiff = velocityDiff;
            rig.impactPos = new Vec3(impactX / impactWeight + origin[0], impactY / impactWeight + origin[1],
                    impactZ / impactWeight + origin[2]);
        }
        if (RigidStep.DEBUG) {
            double hx = velocity.x, hz = velocity.z, hl = Math.sqrt(hx * hx + hz * hz);
            StringBuilder line = new StringBuilder();
            float along = 0;
            for (int k = contactFrom; k < contactTo; k++) {
                RigidSolver.Contact c = solver.contact(k);
                if (hl > 1e-4) {
                    along += (float) ((c.normal.x * hx + c.normal.z * hz) / hl * c.totalNormalImpulse * inverseMass);
                }
                if (Boolean.getBoolean("combinedassault.rigid.debugAll") || c.totalNormalImpulse > 1e-5 && Math.abs(c.normal.y) < 0.999f) {
                    line.append(String.format(java.util.Locale.ROOT, " [%s n %.2f,%.2f,%.2f s %.3f dv %.4f p %.2f,%.2f,%.2f]", c.lift ? "L" : "", c.normal.x, c.normal.y, c.normal.z,
                            c.debugSeparation(), c.totalNormalImpulse * inverseMass, c.anchorB.x + b.com.x + origin[0], c.anchorB.y + b.com.y + origin[1], c.anchorB.z + b.com.z + origin[2]));
                }
            }
            com.wf.combinedassault.CombinedAssault.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "[climbdbg] %d t%d com %.2f,%.2f,%.2f up %.3f w %.4f,%.4f,%.4f vin %.3f,%.3f,%.3f vout %.3f,%.3f,%.3f share %.2f contacts %d along %.4f%s",
                    vehicle.getId(), vehicle.tickCount, b.com.x + origin[0], b.com.y + origin[1], b.com.z + origin[2], hullRotation.transform(new Vector3f(0, 1, 0)).y, b.spin.x, b.spin.y, b.spin.z,
                    velocity.x, velocity.y, velocity.z, b.velocity.x, b.velocity.y, b.velocity.z,
                    wheelLoad * inverseMass / PhysicsEngine.G, contactTo - contactFrom, along, line));
        }
        engine.solved(b.spin, wheelLoad, bellyLoad, groundWeight > 0 ? groundY / groundWeight : Double.NaN, blockingCells, ramSpeed,
                rig.cursor);
        vehicle.integrateStep(this, b.dp, b.dq, b.velocity);
    }

    /**
     * Block contact k: normal components across faces a neighbouring box covers removed, separation scaled by what is
     * left; false => nothing left. Boxes merge per section: SAT edge axis at a seam, n (0, 0.56, -0.83), stopped every
     * wheeled vehicle at z 32 and flipped it.
     */
    private static boolean exposed(ContactBuffer buffer, int k, BoxBuffer boxes, double[] origin) {
        int i = buffer.tagA(k);
        double x = Mth.clamp(buffer.x(k) + origin[0], boxes.minX(i) + FACE_STEP, boxes.maxX(i) - FACE_STEP);
        double y = Mth.clamp(buffer.y(k) + origin[1], boxes.minY(i) + FACE_STEP, boxes.maxY(i) - FACE_STEP);
        double z = Mth.clamp(buffer.z(k) + origin[2], boxes.minZ(i) + FACE_STEP, boxes.maxZ(i) - FACE_STEP);
        float nx = buffer.normalX(k), ny = buffer.normalY(k), nz = buffer.normalZ(k);
        if (nx > FACE_COMPONENT && solid(boxes, boxes.maxX(i) + FACE_STEP, y, z)
                || nx < -FACE_COMPONENT && solid(boxes, boxes.minX(i) - FACE_STEP, y, z)) {
            nx = 0;
        }
        if (ny > FACE_COMPONENT && solid(boxes, x, boxes.maxY(i) + FACE_STEP, z)
                || ny < -FACE_COMPONENT && solid(boxes, x, boxes.minY(i) - FACE_STEP, z)) {
            ny = 0;
        }
        if (nz > FACE_COMPONENT && solid(boxes, x, y, boxes.maxZ(i) + FACE_STEP)
                || nz < -FACE_COMPONENT && solid(boxes, x, y, boxes.minZ(i) - FACE_STEP)) {
            nz = 0;
        }
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nx == buffer.normalX(k) && ny == buffer.normalY(k) && nz == buffer.normalZ(k)) {
            return true;
        }
        if (length < FACE_COMPONENT) {
            return false;
        }
        buffer.set(k, buffer.x(k), buffer.y(k), buffer.z(k), nx / length, ny / length, nz / length,
                buffer.separation(k) * length, buffer.flags(k));
        return true;
    }

    private static boolean solid(BoxBuffer boxes, double x, double y, double z) {
        for (int i = 0, size = boxes.size(); i < size; i++) {
            if (x > boxes.minX(i) && x < boxes.maxX(i) && y > boxes.minY(i) && y < boxes.maxY(i) && z > boxes.minZ(i)
                    && z < boxes.maxZ(i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Block support contact k => box top normal, top separation, when the hull point is over the box; else false (the
     * neighbouring box carries it). Boxes merge per section: SAT edge axes at section seams read n (0.57, 0.81) =>
     * measured as a steep ramp, launched one side (humvee rolled weaving along a terrace).
     */
    private static boolean topFace(ContactBuffer buffer, int k, BoxBuffer boxes, double[] origin) {
        int i = buffer.tagA(k);
        float half = buffer.separation(k) * 0.5f;
        float hx = buffer.x(k) + buffer.normalX(k) * half, hy = buffer.y(k) + buffer.normalY(k) * half,
                hz = buffer.z(k) + buffer.normalZ(k) * half;
        double wx = hx + origin[0], wz = hz + origin[2];
        if (wx < boxes.minX(i) - TOP_SLACK || wx > boxes.maxX(i) + TOP_SLACK || wz < boxes.minZ(i) - TOP_SLACK
                || wz > boxes.maxZ(i) + TOP_SLACK) {
            return false;
        }
        float gap = (float) (hy + origin[1] - boxes.maxY(i));
        buffer.set(k, hx, hy - gap * 0.5f, hz, 0, 1, 0, gap, LIFT);
        return true;
    }

    /** {@link #candidateBoxes}, {@link #candidateVertices}, envelope. Degenerate triangle => NaN prism (meets nothing): unbounded. */
    private void candidates(double[] origin) {
        int boxCount = boxes.size(), triangleCount = candidateTriangles.size();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        if (candidateBoxes.length < boxCount * 6) {
            candidateBoxes = new float[Math.max(boxCount * 6, candidateBoxes.length * 2)];
        }
        float[] b = candidateBoxes;
        for (int i = 0; i < boxCount; i++) {
            b[i * 6] = (float) (boxes.minX(i) - origin[0]);
            b[i * 6 + 1] = (float) (boxes.minY(i) - origin[1]);
            b[i * 6 + 2] = (float) (boxes.minZ(i) - origin[2]);
            b[i * 6 + 3] = (float) (boxes.maxX(i) - origin[0]);
            b[i * 6 + 4] = (float) (boxes.maxY(i) - origin[1]);
            b[i * 6 + 5] = (float) (boxes.maxZ(i) - origin[2]);
            minX = Math.min(minX, b[i * 6]);
            minY = Math.min(minY, b[i * 6 + 1]);
            minZ = Math.min(minZ, b[i * 6 + 2]);
            maxX = Math.max(maxX, b[i * 6 + 3]);
            maxY = Math.max(maxY, b[i * 6 + 4]);
            maxZ = Math.max(maxZ, b[i * 6 + 5]);
        }
        if (candidateVertices.length < triangleCount * 13) {
            candidateVertices = new float[Math.max(triangleCount * 13, candidateVertices.length * 2)];
        }
        float[] v = candidateVertices;
        for (int c = 0; c < triangleCount; c++) {
            SurfaceSection section = candidateSections.get(c);
            int t = candidateTriangles.getInt(c), o = c * 13;
            section.vertices(t, triangle);
            System.arraycopy(triangle, 0, v, o, 9);
            float ox = (float) (origin[0] - section.sx * 16), oy = (float) (origin[1] - section.sy * 16),
                    oz = (float) (origin[2] - section.sz * 16);
            v[o + 9] = ox;
            v[o + 10] = oy;
            v[o + 11] = oz;
            v[o + 12] = Float.intBitsToFloat(t);
            float ax = triangle[3] - triangle[0], ay = triangle[4] - triangle[1], az = triangle[5] - triangle[2];
            float bx = triangle[6] - triangle[0], by = triangle[7] - triangle[1], bz = triangle[8] - triangle[2];
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float scale = FILL_DEPTH / (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (!(scale < Float.POSITIVE_INFINITY)) {
                continue;
            }
            // Prism = triangle + its copy at -n depth.
            float dx = Math.min(0, -nx * scale), dy = Math.min(0, -ny * scale), dz = Math.min(0, -nz * scale);
            float ex = Math.max(0, -nx * scale), ey = Math.max(0, -ny * scale), ez = Math.max(0, -nz * scale);
            for (int k = 0; k < 9; k += 3) {
                float x = triangle[k] - ox, y = triangle[k + 1] - oy, z = triangle[k + 2] - oz;
                minX = Math.min(minX, x + dx);
                minY = Math.min(minY, y + dy);
                minZ = Math.min(minZ, z + dz);
                maxX = Math.max(maxX, x + ex);
                maxY = Math.max(maxY, y + ey);
                maxZ = Math.max(maxZ, z + ez);
            }
        }
        envelope[0] = minX;
        envelope[1] = minY;
        envelope[2] = minZ;
        envelope[3] = maxX;
        envelope[4] = maxY;
        envelope[5] = maxZ;
    }

    private static void copy(NarrowNative native_, int k, ContactBuffer buffer) {
        buffer.add(native_.x(k), native_.y(k), native_.z(k), native_.normalX(k), native_.normalY(k), native_.normalZ(k),
                native_.separation(k), native_.tagA(k), native_.tagB(k));
    }

    /**
     * Fill contacts [from, size) of triangle t: hull point over the triangle => face normal, plane separation; else
     * OFF_FACE. SAT edge axes at triangle seams (section borders) gave n (0.57, 0.81, -0.12) lift
     * contacts: sideways kicks, humvee rolled weaving at 12 m/s.
     */
    private static void faceNormal(ContactBuffer buffer, int from, SurfaceSection section, int t, float[] v, double[] origin) {
        float ax = v[3] - v[0], ay = v[4] - v[1], az = v[5] - v[2];
        float bx = v[6] - v[0], by = v[7] - v[1], bz = v[8] - v[2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= length;
        ny /= length;
        nz /= length;
        // Vertex 0 relative to the solver origin.
        float x0 = (float) (section.sx * 16 - origin[0]) + v[0], y0 = (float) (section.sy * 16 - origin[1]) + v[1],
                z0 = (float) (section.sz * 16 - origin[2]) + v[2];
        for (int k = from, size = buffer.size(); k < size; k++) {
            float half = buffer.separation(k) * 0.5f;
            float hx = buffer.x(k) + buffer.normalX(k) * half, hy = buffer.y(k) + buffer.normalY(k) * half,
                    hz = buffer.z(k) + buffer.normalZ(k) * half;
            if (Double.isNaN(section.height(t, hx + origin[0], hz + origin[2]))) {
                buffer.flag(k, OFF_FACE);
                continue;
            }
            float gap = (hx - x0) * nx + (hy - y0) * ny + (hz - z0) * nz;
            buffer.set(k, hx - nx * gap * 0.5f, hy - ny * gap * 0.5f, hz - nz * gap * 0.5f, nx, ny, nz, gap, 0);
        }
    }

    private void storeCache(RigidSolver solver) {
        int count = contactTo - contactFrom;
        if (cache.length < count * 9) {
            cache = new float[Math.max(count * 9, cache.length * 2)];
        }
        Vector3f local = new Vector3f();
        for (int k = 0; k < count; k++) {
            RigidSolver.Contact c = solver.contact(contactFrom + k);
            hullRotation.transformInverse(local.set(c.anchorB));
            int o = k * 9;
            cache[o] = local.x;
            cache[o + 1] = local.y;
            cache[o + 2] = local.z;
            cache[o + 3] = c.normal.x;
            cache[o + 4] = c.normal.y;
            cache[o + 5] = c.normal.z;
            cache[o + 6] = c.normalImpulse;
            cache[o + 7] = c.tangentImpulse1;
            cache[o + 8] = c.tangentImpulse2;
        }
        cacheCount = count;
        int slot = 0;
        List<SuspensionUnit<?>> units = vehicle.getSuspensionUnits();
        int spring = springFrom;
        for (int u = 0, size = units.size(); u < size && sprung; u++) {
            SuspensionUnit<?> unit = units.get(u);
            int rows = unit.contactCount();
            for (int row = 0; row < rows; row++, slot++) {
                if (unit.isGrounded() && spring < springTo) {
                    RigidSolver.Spring s = solver.spring(spring++);
                    springCache[slot * 2] = s.impulse;
                    springCache[slot * 2 + 1] = s.stopImpulse;
                }
            }
        }
    }

    /** Warm-start caches (snapshot, REDACTED). */
    void writeWarm(FriendlyByteBuf buf) {
        buf.writeVarInt(cacheCount);
        for (int i = 0, n = cacheCount * 9; i < n; i++) {
            buf.writeFloat(cache[i]);
        }
        buf.writeVarInt(springCache.length);
        for (float impulse : springCache) {
            buf.writeFloat(impulse);
        }
    }

    void readWarm(FriendlyByteBuf buf) {
        cacheCount = buf.readVarInt();
        if (cache.length < cacheCount * 9) {
            cache = new float[cacheCount * 9];
        }
        for (int i = 0, n = cacheCount * 9; i < n; i++) {
            cache[i] = buf.readFloat();
        }
        int springs = buf.readVarInt();
        if (springCache.length != springs) {
            springCache = new float[springs];
        }
        for (int i = 0; i < springs; i++) {
            springCache[i] = buf.readFloat();
        }
    }

    /** Task failed: no motion this tick. */
    void hold() {
        failed = true;
        vehicle.physicsRig.capturePose(vehicle);
    }
}
