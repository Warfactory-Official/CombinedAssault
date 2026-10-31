package com.wf.combinedassault.vehicle.collision.gametest;

import com.wf.wflib.physics.api.PhysicsSettings;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.dynamics.PhysicsTrace;
import com.wf.combinedassault.vehicle.part.gametest.ClimbBench;
import com.wf.wflib.testkit.api.ForcedChunks;
import com.wf.wflib.geom.api.OBB;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
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
import com.wf.wflib.physics.api.PhysicsStep;

/**
 * Resting-contact scenes, {@code [stack]} rows every {@link #ROW} ticks + one {@code SUMMARY} per case, {@code STATS}
 * ({@link PhysicsStep#drainStats}) per row; one case per batch. {@code -Pbench=stack} sync, {@code stack_async} async,
 * {@code stack_trace} per-vehicle {@link PhysicsTrace} (sync) -> run/gametest/stack-traces/&lt;case&gt;.
 * <ul>
 * <li>{@code tower_*}: three hulls dropped onto each other.</li>
 * <li>{@code offset_<cm>}: T-90 across an M1A2's turret roof (one flat piece), COM {@code cm} past its side face
 * (+ = overhang); physical: tips iff cm > 0. Humvee lower FORBIDDEN: upper rests on its own wheel pieces.</li>
 * <li>{@code sandwich}: tank driven into a humvee parked against a wall, broadside.</li>
 * </ul>
 */
@GameTestHolder(ClimbBench.NAMESPACE)
public class StackBench {

    private static final int LANE_X = -12000;
    private static final int LANE_PITCH = 64;
    private static final int FLOOR = 12;
    private static final int ROW = 20;
    private static final double[] OFFSETS = {-1.0, -0.3, 0.3, 1.0};
    private static final String[] SINGLES = {"m1a2", "humvee", "mk15"};
    private static final String[][] TOWERS = {{"m1a2", "humvee", "humvee"}, {"humvee", "humvee", "m1a2"}};
    /** Sandwich wall at lane z + this; humvee's far side {@link #GAP} short of it. */
    private static final int WALL_Z = 4;
    private static final double GAP = 0.1;
    private static final int DRIVE = 160;
    private static final boolean TRACE = System.getProperty("combinedassault.bench.filter", "").contains("trace");

    @GameTestGenerator
    public static List<TestFunction> stackBench() {
        List<TestFunction> tests = new ArrayList<>();
        String filter = System.getProperty("combinedassault.bench.filter", "");
        if (!filter.startsWith("stack")) {
            return tests;
        }
        int lane = 0;
        for (String single : SINGLES) {
            int at = lane++;
            tests.add(test("single_" + single, 200, helper -> single(helper, at, single)));
        }
        for (String[] tower : TOWERS) {
            int at = lane++;
            tests.add(test("tower_" + String.join("_", tower), 400, helper -> tower(helper, at, tower)));
        }
        for (double offset : OFFSETS) {
            int at = lane++;
            tests.add(test(String.format(Locale.ROOT, "offset_%+d", Math.round(offset * 100)), 300,
                    helper -> offset(helper, at, offset)));
        }
        int at = lane;
        tests.add(test("sandwich", 400, helper -> sandwich(helper, at)));
        // -Pbench=stack_<part>: cases whose name holds it (async / trace keywords aside).
        String part = filter.replace("stack", "").replace("_async", "").replace("_trace", "").replace("_", "");
        if (!part.isEmpty()) {
            tests.removeIf(test -> !test.testName().replace("_", "").contains(part));
        }
        return tests;
    }

    private static TestFunction test(String name, int ticks, java.util.function.Consumer<GameTestHelper> body) {
        // Own batch => cases run one at a time => STATS per case.
        return new TestFunction("stackbench_" + name, name, ClimbBench.NAMESPACE + ":empty", ticks + 100, 0, false, helper -> {
            current = name;
            PhysicsSettings.override(PhysicsSettings.current().withAsync(System.getProperty("combinedassault.bench.filter", "").contains("async")));
            PhysicsStep.setStats(true);
            body.accept(helper);
        });
    }

    /** Case running; one per batch. */
    private static String current;

