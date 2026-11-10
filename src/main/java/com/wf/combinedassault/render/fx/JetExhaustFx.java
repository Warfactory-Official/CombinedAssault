package com.wf.combinedassault.render.fx;

import com.wf.combinedassault.aircraft.FixedWingVehicle;
import com.wf.combinedassault.aircraft.part.ThrustUnit;
import com.wf.combinedassault.aircraft.pojo.Exhaust;
import com.wf.gemrender.light.LightSink;
import com.wf.gemrender.light.Lights;
import com.wf.gemrender.particle.ParticleEmitter;
import com.wf.gemrender.particle.ParticleLook;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * {@link FixedWingVehicle#exhausts}: nozzle glow, dry plume, afterburner (throttle > 100), smoke, afterburner light.
 * Flame particles spawn each tick at the frame's interpolated pose ({@code ParticleClock} = same partial tick) and fly
 * at the airframe's velocity => attached. Client only, no packet.
 */
public final class JetExhaustFx {

    private static final double RANGE = 384;
    private static final double SMOKE_RANGE = 256;
    /** Past this: half the envelope stages (bigger), no diamonds. */
    private static final double DETAIL_RANGE = 128;
    /** Flame life, s: two tick generations alive. */
    private static final float FLAME_LIFE = 0.1F;
    /** Flame drift along the flow, radii/s. */
    private static final float FLAME_FLOW = 4;
    /** Afterburner light-up / cut per tick. */
    private static final float AB_UP = 0.2F;
    private static final float AB_DOWN = 0.34F;
    private static final int SMOKE_MAX = 6;
    private static final int SWEEP_TICKS = 40;

    private static final int LOOK_GLOW = ParticleLook.of(0xFF5418);
    private static final int LOOK_DRY = ParticleLook.of(0xFF7A3C);
    private static final int LOOK_CORE = ParticleLook.of(0xFFD090);
    private static final int LOOK_DIAMOND = ParticleLook.of(0xFFF0D0);
    /** Envelope, nozzle -> tail: yellow, orange, violet. */
    private static final int[] ENVELOPE = {ParticleLook.of(0xFFB050), ParticleLook.of(0xFF8A30),
            ParticleLook.of(0xFF6A28), ParticleLook.of(0xF05838), ParticleLook.of(0xD05060), ParticleLook.of(0xA04880),
            ParticleLook.of(0x704090)};
    private static final int DIAMONDS = 4;
    private static final int AB_RGB = 0xFF9448;

    private static final RandomSource RANDOM = RandomSource.create();
    private static final Int2ObjectOpenHashMap<State> STATES = new Int2ObjectOpenHashMap<>();
    private static final Quaternionf ROT = new Quaternionf();
    private static final Quaternionf PREV = new Quaternionf();
    private static final Vector3f AT = new Vector3f();
    private static final Vector3f DIR = new Vector3f();
    private static final Vector3f SUM = new Vector3f();
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    // Harness.
    static long nanos;
    static long ticks;
    static int flames;
    static int smokes;
    static int lights;

    private static int stamp;
    /** Pose of the vehicle {@link #pose} last filled. */
    private static double px;
    private static double py;
    private static double pz;
    private static float pitch;

    private static final class State {
        int seen;
        float ab;
        boolean hasLast;
        final double[] last;
        /** Fractional smoke puffs owed, per nozzle. */
        final float[] debt;

        State(int nozzles) {
            last = new double[nozzles * 3];
            debt = new float[nozzles];
        }
    }

    private JetExhaustFx() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
        Lights.register(JetExhaustFx::collect);
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) {
            return;
        }
        long t0 = System.nanoTime();
        stamp++;
        float pt = mc.getTimer().getGameTimeDeltaPartialTick(false);
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        for (Entity e : level.entitiesForRendering()) {
            if (e instanceof FixedWingVehicle v && !v.exhausts.isEmpty() && !v.isDestroyed()) {
                double d2 = e.distanceToSqr(cam);
                if (d2 < RANGE * RANGE) {
                    vehicle(level, v, pt, d2 < SMOKE_RANGE * SMOKE_RANGE, d2 > DETAIL_RANGE * DETAIL_RANGE);
                }
            }
        }
        if (stamp % SWEEP_TICKS == 0) {
            for (ObjectIterator<State> it = STATES.values().iterator(); it.hasNext(); ) {
                if (stamp - it.next().seen > SWEEP_TICKS) {
                    it.remove();
                }
            }
        }
        nanos += System.nanoTime() - t0;
        ticks++;
    }

    private static void vehicle(ClientLevel level, FixedWingVehicle v, float pt, boolean smoke, boolean far) {
        State s = STATES.get(v.getId());
        if (s == null || s.last.length != v.exhausts.size() * 3) {
            s = new State(v.exhausts.size());
            STATES.put(v.getId(), s);
        }
        boolean continuous = s.seen == stamp - 1;
        s.seen = stamp;
        float power = Mth.clamp(v.getPower() / 100, 0, 1);
        float throttle = v.throttleLevel / 100;
        float mil = Math.min(throttle, 1) * power;
        s.ab = throttle > 1 && v.hasPower() ? Math.min(1, s.ab + AB_UP) : Math.max(0, s.ab - AB_DOWN);
        if (power < 0.02F && s.ab == 0) {
            s.hasLast = false;
            return;
        }
        s.hasLast &= continuous;
        pose(v, pt);
        // Blocks/s; flame rides it.
        double ux = (v.getX() - v.xo) * 20;
        double uy = (v.getY() - v.yo) * 20;
        double uz = (v.getZ() - v.zo) * 20;
        for (int i = 0; i < v.exhausts.size(); i++) {
            Exhaust e = v.exhausts.get(i);
            nozzle(v, e);
            double x = px + AT.x;
            double y = py + AT.y;
            double z = pz + AT.z;
            flame(e, x, y, z, ux, uy, uz, mil, s.ab, far);
            if (smoke && e.smoke > 0) {
                smoke(level, s, i, e, x, y, z, ux, uy, uz, mil);
            }
            int k = i * 3;
            s.last[k] = x;
            s.last[k + 1] = y;
            s.last[k + 2] = z;
        }
        s.hasLast = true;
    }

    /** Fills {@link #ROT}, {@link #px}..{@link #pz}, {@link #pitch} at {@code pt}. */
    private static void pose(FixedWingVehicle v, float pt) {
        px = Mth.lerp(pt, v.xo, v.getX());
        py = Mth.lerp(pt, v.yo, v.getY());
        pz = Mth.lerp(pt, v.zo, v.getZ());
        PREV.identity().rotateY(-v.yRotO * Mth.DEG_TO_RAD).rotateX(v.xRotO * Mth.DEG_TO_RAD)
                .rotateZ(v.zRotO * Mth.DEG_TO_RAD);
        PREV.slerp(v.rotYXZShared(), pt, ROT);
        ThrustUnit thrust = v.thrustUnit;
        pitch = thrust == null ? 0 : Mth.lerp(pt, thrust.xRotO, thrust.getXRot()) * Mth.DEG_TO_RAD;
    }

    /** {@link #AT} = exit relative to the posed origin, {@link #DIR} = flow, world axes. */
    private static void nozzle(FixedWingVehicle v, Exhaust e) {
        AT.set((float) e.offset.x, (float) e.offset.y, (float) e.offset.z);
        DIR.set((float) e.direction.x, (float) e.direction.y, (float) e.direction.z);
        if (e.pivot != null) {
            AT.sub((float) e.pivot.x, (float) e.pivot.y, (float) e.pivot.z).rotateX(pitch)
                    .add((float) e.pivot.x, (float) e.pivot.y, (float) e.pivot.z);
            DIR.rotateX(pitch);
        }
        Vec3 c = v.centerOffset;
        AT.sub((float) c.x, (float) c.y, (float) c.z).rotate(ROT).add((float) c.x, (float) c.y, (float) c.z);
        DIR.rotate(ROT);
    }

    /** Heat and brightness fall along the flow: hot core, envelope cooling to violet, fading diamonds. */
    private static void flame(Exhaust e, double x, double y, double z, double ux, double uy, double uz, float mil,
                              float ab, boolean far) {
        ParticleEmitter em = HitFxWorld.emitter(HitFxKind.JET_FLAME, x, y, z);
        if (em == null) {
            return;
        }
        float r = e.radius;
        float hot = e.heat * (0.12F + 0.38F * mil);
        attach(em, x, y, z, ux, uy, uz, r, 0.15F, 1.9F * r, Math.min(1, hot + ab), LOOK_GLOW);
        float dry = e.heat * Math.max(0, mil - 0.4F) * (1 - ab);
        if (dry > 0) {
            attach(em, x, y, z, ux, uy, uz, r, 1.0F, 2.0F * r, 0.35F * dry, LOOK_DRY);
            attach(em, x, y, z, ux, uy, uz, r, 2.4F, 2.4F * r, 0.18F * dry, LOOK_DRY);
        }
        if (ab <= 0 || e.heat <= 0) {
            return;
        }
        float len = 0.55F + 0.45F * ab;
        attach(em, x, y, z, ux, uy, uz, r, 0.3F, 2.0F * r, 0.7F * ab, LOOK_CORE);
        int step = far ? 2 : 1;
        float wide = far ? 1.35F : 1;
        for (int i = 0; i < ENVELOPE.length; i += step) {
            float at = (0.5F + 1.6F * i) * len;
            float size = 2 * r * wide * (1.55F + 0.05F * i) * (0.85F + 0.3F * RANDOM.nextFloat());
            attach(em, x, y, z, ux, uy, uz, r, at, size, ab * (0.95F - 0.11F * i) * (0.8F + 0.2F * RANDOM.nextFloat()),
                    ENVELOPE[i]);
        }
        if (far) {
            return;
        }
        for (int k = 0; k < DIAMONDS; k++) {
            attach(em, x, y, z, ux, uy, uz, r, (1.8F + 2.0F * k) * len, r * (0.9F - 0.15F * k),
                    ab * (0.6F - 0.12F * k), LOOK_DIAMOND);
        }
    }

    /** {@code at} radii down the flow from the exit; {@code size} blocks across. */
    private static void attach(ParticleEmitter em, double x, double y, double z, double ux, double uy, double uz,
                               float r, float at, float size, float tint, int look) {
        float d = at * r;
        float flow = FLAME_FLOW * r;
        em.spawn(x + DIR.x * d, y + DIR.y * d, z + DIR.z * d, ux + DIR.x * flow, uy + DIR.y * flow, uz + DIR.z * flow,
                FLAME_LIFE, size, RANDOM.nextFloat() * Mth.TWO_PI, tint, look);
        flames++;
    }

    /** Puffs along this tick's exit path: fast mover => more, bigger; capped. */
    private static void smoke(ClientLevel level, State s, int i, Exhaust e, double x, double y, double z, double ux,
                              double uy, double uz, float mil) {
        ParticleEmitter em = HitFxWorld.emitter(HitFxKind.JET_SMOKE, x, y, z);
        if (em == null) {
            return;
        }
        float r = e.radius;
        int k = i * 3;
        double sx = s.hasLast ? s.last[k] : x;
        double sy = s.hasLast ? s.last[k + 1] : y;
        double sz = s.hasLast ? s.last[k + 2] : z;
        double seg = Math.sqrt((x - sx) * (x - sx) + (y - sy) * (y - sy) + (z - sz) * (z - sz));
        float want = e.smoke * (0.5F + 1.5F * mil) * (float) Math.max(1, seg / (2.5 * r));
        s.debt[i] += want;
        int n = (int) s.debt[i];
        s.debt[i] -= n;
        if (n == 0) {
            return;
        }
        float grow = 1;
        if (n > SMOKE_MAX) {
            grow = (float) Math.sqrt((double) n / SMOKE_MAX);
            n = SMOKE_MAX;
        }
        float shade = 0.92F - 0.4F * Math.min(1, e.smoke);
        int c = (int) (shade * 255);
        POS.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        int look = ParticleLook.of(c << 16 | c << 8 | c, level.getBrightness(LightLayer.BLOCK, POS),
                level.getBrightness(LightLayer.SKY, POS));
        float jet = (6 + 24 * mil) * r;
        for (int j = 0; j < n; j++) {
            double t = (j + RANDOM.nextFloat()) / n;
            spawnSmoke(em, sx + (x - sx) * t, sy + (y - sy) * t, sz + (z - sz) * t, ux * 0.25 + DIR.x * jet,
                    uy * 0.25 + DIR.y * jet, uz * 0.25 + DIR.z * jet, (1.4F + 1.4F * e.smoke) * (0.8F + 0.4F * RANDOM.nextFloat()),
                    2 * r * grow * (0.9F + 0.5F * RANDOM.nextFloat()), look);
        }
    }

    private static void spawnSmoke(ParticleEmitter em, double x, double y, double z, double vx, double vy, double vz,
                                   float life, float size, int look) {
        em.spawn(x, y, z, vx + RANDOM.nextGaussian() * 0.6, vy + RANDOM.nextGaussian() * 0.6,
                vz + RANDOM.nextGaussian() * 0.6, life, size, RANDOM.nextFloat() * Mth.TWO_PI, 1, look);
        smokes++;
    }

    /** One light per afterburning airframe at its hot nozzles' mean, 2 radii downstream. */
    private static void collect(LightSink sink, float partialTick) {
        lights = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || STATES.isEmpty()) {
            return;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof FixedWingVehicle v)) {
                continue;
            }
            State s = STATES.get(v.getId());
            if (s == null || s.ab <= 0 || s.seen != stamp) {
                continue;
            }
            pose(v, partialTick);
            SUM.zero();
            int hot = 0;
            for (Exhaust x : v.exhausts) {
                if (x.heat > 0) {
                    nozzle(v, x);
                    SUM.add(AT).add(DIR.mul(2 * x.radius));
                    hot++;
                }
            }
            if (hot == 0) {
                continue;
            }
            SUM.div(hot);
            float intensity = 10 * s.ab * Mth.sqrt(hot);
            sink.point(px + SUM.x, py + SUM.y, pz + SUM.z, 18, AB_RGB, intensity, false, false,
                    Mth.sqrt(intensity / 3));
            lights++;
        }
    }
}
