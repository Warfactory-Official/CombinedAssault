package com.wf.combinedassault.dynamics;

import com.wf.wflib.physics.api.CellTops;
import net.minecraft.network.FriendlyByteBuf;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Math;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.physics.api.ChunkCollisionCache;
import com.wf.combinedassault.dynamics.part.SuspensionUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.wflib.geom.api.OBB;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;

import java.util.List;

/**
 * Per-vehicle physics state and the parts of a tick the rigid step ({@code RigidStep}) takes as inputs or hands back:
 * mass properties, spin, buoyancy, suspension probes, drive dv, block breaking, attitude from a rotation.
 */
public class PhysicsEngine {

    public static final float G = PhysicsHelper.GRAVITY / PhysicsHelper.TICKS_PER_SECOND_SQUARED;
    /** sin 20 deg; travel step-up and skirt fade. */
    public static final double MAX_STEP_ROLL = 0.342;
    /** Tyre friction coefficient: caps lateral grip and drive load transfer. */
    public static final double TYRE_GRIP = 0.8;
    /** Block cells pressed per tick, summed, before a jammed hull grinds through. */
    private static final int STUCK_DESTROY_THRESHOLD = 10;
    /** Hardness at or above which the vehicle cannot break the block. */
    private static final float UNBREAKABLE_HARDNESS = 20.0F;
    /** Most cells queued from one refused rotation (merged geometry). */
    private static final int MAX_ROTATION_RAM_CELLS = 27;
    /** Approach speed into blocking cells, b/t: pressing, not resting against. */
    private static final double JAM_SPEED = 1.0e-3;

    public final AbstractVehicle vehicle;
    public float radarCrossSection;
    public float mass = 1;
    public Vec3 center;
    public float bounce = 0.05f;
    public float angularDampingAir = 0.96f;
    public float maxRotV = 0.3f;
    public float maxTipSpeed = 3.6f;
    /** Spin kept per tick while airborne. */
    public float tumbleAirDamping = 0.995f;
    /** World frame, rad/tick. */
    public final Vector3f angularVelocity = new Vector3f();
    public float friction = 0.005f;
    public float destroyExplosionVelocity = 0.8f;
    /** Vehicle density, in the same units as a fluid's density/1000. Drives buoyancy. */
    public float density = 8;
    /** Fraction of velocity bled off per tick at full submersion. */
    public float liquidDamping = 0.1f;
    /** Blocks of rise per block of horizontal travel, steepest step-up; 1.0 = 45 degrees. */
    public float climbGradient = 1.0f;
    public boolean lockZRot;
    public boolean lockCenterRot;
    public boolean canDestroyBlock;
    /** Last step pressed into blocks sideways. */
    public boolean blockedByContacts;
    public int stuckTick;

    /** Hull-frame inertia about the COM per unit mass (CompoundBody: hull + collided pieces). */
    private final Matrix3f bodyInertia = new Matrix3f();
    /** (mass * bodyInertia)^-1, hull frame. */
    private final Matrix3f invInertiaBody = new Matrix3f();
    private final Vector3f comScratch = new Vector3f();

    private volatile float tractionShare = 1;
    private volatile float bellyShare;
    private boolean suspensionGrounded;
    /** This tick's controller dv (drive, steering grip), horizontal; tick thread. */
    private final Vector3f driveDv = new Vector3f();
    /** Last step's load-weighted wheel contact height. */
    private double groundY = Double.NaN;

    /** Fraction of the hull under a fluid surface this tick; 0 when dry. */
    private float submergedRatio;
    private double buoyancyAcceleration;
    private final Vector3f buoyancyLocal = new Vector3f();
    private final double[] buoyancyWorld = new double[3];
    private final BlockPos.MutableBlockPos buoyancyPos = new BlockPos.MutableBlockPos();

    private final LongArrayList pendingBreaks = new LongArrayList();
    /** Hardness budget per pending break; checked on the tick thread against the live state. */
    private final DoubleArrayList pendingBudgets = new DoubleArrayList();
    /** Cells already queued this tick. */
    private final LongOpenHashSet queuedCells = new LongOpenHashSet();
    private final BlockPos.MutableBlockPos breakCursor = new BlockPos.MutableBlockPos();

