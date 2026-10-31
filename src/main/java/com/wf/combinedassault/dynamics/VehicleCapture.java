package com.wf.combinedassault.dynamics;

import com.wf.wflib.physics.api.BodyState;
import com.wf.wflib.physics.api.CellTops;
import com.wf.wflib.physics.api.CompoundBody;
import com.wf.wflib.physics.api.SpringRows;
import com.wf.wflib.physics.api.StepResult;
import com.wf.combinedassault.tow.HitchInfo;
import com.wf.combinedassault.tow.TowLink;
import com.wf.combinedassault.tow.TowLinks;
import com.wf.combinedassault.tow.TowMode;
import com.wf.combinedassault.tow.TowSolver;
import com.wf.combinedassault.tow.TowableInfo;
import com.wf.combinedassault.dynamics.part.LandingGearUnit;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.dynamics.part.SuspensionUnit;
import com.wf.wflib.geom.api.OBB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

/** Vehicle half of the step: {@link BodyState} from the entity, suspension rows, solve result back onto the entity. */
public final class VehicleCapture {

    /** Speculative reach beyond the tick's own motion, blocks. */
    private static final float MARGIN = 0.1f;
    /** Pitch/roll spin kept per tick on the belly, < half the wheels down, up.y above {@link BodyState#BELLY_SPIN_UP}. */
    private static final float BELLY_SPIN_RETENTION = 0.6f;
    /** Strut -y: full spring at cos 45, none at cos 60 (SuspensionUnit probes to there). */
    private static final double STRUT_FADE_START = 0.7071, STRUT_FADE_END = 0.5;
    /** Blocks; past it a pose is broken, and preparing the sweep allocates collision columns without bound. */
    private static final double MAX_GATHER = 1024;
    /** > one tick of travel plus steering swing of the hull centre. */
    private static final double TELEPORT_JUMP = 16;

    private VehicleCapture() {
    }

