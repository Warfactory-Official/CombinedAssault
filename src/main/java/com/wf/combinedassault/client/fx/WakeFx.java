package com.wf.combinedassault.client.fx;

import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.ShipVehicle;
import com.wf.combinedassault.naval.Propeller;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;
import com.wf.gemrender.particle.ParticleEmitter;
import com.wf.gemrender.particle.ParticleLook;
import com.wf.gemrender.particle.ParticleStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Hull wakes (any vehicle whose main hull cuts the surface under way) and screw wash ({@link ShipVehicle} propellers):
 * foam decals, bow spray, bubbles. Client only, no packet.
 */
public final class WakeFx {

    private static final double RANGE = 192;
    /** Blocks/tick: below => no wake (~3 kn). */
    private static final double WAKE_SPEED = 0.08;
    /** Blocks/tick over {@link #WAKE_SPEED} for full wake. */
    private static final double WAKE_FULL = 0.4;
    /** {@link HitFxKind#BUBBLE} style: buoyancy, blocks/s^2; drag, per tick factor. */
    static final float BUBBLE_LIFT = 6;
    static final float BUBBLE_DRAG = 0.9F;
    private static final float BUBBLE_K = ParticleStyle.dragFromPerTickFactor(BUBBLE_DRAG);
    /** Terminal rise, blocks/s. */
    private static final float BUBBLE_RISE = BUBBLE_LIFT / BUBBLE_K;
    private static final float BUBBLE_VY = 0.5F;
    /** Rise time = d / BUBBLE_RISE + this (spin-up from BUBBLE_VY). */
    private static final float BUBBLE_LAG = (BUBBLE_RISE - BUBBLE_VY) / (BUBBLE_K * BUBBLE_RISE);
    /** Stern raft size cap, blocks. */
    private static final float RAFT = 1.6F;
    private static final int BUDGET = 256;
    private static final float TAU = Mth.TWO_PI;
    private static final RandomSource RANDOM = RandomSource.create();

    // Harness.
    static long nanos;
    static long ticks;
    static int foams;
    static int sprays;
    static int bubbles;

    private static int spent;

