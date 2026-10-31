package com.wf.combinedassault.dynamics.gametest;

import com.wf.wflib.physics.api.PhysicsSettings;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.tow.HitchInfo;
import com.wf.combinedassault.tow.TowLinks;
import com.wf.combinedassault.tow.TowMode;
import com.wf.wflib.physics.api.Narrowphase;
import com.wf.wflib.testkit.api.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Golden step trace: fixed scene (hull on a terrace, pit pair, tow pair, hull on a floating deck, engine off: powered ship is not run-to-run deterministic), per-tick pose + velocity hash
 * folded every {@link #EVERY} ticks. Sync and async solves MUST both reproduce {@link #GOLDEN} bitwise.
 * Absolute coordinates fixed: solver floats are origin-relative but poses are doubles at world magnitude.
 * Changed physics => new trace; recompute by running with {@code GOLDEN} edited (failure prints the actual array).
 */
@PrefixGameTestTemplate(false)
public class StepDeterminismGameTest {

    private static final String TEMPLATE = "empty";
    private static final int TICKS = 300;
    private static final int EVERY = 25;
    private static final int BASE_X = 40000;
    private static final int BASE_Y = 100;
    private static final int BASE_Z = 40000;
    private static final long[] GOLDEN = {-9223310889549023502L, 4033727509310587804L, 8615834326200684684L, 5484446907347609020L, -5986019525792417098L, -1202708865635181969L, 2553674933765698766L, 4058103763343306761L, -1869243443012151684L, 3322762024795899542L, 6965062235524407704L, 1769599436483268859L};

    private static final ResourceLocation TANK = CombinedAssault.modLocation("m1a2");
    private static final ResourceLocation HUMVEE = CombinedAssault.modLocation("humvee");
    private static final ResourceLocation T90 = CombinedAssault.modLocation("t90");
    private static final ResourceLocation TRACTOR = CombinedAssault.modLocation("m983");
    private static final ResourceLocation TRAILER = CombinedAssault.modLocation("mim104");
    private static final ResourceLocation BOAT = CombinedAssault.modLocation("riverine");

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, batch = "stepdeterminism_sync", template = TEMPLATE, timeoutTicks = TICKS + 400)
    public static void theSyncStepReproducesTheGoldenTrace(GameTestHelper helper) {
        trace(helper, false);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, batch = "stepdeterminism_async", template = TEMPLATE, timeoutTicks = TICKS + 400)
    public static void theAsyncStepReproducesTheGoldenTrace(GameTestHelper helper) {
        trace(helper, true);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void theNativeNarrowphaseIsLoaded(GameTestHelper helper) {
        if (!Narrowphase.nativeLoaded()) {
            helper.fail("NarrowNative not loaded: natives missing from the jar, JNI symbols unresolved or no AVX2/baseline build");
            return;
        }
        helper.succeed();
    }

    private static void trace(GameTestHelper helper, boolean async) {
        ServerLevel level = helper.getLevel();
        PhysicsSettings.override(PhysicsSettings.current().withAsync(async));
        Scene scene = new Scene(level);
        long[] checkpoints = new long[TICKS / EVERY];
        long[] fold = {0xcbf29ce484222325L};
        GameTestSequence sequence = helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(new BlockPos(BASE_X, BASE_Y, BASE_Z))
                        && level.isPositionEntityTicking(new BlockPos(BASE_X + 200, BASE_Y, BASE_Z))
                        && level.isPositionEntityTicking(new BlockPos(BASE_X + 300, BASE_Y, BASE_Z)), "scene not ticking"))
                .thenExecute(() -> {
                    scene.build();
                    scene.spawn();
                });
        for (int t = 1; t <= TICKS; t++) {
            int tick = t;
            sequence = sequence.thenIdle(1).thenExecute(() -> {
                fold[0] = scene.hash(fold[0]);
                if (tick % EVERY == 0) {
                    checkpoints[tick / EVERY - 1] = fold[0];
                }
            });
        }
        sequence.thenExecute(() -> {
            scene.clear();
            PhysicsSettings.override(null);
            if (GOLDEN.length == 0) {
                helper.fail("no golden trace committed; actual " + literal(checkpoints));
            } else if (!Arrays.equals(GOLDEN, checkpoints)) {
                int first = 0;
                while (first < GOLDEN.length && GOLDEN[first] == checkpoints[first]) {
                    first++;
                }
                helper.fail((async ? "async" : "sync") + " trace diverges from golden at tick " + (first + 1) * EVERY
                        + "; actual " + literal(checkpoints));
            }
        }).thenSucceed();
    }

    private static String literal(long[] values) {
        StringBuilder out = new StringBuilder("{");
        for (long v : values) {
            out.append(String.format(Locale.ROOT, "%dL, ", v));
        }
        return out.append("}").toString();
    }

    private static final class Scene {

        final ServerLevel level;
        final List<AbstractVehicle> vehicles = new ArrayList<>();
        AbstractVehicle tractor;

        Scene(ServerLevel level) {
            this.level = level;
            force(true);
        }

        void build() {
            terrace();
            pit();
            floor(200, -60, 60, 160);
            water();
        }

        void force(boolean on) {
            for (int x = BASE_X - 64; x <= BASE_X + 320; x += 16) {
                for (int z = BASE_Z - 64; z <= BASE_Z + 192; z += 16) {
                    ForcedChunks.set(level, x >> 4, z >> 4, on);
                }
            }
        }

        BlockPos at(int x, int y, int z) {
            return new BlockPos(BASE_X + x, BASE_Y + y, BASE_Z + z);
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        level.setBlock(at(x, y, z), state, 2);
                    }
                }
            }
        }

        void floor(int x, int z, int width, int depth) {
            fill(x - width / 2, 0, z, x + width / 2, 0, z + depth, Blocks.STONE.defaultBlockState());
        }

        /** Three 1-block risers, 8 deep each. */
        void terrace() {
            floor(0, 0, 40, 60);
            for (int step = 1; step <= 3; step++) {
                fill(-20, 0, 20 + step * 8, 20, step, 60, Blocks.STONE.defaultBlockState());
            }
        }

        void pit() {
            floor(100, 0, 24, 24);
            fill(88, 1, 0, 88, 5, 24, Blocks.STONE.defaultBlockState());
            fill(112, 1, 0, 112, 5, 24, Blocks.STONE.defaultBlockState());
            fill(88, 1, 0, 112, 5, 0, Blocks.STONE.defaultBlockState());
            fill(88, 1, 24, 112, 5, 24, Blocks.STONE.defaultBlockState());
        }

        void water() {
            fill(280, -12, 0, 320, -1, 80, Blocks.WATER.defaultBlockState());
            fill(279, -13, -1, 321, -13, 81, Blocks.STONE.defaultBlockState());
        }

        AbstractVehicle add(ResourceLocation id, double x, double y, double z, float yaw) {
            AbstractVehicle vehicle = CommonAssetsManager.vehicleDataManager().getVehicleData(id).orElseThrow()
                    .construct(level, new Vec3(BASE_X + x, BASE_Y + y, BASE_Z + z), 0, yaw);
            vehicle.setDisplayId(id);
            if (!level.addFreshEntity(vehicle)) {
                throw new IllegalStateException(id + " not added");
            }
            vehicle.updateOBBs();
            vehicles.add(vehicle);
            return vehicle;
        }

        void spawn() {
            RandomSource random = RandomSource.create(20261029L);
            AbstractVehicle onTerrace = add(TANK, 0.5, 8, 25.5, 20 + random.nextFloat() * 40);
            onTerrace.setDeltaMovement(0.02, 0, 0.12);
            AbstractVehicle low = add(HUMVEE, 96.5, 3, 6.5, random.nextFloat() * 360);
            low.setDeltaMovement(0.1, 0, 0.05);
            AbstractVehicle high = add(T90, 100.5, 9, 10.5, random.nextFloat() * 360);
            high.setDeltaMovement(-0.05, 0, 0.1);

            tractor = add(TRACTOR, 200.5, 1.1, 0.5, 0);
            HitchInfo hitch = tractor.towing.hitch("fifth_wheel");
            AbstractVehicle trailer = add(TRAILER, 200.5, 1.1, 0.5, 0);
            Vec3 at = TowLinks.hitchPoint(tractor, hitch).subtract(trailer.towing.towable.offset);
            trailer.setPos(at.x, at.y, at.z);
            trailer.updateOBBs();
            if (!TowLinks.couple(tractor, trailer, hitch, TowMode.HITCH)) {
                throw new IllegalStateException("tow pair would not couple");
            }
            tractor.setDeltaMovement(0, 0, 0.25);

            add(BOAT, 300.5, 0, 6.5, 0);
            AbstractVehicle rider = add(HUMVEE, 300.5, 2, 6.5, 0);
            rider.setEnergy(rider.energyInfo.capacityUnits());
        }

        long hash(long h) {
            for (AbstractVehicle v : vehicles) {
                h = mix(h, Double.doubleToRawLongBits(v.getX()));
                h = mix(h, Double.doubleToRawLongBits(v.getY()));
                h = mix(h, Double.doubleToRawLongBits(v.getZ()));
                h = mix(h, Float.floatToRawIntBits(v.getXRot()));
                h = mix(h, Float.floatToRawIntBits(v.getYRot()));
                h = mix(h, Float.floatToRawIntBits(v.getZRot()));
                Vec3 d = v.getDeltaMovement();
                h = mix(h, Double.doubleToRawLongBits(d.x));
                h = mix(h, Double.doubleToRawLongBits(d.y));
                h = mix(h, Double.doubleToRawLongBits(d.z));
                h = mix(h, Float.floatToRawIntBits(v.physicsEngine.angularVelocity.x));
                h = mix(h, Float.floatToRawIntBits(v.physicsEngine.angularVelocity.y));
                h = mix(h, Float.floatToRawIntBits(v.physicsEngine.angularVelocity.z));
            }
            return h;
        }

        private static long mix(long h, long v) {
            h ^= v;
            h *= 0x100000001b3L;
            return h ^ (h >>> 29);
        }

        void clear() {
            for (AbstractVehicle vehicle : vehicles) {
                TowLinks.uncouple(vehicle);
                vehicle.discard();
            }
            fill(-30, 0, -62, 330, 6, 100, Blocks.AIR.defaultBlockState());
            fill(279, -13, -1, 321, -1, 81, Blocks.AIR.defaultBlockState());
            force(false);
        }

    }
}