    /** Tick thread. Hull pose from the entity's (steering turned it after the last updateOBBs). */
    public static void capture(AbstractVehicle v, BodyState s, boolean async) {
        PhysicsEngine engine = v.physicsEngine;
        engine.prepareInertia();
        Vec3 offset = v.centerOffset;
        s.pivotX = v.getX() + offset.x;
        s.pivotY = v.getY() + offset.y;
        s.pivotZ = v.getZ() + offset.z;
        s.entityRotation.set(v.rotYXZShared());
        var main = v.getMainCubeOBB();
        s.hullLocal.set(main.localCenter());
        s.hullLocalRotation.set(main.localRotation());
        s.entityRotation.mul(s.hullLocalRotation, s.hullRotation);
        Vector3f arm = s.entityRotation.transform(new Vector3f(s.hullLocal));
        s.hullX = s.pivotX + arm.x;
        s.hullY = s.pivotY + arm.y;
        s.hullZ = s.pivotZ + arm.z;
        // Position set outside the step (/tp, teleportTo) leaves the OBBs at the old pose => gather spans both.
        OBB stale = main.obb();
        double jx = s.hullX - stale.centerX(), jy = s.hullY - stale.centerY(), jz = s.hullZ - stale.centerZ();
        if (jx * jx + jy * jy + jz * jz > TELEPORT_JUMP * TELEPORT_JUMP) {
            v.updateOBBs();
        }
        s.hullExtents.set(main.obb().extents());
        Vec3 center = engine.center;
        s.hullLocalRotation.transform(s.comLocal.set((float) center.x, (float) center.y, (float) center.z)).add(s.hullLocal);
        s.entityRotation.transform(arm.set(s.comLocal));
        s.comX = s.pivotX + arm.x;
        s.comY = s.pivotY + arm.y;
        s.comZ = s.pivotZ + arm.z;
        s.pieceCount = v.getOBBs().size();
        s.pieceFrames = frames(v.getOBBs(), main.obb(), s.pieceFrames);

        s.inverseMass = 1.0f / Math.max(engine.mass, 1.0e-3f);
        if (engine.lockCenterRot) {
            s.inverseInertia.zero();
        } else {
            engine.inverseInertiaWorld(s.hullRotation, s.inverseInertia);
        }
        Vec3 movement = v.getDeltaMovement();
        s.velocity.set((float) movement.x, (float) movement.y, (float) movement.z);
        s.spin.set(engine.angularVelocity);
        if (engine.lockCenterRot) {
            s.spin.zero();
        }
        s.sprung = engine.suspended();
        boolean wheelsWereDown = engine.wheelsGrounded();
        captureWheels(v, s, main.obb());
        captureFeet(v, s);
        captureTow(v, s);
        s.carried = v.weightCarried();
        s.gear = !s.sprung && v.rollsOnGear();
        s.acceleration.set(0, v.isNoGravity() || s.carried ? 0 : -PhysicsEngine.G, 0);
        engine.buoyancy(s.velocity, s.acceleration);
        if (s.sprung) {
            s.spin.y = engine.decayYawSpin(s.spin.y, v.onGround());
            if (wheelsWereDown && v.isDestroyed()) {
                PhysicsEngine.skid(s.velocity, BodyState.FRICTION);
            } else if (wheelsWereDown) {
                engine.rollingResistance(s.velocity);
            }
        } else if ((v.towedOnWheels() || s.gear) && v.onGround()) {
            engine.rollingResistance(s.velocity);
        }
        driveTorque(engine, s, wheelsWereDown);
        s.spinRetention = v.onGround() ? 1 : engine.tumbleAirDamping;
        s.maxSpin = engine.effectiveMaxRotV();

        float reach = s.hullExtents.length();
        CompoundBody compound = v.collisionBody();
        if (compound.hasPieces()) {
            reach = Math.max(reach, compound.reachRadius());
        }
        s.margin = MARGIN + s.velocity.length() + s.spin.length() * reach;
        s.gather = shapeBounds(v, s).expandTowards(movement).expandTowards(0, v.maxUpStep(), 0).inflate(s.margin);
        AABB gather = s.gather;
        if (!(Math.max(gather.getXsize(), Math.max(gather.getYsize(), gather.getZsize())) < MAX_GATHER)) {
            throw new IllegalStateException(v + " step bounds " + gather);
        }
        s.live = s.predicting || !async || v.physicsTrace() != null || v.level().isDebug() || v.providerNear(gather);
        s.restitution = engine.bounce;
        v.bindStepWorld(s, gather);
        engine.prepareSuspension();
    }

    /** Sprung: the drive's dv acts at the wheels' contact plane, not the COM => squat, dive, lean. */
    private static void driveTorque(PhysicsEngine engine, BodyState s, boolean wheelsWereDown) {
        Vector3f drive = engine.driveDv();
        double height = s.comY - engine.groundY();
        if (!s.sprung || !wheelsWereDown || drive.lengthSquared() == 0 || !(height > 0)) {
            return;
        }
        float planar = drive.length(), grip = (float) (PhysicsEngine.TYRE_GRIP * PhysicsEngine.G);
        Vector3f impulse = new Vector3f(drive).mul((planar > grip ? grip / planar : 1) / s.inverseMass);
        Vector3f torque = new Vector3f(0, (float) -height, 0).cross(impulse);
        s.spin.add(s.inverseInertia.transform(torque));
    }

    private static AABB shapeBounds(AbstractVehicle v, BodyState s) {
        AABB own = v.getMainCubeOBB().obb().bounds();
        List<OBB> obbs = v.getOBBs();
        AABB all = obbs.isEmpty() ? own : own.minmax(OBB.toAABB(obbs));
        // Captured pose differs from the OBBs' by this tick's steering: one radius covers any yaw.
        double dx = s.hullX - v.getMainCubeOBB().obb().centerX();
        double dy = s.hullY - v.getMainCubeOBB().obb().centerY();
        double dz = s.hullZ - v.getMainCubeOBB().obb().centerZ();
        return all.expandTowards(dx, dy, dz).inflate(Math.min(s.hullExtents.length(), 1.0));
    }