    /** Carried across ticks (snapshot). */
    public void writePrediction(FriendlyByteBuf buf) {
        buf.writeFloat(angularVelocity.x);
        buf.writeFloat(angularVelocity.y);
        buf.writeFloat(angularVelocity.z);
        buf.writeFloat(tractionShare);
        buf.writeFloat(bellyShare);
        buf.writeBoolean(suspensionGrounded);
        buf.writeDouble(groundY);
        buf.writeBoolean(blockedByContacts);
        buf.writeVarInt(stuckTick);
    }

    public void readPrediction(FriendlyByteBuf buf) {
        angularVelocity.set(buf.readFloat(), buf.readFloat(), buf.readFloat());
        tractionShare = buf.readFloat();
        bellyShare = buf.readFloat();
        suspensionGrounded = buf.readBoolean();
        groundY = buf.readDouble();
        blockedByContacts = buf.readBoolean();
        stuckTick = buf.readVarInt();
    }

    public PhysicsEngine(AbstractVehicle vehicle) {
        this.vehicle = vehicle;
    }

    /** Tick thread, before a solve: bodyInertia from the compound as updateOBBs last posed it. */
    public void prepareInertia() {
        if (vehicle.getMainCubeOBB() == null || mass <= 0) {
            bodyInertia.zero();
            invInertiaBody.zero();
            return;
        }
        vehicle.collisionBody().inertia(comScratch.set((float) center.x, (float) center.y, (float) center.z), bodyInertia);
        if (bodyInertia.determinant() > 1.0e-12f) {
            invInertiaBody.set(bodyInertia).scale(mass).invert();
        } else {
            invInertiaBody.zero();
        }
    }

    /** Per unit mass, hull frame, about the COM. */
    public Matrix3f bodyInertia() {
        return bodyInertia;
    }

    /** R (m I)^-1 R^T for hull rotation R. Reads only prepareInertia's result. */
    public Matrix3f inverseInertiaWorld(Quaternionf hullRotation, Matrix3f dest) {
        Matrix3f r = new Matrix3f().set(hullRotation);
        return dest.set(r).mul(invInertiaBody).mul(r.transpose());
    }

    public VehicleCubeOBB physicsCube() {
        return vehicle.getMainCubeOBB();
    }

    public float effectiveMaxRotV() {
        float radius = vehicle.getMainCubeOBB().obb().extents().length();
        if (radius < 1.0e-3f) {
            return maxRotV;
        }
        return Math.min(maxRotV, maxTipSpeed / radius);
    }

    /** Last {@link #sampleBuoyancy}. */
    public boolean afloat() {
        return submergedRatio > 0;
    }

