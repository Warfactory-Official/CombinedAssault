package com.wf.combinedassault.vehicle.collision.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllConfigs;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.RigidStep;
import com.wf.combinedassault.vehicle.PhysicsTrace;
import com.wf.combinedassault.vehicle.part.gametest.ClimbBench;
import com.wf.wflib.util.ForcedChunks;
import com.wf.wflib.util.OBB;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.gametest.GameTestHolder;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Mixed vehicles thrown into a walled pit, one per {@link #STAGGER} ticks; {@code [pit]} rows every {@link #ROW}
 * ticks, one summary; {@code [pit] KICK} per upward kick, {@code FAST} per speed spike, {@code WALL} per hull into a wall, {@code SINK} per row with a pair deeper than {@link #SINK}, {@code ESCAPE} per hull out, {@code STATS} ({@link RigidStep#drainStats}, all seeds) per seed-0 row. {@code -Pbench=pit} sync solve,
 * {@code -Pbench=pit_async} async; filter containing {@code shadow} => pair-cache hits diffed (skews timing).
 */
@GameTestHolder(ClimbBench.NAMESPACE)
public class PitBench {

    private static final String[] VEHICLES = {"humvee", "m1a2", "m983", "t90", "m1129", "centauro",
            "merkava", "m142", "mim104", "m252a1"};
    private static final int SEEDS = 3;
    private static final int LANE_X = -8000;
    private static final int LANE_PITCH = 200;
    /** Floor (2 HALF + 1)^2 inside the walls. */
    private static final int HALF = 12;
    private static final int WALL = 16;
    /** Floor ring outside the walls: an escaped hull lands, not falls forever. */
    private static final int APRON = 24;
    private static final int STAGGER = 10;
    private static final int SETTLE = 400;
    private static final int ROW = 20;
    /** Rest: every hull below this speed (b/t). */
    private static final double REST = 0.02;
    /** Hull this deep into a wall logged once. */
    private static final double WALL_DEPTH = 0.3;
    /** Upward kick worth counting (b/t); a thrown hull starts at vy 0. */
    private static final double KICK = 0.5;
    /** Speed (b/t) past a throw's: {@code [pit] FAST} once per crossing. */
    private static final double FAST = 1.2;
    /** Hull-hull depth logged per row past this: {@code [pit] SINK}. */
    private static final double SINK = 0.3;
    /**
     * {@code -Pbench=pit_trace[_<vehicle>]}: per-vehicle {@link PhysicsTrace} (forces that vehicle's sync solve), all
     * or the named one -> run/gametest/pit-traces.
     */
    private static final String TRACE = traced();

    @GameTestGenerator
    public static List<TestFunction> pitBench() {
        List<TestFunction> tests = new ArrayList<>();
        String filter = System.getProperty("combinedassault.bench.filter", "");
        if (!filter.startsWith("pit")) {
            return tests;
        }
        for (int seed = 0; seed < SEEDS; seed++) {
            int s = seed;
            tests.add(new TestFunction("pitbench", "pit_" + seed, ClimbBench.NAMESPACE + ":empty",
                    VEHICLES.length * STAGGER + SETTLE + 200, 0, false, helper -> run(helper, s)));
        }
        return tests;
    }

    private static void run(GameTestHelper helper, int seed) {
        String filter = System.getProperty("combinedassault.bench.filter", "");
        AllConfigs.Cached.asyncVehiclePhysics = filter.contains("async");
        RigidStep.stats = true;
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(BlockPos.ZERO).offset(LANE_X - seed * LANE_PITCH, 0, 0);
        force(level, centre, true);
        build(level, centre, Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState());
        RandomSource random = RandomSource.create(seed);
        AbstractVehicle[] vehicles = new AbstractVehicle[VEHICLES.length];
        int[] born = new int[VEHICLES.length];
        double floor = centre.getY() + 1;
        // worst speed, spin, upward kick, rise; summed and deepest pair depth; escapes; deepest hull in a wall
        double[] worst = new double[7];
        AABB[] walls = walls(centre);
        int[] kicks = new int[1];
        int[] lastMoving = new int[1];
        boolean[] kicked = new boolean[VEHICLES.length];
        boolean[] out = new boolean[VEHICLES.length];
        boolean[] walled = new boolean[VEHICLES.length];
        Vec3[] last = new Vec3[VEHICLES.length];
        String mode = AllConfigs.Cached.asyncVehiclePhysics ? "async" : "sync";
        int end = VEHICLES.length * STAGGER + SETTLE;
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(centre), "pit not ticking"))
                .thenExecuteFor(end, () -> {
                    long t = helper.getTick();
                    int index = (int) (t / STAGGER);
                    if (t % STAGGER == 0 && index < VEHICLES.length && vehicles[index] == null) {
                        Vec3 at = new Vec3(centre.getX() + 0.5 + (random.nextDouble() - 0.5) * 8,
                                floor + 6 + random.nextDouble() * 10,
                                centre.getZ() + 0.5 + (random.nextDouble() - 0.5) * 8);
                        vehicles[index] = spawn(helper, VEHICLES[index], at, random.nextFloat() * 360);
                        if (TRACE != null && (TRACE.isEmpty() || TRACE.equals(VEHICLES[index]))) {
                            vehicles[index].setPhysicsTrace(new PhysicsTrace(vehicles[index], PhysicsTrace.MAX_TICKS));
                        }
                        double angle = random.nextDouble() * Math.PI * 2;
                        double speed = 0.1 + random.nextDouble() * 0.2;
                        vehicles[index].setDeltaMovement(Math.cos(angle) * speed, 0, Math.sin(angle) * speed);
                        born[index] = (int) t;
                    }
                    double speed = 0, spin = 0, kick = 0, rise = 0, wall = 0;
                    int escaped = 0;
                    boolean moving = false;
                    for (int i = 0; i < vehicles.length; i++) {
                        AbstractVehicle v = vehicles[i];
                        if (v == null) {
                            continue;
                        }
                        Vec3 dv = v.getDeltaMovement();
                        Vec3 previous = last[i];
                        last[i] = dv;
                        if (!Double.isFinite(v.getX() + v.getY() + v.getZ())) {
                            helper.fail(VEHICLES[i] + " position not finite");
                        }
                        speed = Math.max(speed, dv.length());
                        if (dv.length() > FAST && previous != null && previous.length() <= FAST) {
                            CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                                    "[pit] FAST %s seed %d t %d %s v %s was %s y %.2f rot %.0f/%.0f ground %s touching %s",
                                    mode, seed, t, VEHICLES[i], dv, previous, v.getY() - floor, v.getXRot(), v.getZRot(),
                                    v.onGround(), touching(vehicles, i)));
                        }
                        spin = Math.max(spin, v.physicsEngine.angularVelocity.length());
                        moving |= dv.length() > REST;
                        if (t - born[i] > 20) {
                            kick = Math.max(kick, dv.y);
                            if (dv.y > KICK) {
                                kicks[0]++;
                                if (!kicked[i]) {
                                    kicked[i] = true;
                                    CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                                            "[pit] KICK %s seed %d t %d %s v %s was %s y %.2f ground %s touching %s",
                                            mode, seed, t, VEHICLES[i], dv, previous, v.getY() - floor, v.onGround(),
                                            touching(vehicles, i)));
                                }
                            } else if (dv.y < 0.1) {
                                kicked[i] = false;
                            }
                            rise = Math.max(rise, v.getY() - floor);
                        }
                        // Hull centre, not origin: an M1129 origin sits 1.2 off its hull, past the wall while the hull is in.
                        OBB hull = v.getMainCubeOBB().obb();
                        double into = 0;
                        for (AABB slab : walls) {
                            into = Math.max(into, hull.calculateMTV(slab).length());
                        }
                        wall = Math.max(wall, into);
                        if (into > WALL_DEPTH && !walled[i]) {
                            walled[i] = true;
                            CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                                    "[pit] WALL %s seed %d t %d %s %.2f deep, hull at (%.2f %.2f %.2f) rot %.0f/%.0f v %s",
                                    mode, seed, t, VEHICLES[i], into, hull.centerX() - centre.getX() - 0.5,
                                    hull.centerY() - floor, hull.centerZ() - centre.getZ() - 0.5, v.getXRot(), v.getZRot(), dv));
                        }
                        double hx = hull.centerX() - centre.getX() - 0.5, hz = hull.centerZ() - centre.getZ() - 0.5;
                        if (Math.abs(hx) > HALF + 1 || Math.abs(hz) > HALF + 1 || v.getY() < floor - 2) {
                            escaped++;
                            if (!out[i]) {
                                out[i] = true;
                                CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                                        "[pit] ESCAPE %s seed %d t %d %s hull at (%.2f %.2f %.2f) v %s",
                                        mode, seed, t, VEHICLES[i], hx, hull.centerY() - floor, hz, dv));
                            }
                        }
                    }
                    double[] depth = overlap(vehicles);
                    worst[0] = Math.max(worst[0], speed);
                    worst[1] = Math.max(worst[1], spin);
                    worst[2] = Math.max(worst[2], kick);
                    worst[3] = Math.max(worst[3], depth[1]);
                    worst[4] = Math.max(worst[4], escaped);
                    worst[5] = Math.max(worst[5], rise);
                    worst[6] = Math.max(worst[6], wall);
                    if (moving) {
                        lastMoving[0] = (int) t;
                    }
                    if (t % ROW == 0) {
                        CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                                "[pit] %s seed %d t %d speed %.3f spin %.3f kick %.3f rise %.2f depth sum %.3f max %.3f (%s) wall %.3f escaped %d",
                                mode, seed, t, speed, spin, kick, rise, depth[0], depth[1], pair(depth), wall, escaped));
                        if (seed == 0) {
                            CombinedAssault.LOGGER.info(String.format(Locale.ROOT, "[pit] STATS %s t %d %s", mode, t,
                                    RigidStep.drainStats()));
                        }
                        if (depth[1] > SINK) {
                            int a = (int) depth[2];
                            CombinedAssault.LOGGER.info(String.format(Locale.ROOT, "[pit] SINK %s seed %d t %d %s rot %.0f/%.0f y %.2f v %s ground %s touching %s",
                                    mode, seed, t, VEHICLES[a], vehicles[a].getXRot(), vehicles[a].getZRot(), vehicles[a].getY() - floor,
                                    vehicles[a].getDeltaMovement(), vehicles[a].onGround(), touching(vehicles, a)));
                        }
                    }
                })
                .thenExecute(() -> {
                    double[] depth = overlap(vehicles);
                    StringBuilder rest = new StringBuilder();
                    for (int i = 0; i < vehicles.length; i++) {
                        AbstractVehicle v = vehicles[i];
                        rest.append(String.format(Locale.ROOT, " %s(%.2f,%.0f/%.0f)", VEHICLES[i],
                                v.getDeltaMovement().length(), v.getXRot(), v.getZRot()));
                    }
                    CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                            "[pit] %s seed %d SUMMARY worst speed %.3f spin %.3f kick %.3f (>%.1f: %d ticks) rise %.2f depth %.3f wall %.3f escaped %d | last moving t %d of %d | final depth sum %.3f max %.3f |%s",
                            mode, seed, worst[0], worst[1], worst[2], KICK, kicks[0], worst[5], worst[3], worst[6], (int) worst[4],
                            lastMoving[0], end, depth[0], depth[1], rest));
                    for (AbstractVehicle v : vehicles) {
                        PhysicsTrace trace = PhysicsTrace.of(v);
                        if (trace != null) {
                            try {
                                trace.dump(FMLPaths.GAMEDIR.get().resolve("pit-traces/seed" + seed));
                                trace.dumpSweeps(FMLPaths.GAMEDIR.get().resolve("pit-traces/seed" + seed));
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        }
                        v.discard();
                    }
                    build(level, centre, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState());
                    force(level, centre, false);
                })
                .thenSucceed();
    }

    /** null: no trace; "": every vehicle. */
    private static String traced() {
        String filter = System.getProperty("combinedassault.bench.filter", "");
        int at = filter.indexOf("trace");
        if (at < 0) {
            return null;
        }
        String rest = filter.substring(at + "trace".length());
        return rest.startsWith("_") ? rest.substring(1) : "";
    }

    private static String pair(double[] depth) {
        return depth[2] < 0 ? "-" : VEHICLES[(int) depth[2]] + "/" + VEHICLES[(int) depth[3]];
    }

    /** Vehicles whose bounds meet vehicle i's, with deepest piece depth. */
    private static String touching(AbstractVehicle[] vehicles, int i) {
        StringBuilder out = new StringBuilder();
        Vector3f axis = new Vector3f();
        for (int j = 0; j < vehicles.length; j++) {
            if (j == i || vehicles[j] == null || !vehicles[i].getBoundingBox().intersects(vehicles[j].getBoundingBox())) {
                continue;
            }
            float deepest = 0;
            for (OBB p : vehicles[i].getOBBs()) {
                for (OBB q : vehicles[j].getOBBs()) {
                    deepest = Math.max(deepest, OBB.mtv(p, 0, 0, 0, q, axis));
                }
            }
            out.append(String.format(Locale.ROOT, "%s(%.3f,dy %.2f,v %s) ", VEHICLES[j], deepest,
                    vehicles[j].getY() - vehicles[i].getY(), vehicles[j].getDeltaMovement()));
        }
        return out.toString();
    }

    /** {sum, max, a, b} of piece-pair depth over vehicle pairs; a, b = deepest pair (-1: none). */
    private static double[] overlap(AbstractVehicle[] vehicles) {
        double sum = 0, max = 0, deepA = -1, deepB = -1;
        Vector3f axis = new Vector3f();
        for (int a = 0; a < vehicles.length; a++) {
            for (int b = a + 1; b < vehicles.length; b++) {
                if (vehicles[a] == null || vehicles[b] == null
                        || !vehicles[a].getBoundingBox().intersects(vehicles[b].getBoundingBox())) {
                    continue;
                }
                for (OBB p : vehicles[a].getOBBs()) {
                    for (OBB q : vehicles[b].getOBBs()) {
                        float depth = OBB.mtv(p, 0, 0, 0, q, axis);
                        if (depth > 0) {
                            sum += depth;
                            if (depth > max) {
                                max = depth;
                                deepA = a;
                                deepB = b;
                            }
                        }
                    }
                }
            }
        }
        return new double[] {sum, max, deepA, deepB};
    }

    private static AbstractVehicle spawn(GameTestHelper helper, String name, Vec3 at, float yaw) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, name);
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        helper.assertTrue(data.isPresent(), id + " is not in the roster");
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(), at, 0, yaw);
        vehicle.setDisplayId(id);
        helper.assertTrue(helper.getLevel().addFreshEntity(vehicle), id + " could not be added");
        return vehicle;
    }

    /** Inner wall slabs, floor to top. */
    private static AABB[] walls(BlockPos centre) {
        double x = centre.getX(), y = centre.getY() + 1, z = centre.getZ();
        double low = -HALF - 1, high = HALF + 1, span = HALF + 2;
        return new AABB[] {
                new AABB(x + low, y, z - span, x + low + 1, y + WALL, z + span),
                new AABB(x + high, y, z - span, x + high + 1, y + WALL, z + span),
                new AABB(x - span, y, z + low, x + span, y + WALL, z + low + 1),
                new AABB(x - span, y, z + high, x + span, y + WALL, z + high + 1)};
    }

    private static void build(ServerLevel level, BlockPos centre, BlockState floor, BlockState wall) {
        for (int x = -HALF - 1 - APRON; x <= HALF + 1 + APRON; x++) {
            for (int z = -HALF - 1 - APRON; z <= HALF + 1 + APRON; z++) {
                level.setBlockAndUpdate(centre.offset(x, 0, z), floor);
                if (Math.abs(x) == HALF + 1 || Math.abs(z) == HALF + 1) {
                    for (int y = 1; y <= WALL; y++) {
                        level.setBlockAndUpdate(centre.offset(x, y, z), wall);
                    }
                }
            }
        }
    }

    private static void force(ServerLevel level, BlockPos centre, boolean forced) {
        int reach = HALF + APRON + 16;
        for (int cx = (centre.getX() - reach) >> 4; cx <= (centre.getX() + reach) >> 4; cx++) {
            for (int cz = (centre.getZ() - reach) >> 4; cz <= (centre.getZ() + reach) >> 4; cz++) {
                ForcedChunks.set(level, cx, cz, forced);
            }
        }
    }
}
