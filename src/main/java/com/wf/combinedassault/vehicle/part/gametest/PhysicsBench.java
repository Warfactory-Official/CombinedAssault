package com.wf.combinedassault.vehicle.part.gametest;

import com.wf.wflib.physics.api.PhysicsSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Physics cost vs hull size; one {@code [perf]} row per (vehicle, drive/park, solver). Vehicles: {@code tools/internal/bench_pack.py}.
 * One test, cases in fixed order after a warmup pass: batch order is hash order => JIT warmup lands on a random case.
 * tick = server tick wall (ServerTickEvent Pre -> Post); cpu = sum of all threads' CPU (process CPU clock = 10 ms).
 * base* = same floor, vehicles removed. Shared empty row FORBIDDEN: absorbs the previous case's chunk unload (-50 us/vehicle).
 * Compiles against upstream mainline.
 */
@GameTestHolder(ClimbBench.NAMESPACE)
public class PhysicsBench {

    private static final int[] LENGTHS = {4, 8, 16, 32, 64};
    private static final int COPIES = 4;
    private static final int SETTLE = 30;
    private static final int RAMP = 20;
    private static final int WINDOW = 200;
    private static final int WARMUP_WINDOW = 20;
    private static final int LANE_Z = 12000;
    /** Ours only; absent upstream => that tree reports one solver. */
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private static boolean measuring;
    private static long tickStart;
    private static long tickTotal;
    private static int tickCount;

    /** push: copies in nose-to-nose pairs under throttle => vehicle contact every tick. */
    private record Case(String name, String vehicle, String mode, Boolean async) {

        boolean drive() {
            return !mode.equals("park");
        }
    }

    @GameTestGenerator
    public static List<TestFunction> physicsBench() {
        List<Case> cases = new ArrayList<>();
        List<Boolean> solvers = List.of(true, false);
        for (Boolean async : solvers) {
            for (String mode : new String[]{"park", "drive", "push"}) {
                for (int length : LENGTHS) {
                    for (String kind : new String[]{"sprung", "rigid"}) {
                        String vehicle = "box_" + length + "_" + kind;
                        String name = String.format(Locale.ROOT, "%s_%s_%s", vehicle, mode,
                                async == null ? "only" : async ? "async" : "sync");
                        if (name.contains(System.getProperty("combinedassault.bench.filter", ""))) {
                            cases.add(new Case(name, vehicle, mode, async));
                        }
                    }
                }
            }
        }
        int ticks = cases.size() * (2 * (SETTLE + 2 * RAMP + 5) + (WINDOW + WARMUP_WINDOW) * 3 / 2) + 200;
        return List.of(new TestFunction("perf", "perf", ClimbBench.NAMESPACE + ":empty", ticks, 0, true,
                helper -> run(helper, cases)));
    }