    /** Tick thread only: reads live fluids; the solve applies the cached result via {@link #applyBuoyancy}. */
    public Vec3 sampleBuoyancy() {
        submergedRatio = 0;
        buoyancyAcceleration = 0;
        if (density <= 0 || mass <= 0) {
            return Vec3.ZERO;
        }
        VehicleCubeOBB physicsCube = physicsCube();
        if (physicsCube == null) {
            return Vec3.ZERO;
        }
        OBB obb = physicsCube.obb();
        Vector3f[] axes = obb.getAxes();
        Vector3f extents = obb.extents();
        int samplesX = buoyancySamples(extents.x * 2);
        int samplesY = 16;
        int samplesZ = buoyancySamples(extents.z * 2);
        int sampleCount = samplesX * samplesY * samplesZ;
        double physicsCubeVolume = physicsCube.volume();
        if (physicsCubeVolume <= 0) {
            return Vec3.ZERO;
        }
        Level level = vehicle.level();
        if (!mayHoldFluid(level, vehicle.getBoundingBox())) {
            return Vec3.ZERO;
        }
        double sampleVolume = physicsCubeVolume / sampleCount;
        double displacedVolume = 0;
        double displacedFluidMass = 0;
        Vector3f local = buoyancyLocal;
        double[] world = buoyancyWorld;
        BlockPos.MutableBlockPos blockPos = buoyancyPos;
        // Consecutive samples mostly share a cell; one fluid lookup per run.
        long lastCell = Long.MIN_VALUE;
        FluidState fluidState = null;
        double fluidSurface = 0;
        int fluidDensity = 0;
        for (int x = 0; x < samplesX; x++) {
            float localX = sampleCoordinate(extents.x, x, samplesX);
            for (int y = 0; y < samplesY; y++) {
                float localY = sampleCoordinate(extents.y, y, samplesY);
                for (int z = 0; z < samplesZ; z++) {
                    float localZ = sampleCoordinate(extents.z, z, samplesZ);
                    obb.localToWorld(local.set(localX, localY, localZ), axes, world);
                    blockPos.set(Mth.floor(world[0]), Mth.floor(world[1]), Mth.floor(world[2]));
                    long cell = blockPos.asLong();
                    if (cell != lastCell) {
                        lastCell = cell;
                        fluidState = level.getFluidState(blockPos);
                        if (!fluidState.isEmpty()) {
                            fluidSurface = blockPos.getY() + fluidState.getHeight(level, blockPos);
                            fluidDensity = fluidState.getFluidType().getDensity(fluidState, level, blockPos);
                        }
                    }
                    if (!fluidState.isEmpty() && world[1] < fluidSurface && fluidDensity > 0) {
                        displacedVolume += sampleVolume;
                        displacedFluidMass += sampleVolume * fluidDensity / 1000.0;
                    }
                }
            }
        }
        if (displacedVolume <= 0) {
            return Vec3.ZERO;
        }
        double buoyancyForce = G * mass * displacedFluidMass / (density * physicsCubeVolume);
        buoyancyAcceleration = buoyancyForce / mass;
        submergedRatio = (float) (displacedVolume / physicsCubeVolume);
        return new Vec3(0, buoyancyForce, 0);
    }