    /**
     * Non-solid wheel colliders never touch the world: a centauro on its side tipped off its sloped hull side onto
     * nothing, rolled upright until the struts re-engaged and threw it over.
     */
    private static void captureWheels(AbstractVehicle v, BodyState s, OBB stale) {
        s.wheelCount = 0;
        if (!s.sprung || s.hullRotation.transform(new Vector3f(0, 1, 0)).y >= STRUT_FADE_END) {
            return;
        }
        List<OBB> wheels = new ArrayList<>();
        for (SuspensionUnit<?> unit : v.getSuspensionUnits()) {
            if (!unit.isDetached()) {
                wheels.addAll(unit.getOBBs());
            }
        }
        s.wheelCount = wheels.size();
        s.wheelFrames = frames(wheels, stale, s.wheelFrames);
    }

    private static void captureFeet(AbstractVehicle v, BodyState s) {
        s.footCount = 0;
        for (PartUnit<?> unit : v.getPartUnits()) {
            if (unit instanceof LandingGearUnit gear && !gear.isDetached()) {
                if (s.feet.length < (s.footCount + gear.footCount()) * 3) {
                    s.feet = Arrays.copyOf(s.feet, (s.footCount + gear.footCount()) * 3);
                }
                for (int i = 0; i < gear.footCount(); i++, s.footCount++) {
                    Vector3f at = gear.foot(i);
                    s.feet[s.footCount * 3] = at.x;
                    s.feet[s.footCount * 3 + 1] = at.y + BodyState.FOOT;
                    s.feet[s.footCount * 3 + 2] = at.z;
                }
            }
        }
    }

