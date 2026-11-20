package com.wf.combinedassault.aircraft.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.aircraft.FixedWingVehicle;
import com.wf.combinedassault.aircraft.WingLoss;
import com.wf.combinedassault.dynamics.part.LandingGearUnit;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.wflib.testkit.api.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

/** Vehicles into an obsidian post (off-centre hit) and into a dirt pad (dive, skid, drop); wreck fall, rollover, coast-down, stall, settle. */
@PrefixGameTestTemplate(false)
public class CrashGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation A10 = CombinedAssault.modLocation("a10");
    private static final ResourceLocation MQ9 = CombinedAssault.modLocation("mq9");
    private static final ResourceLocation HUMVEE = CombinedAssault.modLocation("humvee");
    private static final ResourceLocation KA52 = CombinedAssault.modLocation("ka52");
    private static final ResourceLocation M1A2 = CombinedAssault.modLocation("m1a2");
    /** Clear of WingLossGameTest (-2100 + 96 i). */
    private static final int LANE_X = -2700;
    private static final int LANE_PITCH = 160;
    private static final int PAD_HALF_X = 20, PAD_DEPTH = 10, PAD_FROM_Z = -8, PAD_TO_Z = 140;

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void aWingIntoAPostYawsTheAirframe(GameTestHelper helper) {
        post(helper, 0, MQ9, 6, 1.2);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void aHelicopterClippingAPostYaws(GameTestHelper helper) {
        post(helper, 1, KA52, 1, 0.8);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 300)
    public static void aSteepDiveDigsAPit(GameTestHelper helper) {
        dive(helper, 2, A10, 45, 2.5, 7, 30, 2, 5);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 300)
    public static void aShallowCrashGougesATrench(GameTestHelper helper) {
        dive(helper, 3, A10, 15, 3.0, 3, 15, 1, 12);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 300)
    public static void aFallingTankCraters(GameTestHelper helper) {
        dive(helper, 4, M1A2, 90, 1.5, 6, 100, 3, 5);
    }

    /** Save 2026-11-20: A-10 at yaw -123.5 held level 0.76 up by a humvee (-9.04, +4.98, yaw -192.3) under it. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void aPlaneProppedOnAVehicleTips(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(BlockPos.ZERO).offset(LANE_X + 5 * LANE_PITCH, 120, 0);
        force(level, at, true);
        pad(level, at, Blocks.DIRT.defaultBlockState());
        BlockPos spot = at.offset(0, 0, 40);
        AbstractVehicle humvee = spawn(helper, HUMVEE, spot, 0);
        if (humvee == null) {
            return;
        }
        humvee.setPos(spot.getX() + 0.5 - 9.04, spot.getY(), spot.getZ() + 0.5 + 4.98);
        humvee.setYRot(-192.3f);
        AbstractVehicle[] a10 = new AbstractVehicle[1];
        StringBuilder trace = new StringBuilder();
        helper.startSequence()
                .thenIdle(40)
                .thenExecute(() -> {
                    a10[0] = spawn(helper, A10, spot.above(1), 0);
                    a10[0].setYRot(-123.5f);
                })
                .thenExecuteFor(200, () -> {
                    if (a10[0].tickCount % 10 == 0) {
                        trace.append(String.format(Locale.ROOT, " [y %.2f pitch %.1f roll %.1f ground %s]", a10[0].getY() - spot.getY(),
                                a10[0].getXRot(), a10[0].getZRot(), a10[0].onGround()));
                    }
                })
                .thenExecute(() -> {
                    double tilt = Math.max(Math.abs(a10[0].getXRot()), Math.abs(a10[0].getZRot()));
                    String state = String.format(Locale.ROOT, "tilt %.1f, humvee %.2f %.2f %.2f;%s", tilt, humvee.getX() - a10[0].getX(),
                            humvee.getY() - spot.getY(), humvee.getZ() - a10[0].getZ(), trace);
                    CombinedAssault.LOGGER.info("[crash] propped {}", state);
                    pad(level, at, Blocks.AIR.defaultBlockState());
                    double height = a10[0].getY() - spot.getY();
                    a10[0].discard();
                    humvee.discard();
                    force(level, at, false);
                    if (height > 0.3 || tilt < 2) {
                        helper.fail(state);
                    }
                })
                .thenSucceed();
    }

    /** Before: fell level, left the ground 1.2 b/t up, apex 21. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void aFallingWreckStaysDown(GameTestHelper helper) {
        boolean[] flew = new boolean[1], landed = new boolean[1];
        double[] up = {0}, pitch = {0};
        loose(helper, -1, A10, 60, 0, 200, v -> {
            v.setDestroyed();
            v.setDeltaMovement(0, 0, 2);
        }, (v, y) -> {
            flew[0] |= !v.onGround();
            landed[0] |= flew[0] && v.onGround();
            if (landed[0]) {
                up[0] = Math.max(up[0], v.getDeltaMovement().y);
            } else {
                pitch[0] = Math.max(pitch[0], v.getXRot());
            }
        }, v -> !landed[0] || up[0] > 0.3 || pitch[0] < 20
                ? String.format(Locale.ROOT, "landed %s, up %.2f b/t, nose down %.1f", landed[0], up[0], pitch[0]) : null);
    }

    /** Before: 73 -> 90 deg in 36 t at a constant rate. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void aTankPastItsTippingAngleFallsOver(GameTestHelper helper) {
        int[] ticks = {0};
        boolean[] over = new boolean[1];
        float[] back = {180};
        loose(helper, -2, M1A2, 3, 0, 120, v -> {
            v.setDeltaMovement(0, 0, 0);
            v.setZRot(70);
        }, (v, y) -> {
            over[0] |= Math.abs(v.getZRot()) >= 88;
            if (over[0]) {
                back[0] = Math.min(back[0], Math.abs(v.getZRot()));
            } else if (v.onGround()) {
                ticks[0]++;
            }
        }, v -> !over[0] || ticks[0] > 30 || back[0] < 85
                ? String.format(Locale.ROOT, "over %s after %d t, rocked back to %.1f", over[0], ticks[0], back[0]) : null);
    }

    /** Before: mu 0.2, 86+ blocks. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void anUnpoweredTankSlidingBroadsideStops(GameTestHelper helper) {
        double[] z = new double[1];
        loose(helper, -3, M1A2, 0, 90, 120, v -> v.setDeltaMovement(0, 0, 1), (v, y) -> z[0] = v.getZ(),
                v -> v.getDeltaMovement().horizontalDistance() > 0.01 ? "still sliding " + v.getDeltaMovement() : null);
    }

    /** Before: 1.0 -> 0.963 b/t in 100 t. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void aCoastingTruckLosesSpeed(GameTestHelper helper) {
        loose(helper, -4, HUMVEE, 0, 0, 100, v -> v.setDeltaMovement(0, 0, 1), (v, y) -> {
        }, v -> v.getDeltaMovement().horizontalDistance() > 0.94 ? "speed " + v.getDeltaMovement().horizontalDistance() : null);
    }

    /** Before: fell level at any sink rate (pitch 0), yawed 28 deg in a 10-block drop. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void aStalledPlaneDropsItsNose(GameTestHelper helper) {
        double[] pitch = {0};
        loose(helper, -5, A10, 60, 0, 60, v -> v.setDeltaMovement(0, 0, 0), (v, y) -> pitch[0] = Math.max(pitch[0], v.getXRot()),
                v -> pitch[0] < 10 ? String.format(Locale.ROOT, "nose down %.1f", pitch[0]) : null);
    }

    /** Before (2.2 Hz, zeta 0.12): heaved and rocked ~80 t after a drop. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void aDroppedTruckSettles(GameTestHelper helper) {
        int[] ticks = {0}, moving = {0};
        boolean[] flew = new boolean[1], landed = new boolean[1];
        loose(helper, -6, HUMVEE, 3, 0, 120, v -> v.setDeltaMovement(0, 0, 0), (v, y) -> {
            flew[0] |= !v.onGround();
            landed[0] |= flew[0] && v.onGround();
            if (landed[0] && (Math.abs(v.getDeltaMovement().y) > 0.01 || v.physicsEngine.angularVelocity.length() > 0.005)) {
                moving[0] = ticks[0];
            }
            ticks[0] += landed[0] ? 1 : 0;
        }, v -> !landed[0] || moving[0] > 40 || Math.abs(v.getZRot()) > 1 || Math.abs(v.getXRot()) > 1
                ? String.format(Locale.ROOT, "landed %s, still moving at tick %d, roll %.1f pitch %.1f", landed[0], moving[0], v.getZRot(),
                v.getXRot()) : null);
    }

    /** Unmanned vehicle settled on the pad, then lifted {@code height} and released; {@code verdict} null = pass. */
    private static void loose(GameTestHelper helper, int lane, ResourceLocation id, int height, float yaw, int ticks,
                              java.util.function.Consumer<AbstractVehicle> start,
                              java.util.function.BiConsumer<AbstractVehicle, Double> watch,
                              java.util.function.Function<AbstractVehicle, String> verdict) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(BlockPos.ZERO).offset(LANE_X + lane * LANE_PITCH, 120, 0);
        force(level, at, true);
        pad(level, at, Blocks.DIRT.defaultBlockState());
        AbstractVehicle v = spawn(helper, id, at, 0);
        if (v == null) {
            return;
        }
        v.setYRot(yaw);
        StringBuilder trace = new StringBuilder();
        helper.startSequence()
                .thenIdle(40)
                .thenExecute(() -> {
                    v.setPos(at.getX() + 0.5, v.getY() + height, at.getZ() + 0.5);
                    start.accept(v);
                })
                .thenExecuteFor(ticks, () -> {
                    watch.accept(v, v.getY() - at.getY());
                    if (v.tickCount % 4 == 0) {
                        trace.append(String.format(Locale.ROOT, " [%.1f %.1f pitch %.0f roll %.0f v %.2f %.2f%s]", v.getY() - at.getY(),
                                v.getZ() - at.getZ(), v.getXRot(), v.getZRot(), v.getDeltaMovement().y,
                                v.getDeltaMovement().horizontalDistance(), v.onGround() ? " G" : ""));
                    }
                })
                .thenExecute(() -> {
                    String failure = verdict.apply(v);
                    CombinedAssault.LOGGER.info("[crash] loose {} lane {}: {};{}", id.getPath(), lane, failure, trace);
                    pad(level, at, Blocks.AIR.defaultBlockState());
                    v.discard();
                    force(level, at, false);
                    if (failure != null) {
                        helper.fail(failure);
                    }
                })
                .thenSucceed();
    }

    private static void post(GameTestHelper helper, int lane, ResourceLocation id, int side, double speed) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(BlockPos.ZERO).offset(LANE_X + lane * LANE_PITCH, 120, 0);
        force(level, at, true);
        AbstractVehicle a10 = spawn(helper, id, at, 0);
        if (a10 == null) {
            force(level, at, false);
            return;
        }
        BlockPos foot = at.offset(side, -5, 10);
        double[] yaw = new double[1];
        float[] spin = new float[1];
        StringBuilder trace = new StringBuilder();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(a10.hits() != null, "a10 hit model not built"))
                .thenIdle(3)
                .thenExecute(() -> {
                    for (int y = 0; y < 11; y++) {
                        level.setBlock(foot.above(y), Blocks.OBSIDIAN.defaultBlockState(), 18);
                    }
                    a10.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                    cruise(a10, speed);
                    yaw[0] = a10.getYRot();
                })
                .thenExecuteFor(40, () -> {
                    spin[0] = Math.max(spin[0], Math.abs(a10.physicsEngine.angularVelocity.y));
                    trace.append(String.format(Locale.ROOT, " [%.1f %.1f %.1f yaw %.1f roll %.1f |v| %.2f w %.3f]",
                            a10.getX() - at.getX(), a10.getY() - at.getY(), a10.getZ() - at.getZ(), a10.getYRot() - yaw[0],
                            a10.getZRot(), a10.getDeltaMovement().length(), a10.physicsEngine.angularVelocity.length()));
                })
                .thenExecute(() -> {
                    float[] wings = WingLoss.intact(a10.hullComponents());
                    String state = String.format(Locale.ROOT, "%s speed %.1f: yaw %.1f, wings %.0f/%.0f, destroyed %s;%s", id.getPath(), speed,
                            a10.getYRot() - yaw[0], wings[0], wings[1], a10.isDestroyed(), trace);
                    CombinedAssault.LOGGER.info("[crash] post {}", state);
                    for (int y = 0; y < 11; y++) {
                        level.setBlock(foot.above(y), Blocks.AIR.defaultBlockState(), 18);
                    }
                    double speedAfter = a10.getDeltaMovement().length();
                    boolean destroyed = a10.isDestroyed();
                    a10.discard();
                    force(level, at, false);
                    // Wing pools: the wing shears, the airframe flies on. None: the hull pivots on the post.
                    if (spin[0] < 0.01 || id == MQ9 && (wings[0] != 0 || wings[1] != 1 || destroyed || speedAfter < 0.6 * speed)) {
                        helper.fail(state);
                    }
                })
                .thenSucceed();
    }

    private static void dive(GameTestHelper helper, int lane, ResourceLocation id, float pitch, double speed, int height,
                             int minCells, int minDepth, int minLength) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(BlockPos.ZERO).offset(LANE_X + lane * LANE_PITCH, 120, 0);
        force(level, at, true);
        AbstractVehicle a10 = spawn(helper, id, at.above(height), id == M1A2 ? 0 : pitch);
        if (a10 == null) {
            force(level, at, false);
            return;
        }
        StringBuilder trace = new StringBuilder();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(a10.hits() != null, "a10 hit model not built"))
                .thenIdle(3)
                .thenExecute(() -> {
                    pad(level, at, Blocks.DIRT.defaultBlockState());
                    a10.setPos(at.getX() + 0.5, at.getY() + height, at.getZ() + 0.5);
                    if (id == M1A2) {
                        a10.setDeltaMovement(0, -speed, 0);
                    } else {
                        a10.setXRot(pitch);
                        cruise(a10, speed);
                    }
                })
                .thenExecuteFor(100, () -> {
                    if (a10.tickCount % 4 == 0) {
                        trace.append(String.format(Locale.ROOT, " [%.1f %.1f %.1f pitch %.0f roll %.0f |v| %.2f%s]",
                                a10.getX() - at.getX(), a10.getY() - at.getY(), a10.getZ() - at.getZ(), a10.getXRot(),
                                a10.getZRot(), a10.getDeltaMovement().length(), a10.isDestroyed() ? " X" : ""));
                    }
                })
                .thenExecute(() -> {
                    int[] hole = hole(level, at);
                    String state = String.format(Locale.ROOT,
                            "%s pitch %.0f speed %.1f: %d cells gone, x %d..%d, depth %d, z %d..%d, destroyed %s, removed %s;%s",
                            id.getPath(), pitch, speed, hole[0], hole[1], hole[2], hole[3], hole[4], hole[5], a10.isDestroyed(),
                            a10.isRemoved(), trace);
                    CombinedAssault.LOGGER.info("[crash] dive {}", state);
                    pad(level, at, Blocks.AIR.defaultBlockState());
                    a10.discard();
                    force(level, at, false);
                    if (hole[0] < minCells || hole[3] < minDepth || hole[5] - hole[4] + 1 < minLength) {
                        helper.fail(state);
                    }
                })
                .thenSucceed();
    }

    private static void pad(ServerLevel level, BlockPos at, BlockState state) {
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        for (int x = -PAD_HALF_X; x <= PAD_HALF_X; x++) {
            for (int y = -PAD_DEPTH; y < 0; y++) {
                for (int z = PAD_FROM_Z; z <= PAD_TO_Z; z++) {
                    level.setBlock(cell.setWithOffset(at, x, y, z), state, 18);
                }
            }
        }
    }

    /** Air cells in the pad: count, x min/max, deepest, z min/max. */
    private static int[] hole(ServerLevel level, BlockPos at) {
        int[] hole = {0, Integer.MAX_VALUE, Integer.MIN_VALUE, 0, Integer.MAX_VALUE, Integer.MIN_VALUE};
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        for (int x = -PAD_HALF_X; x <= PAD_HALF_X; x++) {
            for (int y = -PAD_DEPTH; y < 0; y++) {
                for (int z = PAD_FROM_Z; z <= PAD_TO_Z; z++) {
                    if (level.getBlockState(cell.setWithOffset(at, x, y, z)).isAir()) {
                        hole[0]++;
                        hole[1] = Math.min(hole[1], x);
                        hole[2] = Math.max(hole[2], x);
                        hole[3] = Math.max(hole[3], -y);
                        hole[4] = Math.min(hole[4], z);
                        hole[5] = Math.max(hole[5], z);
                    }
                }
            }
        }
        return hole;
    }

    private static void cruise(AbstractVehicle v, double speed) {
        v.setEnergy(v.energyInfo.capacityUnits());
        v.setPower(100);
        if (v instanceof FixedWingVehicle plane) {
            plane.throttleLevel = 100;
            if (plane.landingGear != null) {
                LandingGearUnit.setAll(plane, true);
            }
        }
        v.setDeltaMovement(v.getLookAngle().scale(speed));
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, ResourceLocation id, BlockPos at, float pitch) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            helper.fail(id + " is not in the roster");
            return null;
        }
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), pitch, 0);
        vehicle.setDisplayId(id);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail(id + " could not be added to the world");
            return null;
        }
        return vehicle;
    }

    private static void force(ServerLevel level, BlockPos at, boolean forced) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -2; dz <= Mth.ceil(PAD_TO_Z / 16.0) + 2; dz++) {
                ForcedChunks.set(level, (at.getX() >> 4) + dx, (at.getZ() >> 4) + dz, forced);
            }
        }
    }
}