    /** Palette test over loaded sections under box; false => no fluid anywhere in it. */
    private static boolean mayHoldFluid(Level level, AABB box) {
        int minSectionY = Math.max(SectionPos.blockToSectionCoord(Mth.floor(box.minY)), level.getMinSection());
        int maxSectionY = Math.min(SectionPos.blockToSectionCoord(Mth.floor(box.maxY)), level.getMaxSection() - 1);
        for (int sectionX = SectionPos.blockToSectionCoord(Mth.floor(box.minX));
             sectionX <= SectionPos.blockToSectionCoord(Mth.floor(box.maxX)); sectionX++) {
            for (int sectionZ = SectionPos.blockToSectionCoord(Mth.floor(box.minZ));
                 sectionZ <= SectionPos.blockToSectionCoord(Mth.floor(box.maxZ)); sectionZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(sectionX, sectionZ);
                if (chunk == null) {
                    continue;
                }
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
                    if (!section.hasOnlyAir() && section.maybeHas(state -> !state.getFluidState().isEmpty())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int buoyancySamples(float size) {
        return Mth.clamp(Mth.ceil(size * 2), 1, 8);
    }

    private static float sampleCoordinate(float extent, int sample, int sampleCount) {
        return -extent + extent * 2 * (sample + 0.5f) / sampleCount;
    }

    private static float rightInLiquid(float angle, float liquidInfluence) {
        // Large tilt rights fast, slows near level; min step avoids trailing off at the end.
        float rightingStep = Mth.clamp(Math.abs(Mth.wrapDegrees(angle)) * 0.2f, 1.5f, 6.0f) * liquidInfluence;
        return Mth.approachDegrees(angle, 0, rightingStep);
    }

    public boolean suspended() {
        return vehicle.hasRunningGear();
    }

    /** Tick thread, before a solve is launched: suspension frames are read off-thread. */
    public void prepareSuspension() {
        List<SuspensionUnit<?>> suspensions = vehicle.getSuspensionUnits();
        for (int i = 0, size = suspensions.size(); i < size; i++) {
            suspensions.get(i).captureFrame();
        }
    }

    /** Wheel-borne share of weight (last solve), 0..1: tyre force ~ normal load. */
    public float tractionShare() {
        return tractionShare;
    }

    /** Weight share carried by upward hull contacts (high-centred), last solve. */
    public float bellyShare() {
        return bellyShare;
    }

    /** Some wheel or track touches ground (last solve). */
    public boolean wheelsGrounded() {
        return suspensionGrounded;
    }

    /** Yaw spin (rad/tick) after one tick: ground friction mu G r / k^2, r = (w + d) / 6 ~ mean arm of the patch; else air. */
    public float decayYawSpin(float spin, boolean grounded) {
        if (!grounded) {
            return spin * angularDampingAir;
        }
        VehicleCubeOBB cube = physicsCube();
        float k2 = bodyInertia.m11;
        if (cube == null || !(k2 > 0)) {
            return 0;
        }
        float loss = (float) (TYRE_GRIP * G * (cube.getWidth() + cube.getDepth()) / 6 / k2);
        return Math.abs(spin) <= loss ? 0 : spin - Math.signum(spin) * loss;
    }

    /** TODO: wheel probes read block cells only; on a deck or provider geometry a sprung hull rests on its belly. */
    public void simulateSuspensions(PhysicsRig rig, CellTops tops) {
        suspensionGrounded = false;
        Vec3 position = rig.position();
        Quaternionf rotation = rig.rotYXZ();
        List<SuspensionUnit<?>> suspensions = vehicle.getSuspensionUnits();
        for (int i = 0, size = suspensions.size(); i < size; i++) {
            SuspensionUnit<?> suspension = suspensions.get(i);
            suspension.simulate(position, rotation, tops);
            suspensionGrounded |= suspension.isGrounded();
        }
    }

    /**
     * Recoil pitch / roll kick, sprung only. Tick thread only, deferred through queueRecoil. Unsprung (emplacement,
     * trailer): planted => none; the old edge-pivot rock stacked into hops under a burst (Mk 15).
     */
    public void recoil(WeaponUnit weaponUnit, float recoil) {
        if (!suspended() || lockCenterRot) {
            return;
        }
        Vector3f localFireDirection = new Quaternionf()
                .rotateY(Math.toRadians(vehicle.getYRot()))
                .transform(weaponUnit.worldVec().normalize().toVector3f());
        float impulse = 0.01f * recoil;
        // Yaw frame (pitch x, roll z) -> world.
        angularVelocity.add(new Quaternionf().rotateY(Math.toRadians(-vehicle.getYRot()))
                .transform(new Vector3f(-localFireDirection.z * impulse, 0,
                        lockZRot ? 0 : localFireDirection.x * impulse)));
    }

    /** Rams blocks that blocked a rotation attempt. */
    public void ramByRotation(AABB blocker, double tipSpeed) {
        if (tipSpeed <= 0 || !breakingEnabled()) {
            return;
        }
        ChunkCollisionCache.Cursor cursor = vehicle.collisionCursor();
        if (cursor == null) {
            return;
        }
        double perHardness = VehicleSettings.current().ramBreakMomentum();
        double budget = perHardness <= 0
                ? Double.MAX_VALUE
                : (mass * tipSpeed) / perHardness;
        if (budget <= 0) {
            return;
        }
        AABB region = blocker.intersect(vehicle.getBoundingBox().inflate(0.1));
        if (region.getXsize() <= 0 || region.getYsize() <= 0 || region.getZsize() <= 0) {
            return;
        }
        int queued = 0;
        for (int y = Mth.floor(region.minY); y <= Mth.floor(region.maxY - 1.0e-6); y++) {
            for (int z = Mth.floor(region.minZ); z <= Mth.floor(region.maxZ - 1.0e-6); z++) {
                for (int x = Mth.floor(region.minX); x <= Mth.floor(region.maxX - 1.0e-6); x++) {
                    if (queued++ >= MAX_ROTATION_RAM_CELLS) {
                        return;
                    }
                    queueBreak(cursor, BlockPos.asLong(x, y, z), budget);
                }
            }
        }
    }

    private boolean breakingEnabled() {
        return canDestroyBlock && VehicleSettings.current().canDestroyBlock();
    }

    /** Queues one cell for breaking if it exists, is soft enough, and not already queued. */
    private void queueBreak(ChunkCollisionCache.Cursor cursor, long cell, double hardnessBudget) {
        if (!queuedCells.add(cell)) {
            return;
        }
        if (cursor.collisionAt(BlockPos.getX(cell), BlockPos.getY(cell), BlockPos.getZ(cell)) == null) {
            return;
        }
        // Hardness is block code (modded overrides read the level): tick thread only, at apply.
        pendingBreaks.add(cell);
        pendingBudgets.add(hardnessBudget);
    }

    /** Applies queued block breaks; tick thread only. Re-checks hardness against live level. */
    public void applyPendingBreaks() {
        if (pendingBreaks.isEmpty()) {
            return;
        }
        Level level = vehicle.level();
        if (level.isClientSide()) {
            // Guard against client-side desync.
            pendingBreaks.clear();
            pendingBudgets.clear();
            return;
        }
        // No drops; blocks are smashed not mined.
        boolean drops = false;
        for (int i = 0, size = pendingBreaks.size(); i < size; i++) {
            long cell = pendingBreaks.getLong(i);
            breakCursor.set(BlockPos.getX(cell), BlockPos.getY(cell), BlockPos.getZ(cell));
            BlockState state = level.getBlockState(breakCursor);
            if (state.isAir()) {
                continue;
            }
            float hardness = state.getDestroySpeed(level, breakCursor);
            if (hardness < 0 || hardness >= UNBREAKABLE_HARDNESS || hardness > pendingBudgets.getDouble(i)) {
                continue;
            }
            // Use immutable position here; destroyBlock reaches deep and some paths retain it.
            level.destroyBlock(breakCursor.immutable(), drops, vehicle);
        }
        pendingBreaks.clear();
        pendingBudgets.clear();
    }

    public void setAttitude(PhysicsRig rig, Quaternionf rotation) {
        Vector3f angles = new Vector3f();
        if (attitudeOf(rig, rotation, angles)) {
            rig.setXRot(angles.x);
            rig.setYRot(angles.y);
            rig.setZRot(angles.z);
        }
    }

    /**
     * Entity (xRot, yRot, zRot) degrees of rotation: YXZ branch (x, y, z) or (180-x, y+180, z+180), whichever is
     * nearer the rig's current angles. Fixed branch => yaw/roll jump 180 as pitch crosses 90 (parts, riders, yaw frame).
     * lockZRot => roll 0.
     * @return false when not finite
     */
    private boolean attitudeOf(PhysicsRig rig, Quaternionf rotation, Vector3f dest) {
        rotation.getEulerAnglesYXZ(dest);
        if (!Float.isFinite(dest.x) || !Float.isFinite(dest.y) || !Float.isFinite(dest.z)) {
            return false;
        }
        float x = (float) Math.toDegrees(dest.x);
        float y = -(float) Math.toDegrees(dest.y);
        float z = (float) Math.toDegrees(dest.z);
        float px = rig.getXRot(), py = rig.getYRot(), pz = rig.getZRot();
        if (lockZRot) {
            dest.set(x, py + Mth.wrapDegrees(y - py), 0);
            return true;
        }
        float fx = Mth.wrapDegrees(180 - x), fy = y + 180, fz = Mth.wrapDegrees(z + 180);
        float direct = Math.abs(Mth.wrapDegrees(x - px)) + Math.abs(Mth.wrapDegrees(y - py)) + Math.abs(Mth.wrapDegrees(z - pz));
        float flipped = Math.abs(Mth.wrapDegrees(fx - px)) + Math.abs(Mth.wrapDegrees(fy - py)) + Math.abs(Mth.wrapDegrees(fz - pz));
        if (flipped < direct) {
            x = fx;
            y = fy;
            z = fz;
        }
        dest.set(x, py + Mth.wrapDegrees(y - py), z);
        return true;
    }

    /** Tick thread: controller dv this tick (drive model's velocity edit), horizontal part. */
    public void noteDrive(Vec3 before, Vec3 after) {
        driveDv.set((float) (after.x - before.x), 0, (float) (after.z - before.z));
    }

    public Vector3f driveDv() {
        return driveDv;
    }

    /** NaN before the first grounded step. */
    public double groundY() {
        return groundY;
    }

    /** Wheels down: horizontal speed loses {@link #friction} as a force, never reversing. */
    public void rollingResistance(Vector3f velocity) {
        float loss = (float) PhysicsHelper.accelerationPerTick(friction, mass);
        float horizontal = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        float scale = horizontal < 1.0e-4f ? 0 : Math.max(0, horizontal - loss) / horizontal;
        velocity.x *= scale;
        velocity.z *= scale;
    }

    /** Locked running gear (wreck): horizontal speed loses {@code mu} G per tick, never reversing. */
    public static void skid(Vector3f velocity, float mu) {
        float loss = mu * G;
        float horizontal = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        float scale = horizontal < 1.0e-4f ? 0 : Math.max(0, horizontal - loss) / horizontal;
        velocity.x *= scale;
        velocity.z *= scale;
    }

    /** Spin impulse (world, rad/tick), e.g. parachute righting. Tick thread. */
    public void addImpulse(Vector3f dw) {
        angularVelocity.add(dw);
    }

    /**
     * Capture: liquid damping into velocity, buoyancy into acceleration ({@link #sampleBuoyancy} this tick). Buoyant
     * heave halved per tick (kept from the old solve).
     */
    public void buoyancy(Vector3f velocity, Vector3f acceleration) {
        if (submergedRatio <= 0) {
            return;
        }
        velocity.mul((float) Mth.clamp(1 - liquidDamping * submergedRatio, 0, 1));
        if (buoyancyAcceleration > 0) {
            velocity.y *= 0.5f;
        }
        acceleration.y += (float) buoyancyAcceleration;
    }

    /** Integration: eases pitch/roll level while afloat (one buoyancy force at the COM rights nothing). */
    public void rightInLiquid(PhysicsRig rig) {
        if (submergedRatio <= 0) {
            return;
        }
        float liquidInfluence = Mth.sqrt(Mth.clamp(submergedRatio, 0, 1));
        angularVelocity.mul(1 - liquidInfluence * 0.2f);
        rig.setXRot(rightInLiquid(rig.getXRot(), liquidInfluence));
        rig.setZRot(lockZRot ? 0 : rightInLiquid(rig.getZRot(), liquidInfluence));
    }

    /**
     * Island task: this tick's solve result. Ram breaks at the approach speed into blocking cells; pressing into them
     * accumulates a jam that grinds through.
     */
    public void solved(Vector3f spin, float wheelLoad, float bellyLoad, double groundY, LongArrayList blockingCells, double ramSpeed,
                       ChunkCollisionCache.Cursor cursor) {
        angularVelocity.set(spin);
        tractionShare = mass > 0 ? Mth.clamp(wheelLoad / (mass * G), 0, 1) : 0;
        bellyShare = mass > 0 ? Mth.clamp(bellyLoad / (mass * G), 0, 1) : 0;
        if (!Double.isNaN(groundY)) {
            this.groundY = groundY;
        }
        boolean jammed = !blockingCells.isEmpty() && ramSpeed > JAM_SPEED;
        blockedByContacts = jammed;
        queuedCells.clear();
        if (!jammed || cursor == null || !breakingEnabled()) {
            stuckTick = Math.max(stuckTick - 1, 0);
            return;
        }
        double perHardness = VehicleSettings.current().ramBreakMomentum();
        double budget = perHardness <= 0 ? Double.MAX_VALUE : mass * ramSpeed / perHardness;
        for (int i = 0, size = blockingCells.size(); i < size; i++) {
            queueBreak(cursor, blockingCells.getLong(i), budget);
        }
        stuckTick += blockingCells.size();
        if (stuckTick < STUCK_DESTROY_THRESHOLD) {
            return;
        }
        for (int i = 0, size = blockingCells.size(); i < size; i++) {
            long cell = blockingCells.getLong(i);
            for (int dy = -1; dy <= 1; dy++) {
                // No momentum in a jam; break anything soft.
                queueBreak(cursor, BlockPos.offset(cell, 0, dy, 0), Double.MAX_VALUE);
            }
        }
        stuckTick = STUCK_DESTROY_THRESHOLD - 2;
    }

}