    /** Coupling geometry against the tractor's current (pre-step) pose; parted-far couplings left to {@link TowSolver}. */
    private static void captureTow(AbstractVehicle v, BodyState s) {
        s.coupling = null;
        TowLink link = v.towLink();
        HitchInfo hitch = link == null ? null : link.hitch();
        if (hitch == null || link.tractor().isRemoved() || link.tractor().level() != v.level()) {
            return;
        }
        AbstractVehicle tractor = link.tractor();
        TowMode mode = link.mode();
        Vec3 hitchAt = TowLinks.hitchPoint(tractor, hitch);
        Vec3 attach = TowLinks.attachPoint(v, mode);
        s.couplingLength = mode == TowMode.HITCH ? -1 : (float) link.cable();
        if (hitchAt.distanceTo(attach) - Math.max(s.couplingLength, 0) > TowSolver.BREAK_MARGIN) {
            return;
        }
        s.coupling = link;
        double[] p = s.couplingPoints;
        p[0] = attach.x;
        p[1] = attach.y;
        p[2] = attach.z;
        p[3] = hitchAt.x;
        p[4] = hitchAt.y;
        p[5] = hitchAt.z;
        s.couplingYawLimit = Float.NaN;
        s.couplingRoll = Float.NaN;
        s.couplingGrip = (float) PhysicsEngine.TYRE_GRIP;
        if (mode == TowMode.SLING) {
            return;
        }
        TowableInfo towable = v.towing.towable;
        Vec3 axle = TowLinks.worldPoint(v, new Vec3(towable.axle.x, 0, towable.axle.z));
        p[6] = axle.x;
        p[7] = axle.y;
        p[8] = axle.z;
        s.entityRotation.transform(s.couplingLateral.set(1, 0, 0));
        if (mode == TowMode.HITCH) {
            Vector3f own = s.entityRotation.transform(new Vector3f(0, 0, 1));
            Vector3f lead = tractor.rotYXZ().transform(new Vector3f(0, 0, 1));
            // Coupling behind the axle => towed tail-first.
            double flip = towable.offset.z < towable.axle.z ? Math.PI : 0;
            float yaw = (float) Mth.wrapDegrees(Math.toDegrees(Math.atan2(own.x, own.z) + flip - Math.atan2(lead.x, lead.z)));
            s.couplingYaw = (float) Math.toRadians(yaw);
            s.couplingYawLimit = (float) Math.toRadians(TowSolver.JACKKNIFE_LIMIT);
            Quaternionf leadRotation = tractor.rotYXZ();
            leadRotation.transform(s.couplingRollAxis.set(0, 0, 1));
            Vector3f leadUp = leadRotation.transform(new Vector3f(0, 1, 0));
            Vector3f ownUp = s.entityRotation.transform(new Vector3f(0, 1, 0));
            s.couplingRoll = (float) Math.atan2(new Vector3f(leadUp).cross(ownUp).dot(s.couplingRollAxis), leadUp.dot(ownUp));
        }
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

    /** Island task: suspension rows, one slot per unit contact. */
    public static void springs(AbstractVehicle v, SpringRows rows, CellTops surface) {
        v.physicsEngine.simulateSuspensions(v.physicsRig, surface);
        List<SuspensionUnit<?>> units = v.getSuspensionUnits();
        for (int u = 0, size = units.size(); u < size; u++) {
            SuspensionUnit<?> unit = units.get(u);
            int count = unit.contactCount();
            if (!unit.isGrounded()) {
                for (int row = 0; row < count; row++) {
                    rows.skip();
                }
                continue;
            }
            double upright = -unit.getSuspensionDirection().y;
            float ratio = (float) (1 / upright);
            // 1/cos ratio + a hard cut at 60 degrees: a strut re-entering the ground deep threw a centauro on its side upright.
            double weight = unit.contactWeight() * Mth.clamp((upright - STRUT_FADE_END) / (STRUT_FADE_START - STRUT_FADE_END), 0, 1);
            float stiffness = (float) (PhysicsHelper.forcePerTick(unit.getData().getSpringStiffness()) * weight);
            float damping = (float) (PhysicsHelper.dampingPerTick(unit.getData().getDamping()) * weight);
            for (int row = 0; row < count; row++) {
                rows.add(unit.contactOnDeck(row), unit.contactX(row), unit.contactY(row), unit.contactZ(row), ratio,
                        (float) unit.contactCompression(row), (float) unit.contactMin(row),
                        weight < unit.contactWeight() ? Float.MAX_VALUE : (float) unit.contactMax(row), stiffness, damping);
            }
        }
    }

    /** Island task: belly spin rule, impact, engine bookkeeping. */
    public static void solved(AbstractVehicle v, BodyState s, StepResult r) {
        PhysicsEngine engine = v.physicsEngine;
        PhysicsRig rig = v.physicsRig;
        r.grounded |= engine.wheelsGrounded();
        if (s.sprung && r.belly && s.hullRotation.transform(new Vector3f(0, 1, 0)).y > BodyState.BELLY_SPIN_UP) {
            List<SuspensionUnit<?>> units = v.getSuspensionUnits();
            int down = 0;
            for (int u = 0, size = units.size(); u < size; u++) {
                down += units.get(u).isGrounded() ? 1 : 0;
            }
            // Frictionless belly rows kept every kick on a box edge: RoughBench flips 7/96 -> 0/96 (pre-rewrite);
            // landing straddling a terrace edge rolled an M983 over at 7 deg/t. Coulomb belly FORBIDDEN (tripped
            // flat turns). Yaw kept.
            if (down * 2 < units.size()) {
                r.spin.x *= BELLY_SPIN_RETENTION;
                r.spin.z *= BELLY_SPIN_RETENTION;
            }
        }
        rig.impactVelocityDiff = 0;
        if (r.impactWeight > 0 && r.velocityDiff * PhysicsHelper.TICKS_PER_SECOND > v.impactSpeedThreshold()) {
            rig.impactVelocityDiff = r.velocityDiff;
            rig.impactPos = new Vec3(r.impactX, r.impactY, r.impactZ);
        }
        engine.solved(r.spin, r.wheelLoad, r.bellyLoad, r.groundY, r.blockingCells, r.ramSpeed, rig.cursor);
    }
}