    private WakeFx() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) {
            return;
        }
        long t0 = System.nanoTime();
        spent = 0;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        for (Entity e : level.entitiesForRendering()) {
            if (e instanceof AbstractVehicle v && v.getMainCubeOBB() != null && e.distanceToSqr(cam) < RANGE * RANGE) {
                vehicle(level, v);
            }
        }
        nanos += System.nanoTime() - t0;
        ticks++;
    }

    private static void vehicle(ClientLevel level, AbstractVehicle v) {
        Quaternionf rot = v.rotYXZ(1);
        Vec3 forward = dir(rot, 0, 0, 1);
        Vec3 right = dir(rot, -1, 0, 0);
        double vx = v.getX() - v.xo;
        double vz = v.getZ() - v.zo;
        double speed = Math.sqrt(vx * vx + vz * vz);
        if (speed > WAKE_SPEED) {
            hull(level, v, rot, forward, right, speed);
        }
        if (v instanceof ShipVehicle ship && ship.hasPower() && Math.abs(ship.throttle()) > 0.05F) {
            for (Propeller p : ship.propellers()) {
                screw(level, ship, rot, forward, p);
            }
        }
    }

    /** Main hull pierces the surface: stern raft trail, shoulder foam, bow spray. */
    private static void hull(ClientLevel level, AbstractVehicle v, Quaternionf rot, Vec3 forward, Vec3 right,
                             double speed) {
        VehicleCubeOBB main = v.getMainCubeOBB();
        Vec3 c = main.offset();
        Vector3f e = main.obb().extents();
        Vec3 centre = world(v, rot, c);
        double surface = surface(level, centre.x, centre.y + e.y, centre.z, e.y * 2 + 2);
        if (Double.isNaN(surface) || surface < centre.y - e.y - 0.5 || surface > centre.y + e.y + 0.5) {
            return;
        }
        float k = (float) Mth.clamp((speed - WAKE_SPEED) / WAKE_FULL, 0, 1);
        double y = surface + 0.02;
        int lit = ParticleLook.lit(level, centre.x, surface + 0.5, centre.z, ParticleLook.WHITE);
        // Stern: rafts across the beam, left behind as the hull moves on. Raft <= RAFT: broad hull => more rafts,
        // not bigger (one beam-wide raft per tick = uniform smear).
        Vec3 stern = world(v, rot, c.add(0, 0, -e.z));
        float raft = Math.min(RAFT, e.x * (0.6F + 0.4F * k));
        int n = Math.round((1 + 3 * k) * Math.max(1, e.x / RAFT));
        for (int i = 0; i < n; i++) {
            double across = (RANDOM.nextFloat() * 2 - 1) * e.x * 0.6;
            foam(stern.x + right.x * across, y, stern.z + right.z * across, 6 + 6 * k, raft, lit);
        }
        // Shoulders: bow wave folding along the sides.
        Vec3 bow = world(v, rot, c.add(0, 0, e.z * 0.85));
        for (int side = -1; side <= 1; side += 2) {
            if (RANDOM.nextFloat() < 0.4F + 0.6F * k) {
                foam(bow.x + right.x * side * e.x, y, bow.z + right.z * side * e.x, 3 + 3 * k,
                        Math.max(0.6F, e.x * 0.5F), lit);
            }
            if (k > 0.15F) {
                int drops = Math.round(2 + 6 * k);
                for (int i = 0; i < drops && take(); i++) {
                    float out = (2 + 4 * k) * (0.6F + 0.4F * RANDOM.nextFloat());
                    float up = (2 + 4 * k) * (0.5F + 0.5F * RANDOM.nextFloat());
                    spawn(HitFxKind.SPRAY, bow.x + right.x * side * e.x * 0.7, surface + 0.1,
                            bow.z + right.z * side * e.x * 0.7,
                            right.x * side * out + forward.x * speed * 10, up, right.z * side * out + forward.z * speed * 10,
                            0.6F + 0.5F * RANDOM.nextFloat(), 0.6F + 0.8F * k, lit);
                    sprays++;
                }
            }
        }
    }

    /** One screw: bubbles in the water, boil when shallow, plume when at the surface (waterjet). */
    private static void screw(ClientLevel level, ShipVehicle ship, Quaternionf rot, Vec3 forward, Propeller p) {
        Vec3 hub = world(ship, rot, p.offset());
        float throttle = Math.abs(ship.throttle());
        float aft = -Math.signum(ship.throttle());
        double surface = surface(level, hub.x, hub.y, hub.z, 4);
        if (Double.isNaN(surface)) {
            return;
        }
        double depth = surface - hub.y;
        int lit = ParticleLook.lit(level, hub.x, surface + 0.5, hub.z, ParticleLook.WHITE);
        if (depth > p.radius() * 0.5) {
            int n = Math.round(throttle * (2 + p.radius() * 3));
            Vec3 right = dir(rot, -1, 0, 0);
            Vec3 up = dir(rot, 0, 1, 0);
            for (int i = 0; i < n && take(); i++) {
                float a = RANDOM.nextFloat() * TAU;
                float r = p.radius() * (0.4F + 0.6F * RANDOM.nextFloat());
                double x = hub.x + (right.x * Mth.cos(a) + up.x * Mth.sin(a)) * r;
                double y = hub.y + (right.y * Mth.cos(a) + up.y * Mth.sin(a)) * r;
                double z = hub.z + (right.z * Mth.cos(a) + up.z * Mth.sin(a)) * r;
                float wash = aft * throttle * (3 + 3 * RANDOM.nextFloat());
                float life = (float) Mth.clamp((surface - y - 0.2) / BUBBLE_RISE + BUBBLE_LAG, 0.1, 3);
                spawn(HitFxKind.BUBBLE, x, y, z, forward.x * wash, BUBBLE_VY, forward.z * wash, life,
                        0.7F + 0.8F * RANDOM.nextFloat(), ParticleLook.NONE);
                bubbles++;
            }
        }
        // Boil: wash reaches the surface while the screw is within a few diameters of it.
        double reach = p.radius() * 2.5 + 3;
        if (depth < reach && RANDOM.nextFloat() < throttle * (float) (1 - Math.max(depth, 0) / reach)) {
            double back = p.radius() * 2 + throttle * 3;
            foam(hub.x + forward.x * aft * back, surface + 0.02, hub.z + forward.z * aft * back, 5 + 4 * throttle,
                    p.radius() * 1.6F, lit);
        }
        if (Math.abs(depth) < p.radius() * 0.5 + 0.3) {
            int drops = Math.round(1 + 4 * throttle);
            for (int i = 0; i < drops && take(); i++) {
                float back = aft * throttle * (3 + 3 * RANDOM.nextFloat());
                spawn(HitFxKind.SPRAY, hub.x, surface + 0.05, hub.z, forward.x * back + RANDOM.nextGaussian() * 0.4,
                        (2 + 3 * throttle) * (0.6F + 0.4F * RANDOM.nextFloat()),
                        forward.z * back + RANDOM.nextGaussian() * 0.4, 0.5F + 0.4F * RANDOM.nextFloat(),
                        0.4F + 0.5F * throttle, lit);
                sprays++;
            }
        }
    }

    private static void foam(double x, double y, double z, float life, float size, int look) {
        if (!take()) {
            return;
        }
        ParticleEmitter em = HitFxWorld.emitter(HitFxKind.FOAM, x, y, z);
        if (em != null) {
            em.spawnDecal(x, y, z, 0, 1, 0, life, size * (0.8F + 0.4F * RANDOM.nextFloat()), RANDOM.nextFloat() * TAU,
                    look);
            foams++;
        }
    }

    private static void spawn(HitFxKind kind, double x, double y, double z, double vx, double vy, double vz, float life,
                              float size, int look) {
        ParticleEmitter em = HitFxWorld.emitter(kind, x, y, z);
        if (em != null) {
            em.spawn(x, y, z, vx, vy, vz, life, size, RANDOM.nextFloat() * TAU, 1, look);
        }
    }

    private static boolean take() {
        return spent++ < BUDGET;
    }

    /** Vehicle frame -> world (double position; posed OBB centre is a float). */
    private static Vec3 world(AbstractVehicle v, Quaternionf rot, Vec3 local) {
        Vector3f o = rot.transform(local.subtract(v.centerOffset).toVector3f());
        return v.position().add(v.centerOffset).add(o.x, o.y, o.z);
    }

    private static Vec3 dir(Quaternionf rot, float x, float y, float z) {
        Vector3f d = rot.transform(new Vector3f(x, y, z));
        return new Vec3(d.x, d.y, d.z);
    }

    /** Water surface height in the column through (x, z): up from a wet point, else down {@code span}; NaN = none. */
    private static double surface(ClientLevel level, double x, double y, double z, double span) {
        BlockPos.MutableBlockPos at = BlockPos.containing(x, y, z).mutable();
        if (level.getFluidState(at).is(FluidTags.WATER)) {
            for (int i = 0; i < 64 && level.getFluidState(at.move(0, 1, 0)).is(FluidTags.WATER); i++) {
            }
            at.move(0, -1, 0);
            return at.getY() + level.getFluidState(at).getHeight(level, at);
        }
        for (int i = 0; i < span; i++) {
            FluidState fluid = level.getFluidState(at.move(0, -1, 0));
            if (fluid.is(FluidTags.WATER)) {
                return at.getY() + fluid.getHeight(level, at);
            }
        }
        return Double.NaN;
    }
}