    private static void run(GameTestHelper helper, List<Case> cases) {
        Consumer<ServerTickEvent.Pre> pre = event -> tickStart = System.nanoTime();
        Consumer<ServerTickEvent.Post> post = event -> {
            if (measuring) {
                tickTotal += System.nanoTime() - tickStart;
                tickCount++;
            }
        };
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Pre.class, pre);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, post);
        GameTestSequence sequence = helper.startSequence();
        for (boolean warmup : new boolean[]{true, false}) {
            for (Case c : cases) {
                sequence = step(helper, sequence, c, warmup ? WARMUP_WINDOW : WINDOW, !warmup);
            }
        }
        sequence.thenExecute(() -> {
            NeoForge.EVENT_BUS.unregister(pre);
            NeoForge.EVENT_BUS.unregister(post);
        }).thenSucceed();
    }

    private static GameTestSequence step(GameTestHelper helper, GameTestSequence sequence, Case c, int window, boolean log) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO).offset(0, 0, LANE_Z);
        int length = Integer.parseInt(c.vehicle.split("_")[1]);
        int laneWidth = (int) Math.ceil(Math.max(2.0, length * 0.4)) + 6;
        boolean push = c.mode.equals("push");
        int floorLength = push ? 2 * length + 20 : length + (c.drive() ? 90 : 10);
        List<AbstractVehicle> vehicles = new ArrayList<>();
        List<ArmorStand> drivers = new ArrayList<>();
        long[] cpu = new long[1];
        long[] with = new long[2];
        double[] speed = new double[1];
        return sequence
                .thenExecute(() -> {
                    setFloor(level, origin, laneWidth, floorLength, true);
                    setAsync(c.async);
                    ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ClimbBench.NAMESPACE, c.vehicle);
                    Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
                    if (data.isEmpty()) {
                        helper.fail(id + " not loaded: tools/internal/bench_pack.py not staged");
                        return;
                    }
                    for (int i = 0; i < COPIES; i++) {
                        int lane = push ? i / 2 : i;
                        boolean facing = push && i % 2 == 1;
                        Vec3 at = new Vec3(origin.getX() + lane * laneWidth + laneWidth / 2.0, origin.getY() + 1.5,
                                origin.getZ() + length / 2.0 + 2 + (facing ? length + 0.5 : 0));
                        AbstractVehicle vehicle = data.get().construct(level, at, 0, facing ? 180 : 0);
                        vehicle.setDisplayId(id);
                        level.addFreshEntity(vehicle);
                        vehicles.add(vehicle);
                        ArmorStand driver = new ArmorStand(level, at.x, at.y, at.z);
                        driver.setNoGravity(true);
                        level.addFreshEntity(driver);
                        drivers.add(driver);
                    }
                })
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    for (int i = 0; i < vehicles.size(); i++) {
                        AbstractVehicle vehicle = vehicles.get(i);
                        vehicle.setEnergy(vehicle.energyInfo.energyCapacity);
                        vehicle.controlUnit.setOperator(drivers.get(i));
                        vehicle.toggleEngine(true);
                        vehicle.controlUnit.forward = c.drive();
                    }
                })
                .thenIdle(RAMP)
                .thenExecute(() -> {
                    tickTotal = 0;
                    tickCount = 0;
                    cpu[0] = allThreadsCpu();
                    measuring = true;
                })
                .thenIdle(window)
                .thenExecute(() -> {
                    measuring = false;
                    int ticks = Math.max(1, tickCount);
                    with[0] = tickTotal / ticks;
                    with[1] = (allThreadsCpu() - cpu[0]) / ticks;
                    speed[0] = vehicles.stream().mapToDouble(v -> v.getDeltaMovement().horizontalDistance()).average().orElse(0);
                    vehicles.forEach(AbstractVehicle::discard);
                    drivers.forEach(ArmorStand::discard);
                })
                .thenIdle(RAMP)
                .thenExecute(() -> {
                    tickTotal = 0;
                    tickCount = 0;
                    cpu[0] = allThreadsCpu();
                    measuring = true;
                })
                .thenIdle(window / 2)
                .thenExecute(() -> {
                    measuring = false;
                    int ticks = Math.max(1, tickCount);
                    if (log) {
                        CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                                "[perf] %s tick=%d cpu=%d baseTick=%d baseCpu=%d copies=%d speed=%.3f",
                                c.name, with[0], with[1], tickTotal / ticks, (allThreadsCpu() - cpu[0]) / ticks,
                                vehicles.size(), speed[0]));
                    }
                    PhysicsSettings.override(null);
                    setFloor(level, origin, laneWidth, floorLength, false);
                })
                .thenIdle(5);
    }

    private static long allThreadsCpu() {
        long total = 0;
        for (long id : THREADS.getAllThreadIds()) {
            long t = THREADS.getThreadCpuTime(id);
            if (t > 0) {
                total += t;
            }
        }
        return total;
    }

    private static void setAsync(Boolean async) {
        if (async != null) {
            PhysicsSettings.override(PhysicsSettings.current().withAsync(async));
        }
    }

    private static void setFloor(ServerLevel level, BlockPos origin, int laneWidth, int floorLength, boolean place) {
        BlockState state = place ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        int width = laneWidth * COPIES;
        for (int cx = origin.getX() >> 4; cx <= (origin.getX() + width) >> 4; cx++) {
            for (int cz = origin.getZ() >> 4; cz <= (origin.getZ() + floorLength) >> 4; cz++) {
                level.setChunkForced(cx, cz, place);
            }
        }
        for (int x = 0; x <= width; x++) {
            for (int z = 0; z <= floorLength; z++) {
                level.setBlock(origin.offset(x, 0, z), state, 2);
            }
        }
    }
}