    private static String mode() {
        return PhysicsSettings.current().asyncStep() ? "async" : "sync";
    }

    /** One hull released 0.2 above the floor: every tick to 40, then every 20. */
    private static void single(GameTestHelper helper, int laneIndex, String name) {
        BlockPos lane = lane(helper, laneIndex, true);
        double floor = lane.getY() + 1;
        Vec3 base = Vec3.atBottomCenterOf(lane.above());
        AbstractVehicle[] v = new AbstractVehicle[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(helper.getLevel().isPositionEntityTicking(lane), "lane not ticking"))
                .thenExecuteFor(200, () -> {
                    long t = helper.getTick();
                    if (v[0] == null) {
                        v[0] = spawn(helper, name, base.add(0, 0.2, 0), 0);
                        return;
                    }
                    if (t <= 40 || t % ROW == 0) {
                        AbstractVehicle each = v[0];
                        row("single_" + name, t, String.format(Locale.ROOT, "y %.4f v %s w %s tilt %.2f ground %s xz %s",
                                each.getY() - floor, each.getDeltaMovement(), each.physicsEngine.angularVelocity, tilt(each),
                                each.onGround(), places(v, base)));
                    }
                })
                .thenSucceed();
    }

    /** Drops at 0, 30, 60; measures from 160. */
    private static void tower(GameTestHelper helper, int laneIndex, String[] names) {
        BlockPos lane = lane(helper, laneIndex, true);
        double floor = lane.getY() + 1;
        Vec3 base = Vec3.atBottomCenterOf(lane.above());
        AbstractVehicle[] v = new AbstractVehicle[names.length];
        String name = "tower_" + String.join("_", names);
        // per pair: worst depth; top hull: min/max x y z; worst speed, worst spin
        double[] pairDepth = new double[names.length - 1];
        double[] box = {Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE};
        double[] worst = new double[2];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(helper.getLevel().isPositionEntityTicking(lane), "lane not ticking"))
                .thenExecuteFor(400, () -> {
                    long t = helper.getTick();
                    for (int i = 0; i < names.length; i++) {
                        if (v[i] == null && t >= 30L * i) {
                            v[i] = spawn(helper, names[i], base.add(0, i == 0 ? 0.2 : 5 * i, 0), 0);
                        }
                    }
                    if (v[names.length - 1] == null || t < 60) {
                        return;
                    }
                    for (int i = 0; i + 1 < names.length; i++) {
                        pairDepth[i] = Math.max(pairDepth[i], depth(v[i], v[i + 1]));
                    }
                    AbstractVehicle top = v[names.length - 1];
                    double[] p = {top.getX(), top.getY(), top.getZ()};
                    for (int k = 0; k < 3; k++) {
                        box[2 * k] = Math.min(box[2 * k], p[k]);
                        box[2 * k + 1] = Math.max(box[2 * k + 1], p[k]);
                    }
                    for (AbstractVehicle each : v) {
                        worst[0] = Math.max(worst[0], each.getDeltaMovement().length());
                        worst[1] = Math.max(worst[1], each.physicsEngine.angularVelocity.length());
                    }
                    if (t % ROW == 0 || t < 200) {
                        row(name, t, String.format(Locale.ROOT, "depth %s heights %s tilt %s xz %s",
                                depths(v), heights(v, floor), tilts(v), places(v, base)));
                    }
                })
                .thenExecute(() -> {
                    boolean stacked = true;
                    for (int i = 0; i + 1 < names.length; i++) {
                        stacked &= v[i + 1].getY() > v[i].getY() + 0.5;
                    }
                    log(String.format(Locale.ROOT,
                            "%s %s SUMMARY stacked %b worst depth %s top drift x %.3f y %.3f z %.3f worst speed %.3f spin %.4f final heights %s tilt %s",
                            name, mode(), stacked, fmt(pairDepth), box[1] - box[0], box[3] - box[2], box[5] - box[4],
                            worst[0], worst[1], heights(v, floor), tilts(v)));
                    release(helper, laneIndex, lane, v);
                })
                .thenSucceed();
    }

    /** Lower along z; upper across it (yaw 90), underside 5 cm over the lower's roof, COM x = side face + offset. */
    private static void offset(GameTestHelper helper, int laneIndex, double offset) {
        BlockPos lane = lane(helper, laneIndex, true);
        double floor = lane.getY() + 1;
        Vec3 base = Vec3.atBottomCenterOf(lane.above());
        AbstractVehicle[] v = new AbstractVehicle[2];
        String name = String.format(Locale.ROOT, "offset_%+d", Math.round(offset * 100));
        // worst tilt, worst spin, worst depth, placed COM overhang
        double[] worst = new double[4];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(helper.getLevel().isPositionEntityTicking(lane), "lane not ticking"))
                .thenExecute(() -> v[0] = spawn(helper, "m1a2", base.add(0, 0.2, 0), 0))
                .thenIdle(60)
                .thenExecute(() -> {
                    OBB lower = v[0].getMainCubeOBB().obb();
                    double roof = OBB.toAABB(v[0].getOBBs()).maxY;
                    double side = support(v[0]);
                    v[1] = spawn(helper, "t90", base.add(0, 4, 0), 90);
                    v[1].updateOBBs();
                    Vec3 com = com(v[1]);
                    double under = OBB.toAABB(v[1].getOBBs()).minY;
                    v[1].setPos(v[1].getX() + side + offset - com.x, v[1].getY() + roof + 0.05 - under, v[1].getZ() + lower.centerZ() - com.z);
                    v[1].updateOBBs();
                    worst[3] = com(v[1]).x - side;
                })
                .thenExecuteFor(200, () -> {
                    long t = helper.getTick();
                    worst[0] = Math.max(worst[0], tilt(v[1]));
                    worst[1] = Math.max(worst[1], v[1].physicsEngine.angularVelocity.length());
                    worst[2] = Math.max(worst[2], depth(v[0], v[1]));
                    if (t % ROW == 0) {
                        row(name, t, String.format(Locale.ROOT, "upper tilt %.1f y %.3f com past side %.3f depth %.3f v %s",
                                tilt(v[1]), v[1].getY() - floor, com(v[1]).x - support(v[0]), depth(v[0], v[1]), v[1].getDeltaMovement()));
                    }
                })
                .thenExecute(() -> {
                    double roof = OBB.toAABB(v[0].getOBBs()).maxY;
                    boolean fell = OBB.toAABB(v[1].getOBBs()).minY < roof - 0.5;
                    log(String.format(Locale.ROOT,
                            "%s %s SUMMARY placed overhang %.3f tipped %b worst tilt %.1f spin %.4f depth %.3f final upper tilt %.1f lower tilt %.1f",
                            name, mode(), worst[3], fell || tilt(v[1]) > 30, worst[0], worst[1], worst[2], tilt(v[1]), tilt(v[0])));
                    release(helper, laneIndex, lane, v);
                })
                .thenSucceed();
    }

    /** Humvee broadside (yaw 90) against a 3-high wall; M1A2 from 12 blocks back drives {@link #DRIVE} ticks into it, then rests. */
    private static void sandwich(GameTestHelper helper, int laneIndex) {
        BlockPos lane = lane(helper, laneIndex, true);
        double floor = lane.getY() + 1;
        ServerLevel level = helper.getLevel();
        wall(level, lane, Blocks.STONE.defaultBlockState());
        AABB wall = new AABB(lane.getX() - FLOOR, floor, lane.getZ() + WALL_Z, lane.getX() + FLOOR + 1, floor + 3, lane.getZ() + WALL_Z + 1);
        AbstractVehicle[] v = new AbstractVehicle[2];
        ArmorStand driver = new ArmorStand(level, lane.getX(), floor, lane.getZ() - 12);
        driver.setNoGravity(true);
        String name = "sandwich";
        // humvee: worst wall depth, rise, vy, tilt; pair depth; tank rise, tank tilt
        double[] worst = new double[7];
        double[] start = new double[2];
        int[] driven = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(lane), "lane not ticking"))
                .thenExecute(() -> {
                    v[0] = spawn(helper, "humvee", Vec3.atBottomCenterOf(lane.above()).add(0, 0.2, 0), 90);
                    v[0].updateOBBs();
                    double far = OBB.toAABB(v[0].getOBBs()).maxZ;
                    v[0].setPos(v[0].getX(), v[0].getY(), v[0].getZ() + lane.getZ() + WALL_Z - GAP - far);
                    v[1] = spawn(helper, "m1a2", Vec3.atBottomCenterOf(lane.above()).add(0, 0.2, -12), 0);
                    level.addFreshEntity(driver);
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    start[0] = v[0].getY();
                    start[1] = v[1].getY();
                    v[1].setEnergy(v[1].energyInfo.capacityUnits());
                    v[1].controlUnit.setOperator(driver);
                    v[1].toggleEngine(true);
                    v[1].controlUnit.forward = true;
                })
                .thenExecuteFor(DRIVE + 100, () -> {
                    long t = helper.getTick();
                    if (++driven[0] == DRIVE) {
                        v[1].controlUnit.forward = false;
                    }
                    OBB hull = v[0].getMainCubeOBB().obb();
                    double into = hull.calculateMTV(wall).length();
                    worst[0] = Math.max(worst[0], into);
                    worst[1] = Math.max(worst[1], v[0].getY() - start[0]);
                    worst[2] = Math.max(worst[2], v[0].getDeltaMovement().y);
                    worst[3] = Math.max(worst[3], tilt(v[0]));
                    worst[4] = Math.max(worst[4], depth(v[0], v[1]));
                    worst[5] = Math.max(worst[5], v[1].getY() - start[1]);
                    worst[6] = Math.max(worst[6], tilt(v[1]));
                    if (t % ROW == 0) {
                        row(name, t, String.format(Locale.ROOT, "humvee wall %.3f rise %.3f tilt %.1f v %s | pair %.3f | tank rise %.3f tilt %.1f v %s",
                                into, v[0].getY() - start[0], tilt(v[0]), v[0].getDeltaMovement(), depth(v[0], v[1]),
                                v[1].getY() - start[1], tilt(v[1]), v[1].getDeltaMovement()));
                    }
                })
                .thenExecute(() -> {
                    log(String.format(Locale.ROOT,
                            "%s %s SUMMARY humvee worst wall %.3f rise %.3f vy %.3f tilt %.1f | pair %.3f | tank rise %.3f tilt %.1f | final wall %.3f pair %.3f tank z gap %.2f",
                            name, mode(), worst[0], worst[1], worst[2], worst[3], worst[4], worst[5], worst[6],
                            v[0].getMainCubeOBB().obb().calculateMTV(wall).length(), depth(v[0], v[1]),
                            OBB.toAABB(v[0].getOBBs()).minZ - OBB.toAABB(v[1].getOBBs()).maxZ));
                    driver.discard();
                    wall(level, lane, Blocks.AIR.defaultBlockState());
                    release(helper, laneIndex, lane, v);
                })
                .thenSucceed();
    }

    private static void row(String name, long t, String body) {
        log(String.format(Locale.ROOT, "%s %s t %d %s", name, mode(), t, body));
        log(String.format(Locale.ROOT, "%s %s t %d STATS %s", name, mode(), t, PhysicsStep.drainStats()));
    }

    private static void log(String line) {
        CombinedAssault.LOGGER.info("[stack] {}", line);
    }

    /** +x face of the lower hull's roof pieces (top within 0.1 of the highest). */
    private static double support(AbstractVehicle v) {
        double roof = OBB.toAABB(v.getOBBs()).maxY, side = -Double.MAX_VALUE;
        for (OBB piece : v.getOBBs()) {
            AABB bounds = piece.bounds();
            if (bounds.maxY > roof - 0.1) {
                side = Math.max(side, bounds.maxX);
            }
        }
        return side;
    }

    private static Vec3 com(AbstractVehicle v) {
        OBB hull = v.getMainCubeOBB().obb();
        Vec3 c = v.physicsEngine.center;
        double[] out = new double[3];
        hull.localToWorld(new Vector3f((float) c.x, (float) c.y, (float) c.z), hull.getAxes(), out);
        return new Vec3(out[0], out[1], out[2]);
    }

    /** Degrees between hull up and world up. */
    private static double tilt(AbstractVehicle v) {
        Vector3f up = v.getMainCubeOBB().obb().rotation().transform(new Vector3f(0, 1, 0));
        return Math.toDegrees(Math.acos(Math.min(1, up.y)));
    }

    /** Deepest piece-pair overlap. */
    private static double depth(AbstractVehicle a, AbstractVehicle b) {
        float deepest = 0;
        Vector3f axis = new Vector3f();
        for (OBB p : a.getOBBs()) {
            for (OBB q : b.getOBBs()) {
                deepest = Math.max(deepest, OBB.mtv(p, 0, 0, 0, q, axis));
            }
        }
        return deepest;
    }

    private static String depths(AbstractVehicle[] v) {
        double[] d = new double[v.length - 1];
        for (int i = 0; i + 1 < v.length; i++) {
            d[i] = depth(v[i], v[i + 1]);
        }
        return fmt(d);
    }

    private static String heights(AbstractVehicle[] v, double floor) {
        double[] d = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            d[i] = v[i].getY() - floor;
        }
        return fmt(d);
    }

    private static String places(AbstractVehicle[] v, Vec3 base) {
        StringBuilder out = new StringBuilder();
        for (AbstractVehicle each : v) {
            out.append(out.isEmpty() ? "" : "/").append(String.format(Locale.ROOT, "%.2f,%.2f", each.getX() - base.x, each.getZ() - base.z));
        }
        return out.toString();
    }

    private static String tilts(AbstractVehicle[] v) {
        StringBuilder out = new StringBuilder();
        for (AbstractVehicle each : v) {
            out.append(out.isEmpty() ? "" : "/").append(String.format(Locale.ROOT, "%.1f", tilt(each)));
        }
        return out.toString();
    }

    private static String fmt(double[] values) {
        StringBuilder out = new StringBuilder();
        for (double value : values) {
            out.append(out.isEmpty() ? "" : "/").append(String.format(Locale.ROOT, "%.3f", value));
        }
        return out.toString();
    }

    private static AbstractVehicle spawn(GameTestHelper helper, String name, Vec3 at, float yaw) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, name);
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        helper.assertTrue(data.isPresent(), id + " is not in the roster");
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(), at, 0, yaw);
        vehicle.setDisplayId(id);
        helper.assertTrue(helper.getLevel().addFreshEntity(vehicle), id + " could not be added");
        if (TRACE) {
            vehicle.setPhysicsTrace(new PhysicsTrace(vehicle, PhysicsTrace.MAX_TICKS));
        }
        return vehicle;
    }

    private static BlockPos lane(GameTestHelper helper, int index, boolean place) {
        BlockPos lane = helper.absolutePos(BlockPos.ZERO).offset(LANE_X, 0, index * LANE_PITCH);
        ServerLevel level = helper.getLevel();
        for (int cx = (lane.getX() - FLOOR) >> 4; cx <= (lane.getX() + FLOOR) >> 4; cx++) {
            for (int cz = (lane.getZ() - FLOOR - 4) >> 4; cz <= (lane.getZ() + FLOOR) >> 4; cz++) {
                ForcedChunks.set(level, cx, cz, place);
            }
        }
        BlockState state = place ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        for (int x = -FLOOR; x <= FLOOR; x++) {
            for (int z = -FLOOR - 4; z <= FLOOR; z++) {
                level.setBlockAndUpdate(lane.offset(x, 0, z), state);
            }
        }
        return lane;
    }

    private static void wall(ServerLevel level, BlockPos lane, BlockState state) {
        for (int x = -FLOOR; x <= FLOOR; x++) {
            for (int y = 1; y <= 3; y++) {
                level.setBlockAndUpdate(lane.offset(x, y, WALL_Z), state);
            }
        }
    }

    private static void release(GameTestHelper helper, int index, BlockPos lane, AbstractVehicle[] vehicles) {
        for (AbstractVehicle v : vehicles) {
            if (v == null) {
                continue;
            }
            PhysicsTrace trace = PhysicsTrace.of(v);
            if (trace != null) {
                try {
                    trace.dump(FMLPaths.GAMEDIR.get().resolve("stack-traces/" + current));
                    trace.dumpSweeps(FMLPaths.GAMEDIR.get().resolve("stack-traces/" + current));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            v.discard();
        }
        lane(helper, index, false);
    }
}
