package com.wf.combinedassault.vehicle.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.entity.VehiclePart;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Locale;

/**
 * Aircraft killed in the air: wreck turns out of its kill attitude on the ground, comes to rest. Ground vehicle killed
 * moving (user: hulls slide when they explode): wreck stops like a dragged hull. Lane forces +-16 blocks.
 */
@PrefixGameTestTemplate(false)
public class WreckGameTest {

    private static final String TEMPLATE = "empty";
    private static final int DROP = 18;
    private static final int REST = 250;
    /** Blocks/tick (6 m/s). */
    private static final double KILL_SPEED = 0.3;
    /** Slide limit, blocks. Humvee measured 2.7 alone, 6.1 under full-suite load; free roll 27.6. */
    private static final double SLIDE = 10;
    /** Off-gear wrecks (side, roof, belly), Flan's: 1.5 x the mu 0.5 stop. */
    private static final double DRAG_SLIDE = 5.5;
    /** Posed (rolled, on the ridge) wreck falls and settles first: slide measured from rest. */
    private static final int POSE_SETTLE = 40;
    /** Ridge half length, blocks: lane floor +-16. */
    private static final int RIDGE = 15;

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aJetKilledInADiveDoesNotStayWedgedNoseDown(GameTestHelper helper) {
        wreck(helper, -8400, CombinedAssault.modLocation("a10"), 30, 0, new Vec3(0, -0.4, 0.3),
                v -> Math.abs(Mth.wrapDegrees(v.getXRot())) < 15,
                v -> "pitch " + v.getXRot());
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aHelicopterKilledBankedDoesNotLandFrozenAtItsBank(GameTestHelper helper) {
        wreck(helper, -8600, CombinedAssault.modLocation("ka52"), 0, 45, Vec3.ZERO,
                v -> Math.abs(Mth.wrapDegrees(v.getZRot()) - 45) > 10,
                v -> "roll " + v.getZRot());
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aTruckKilledMovingStopsOnItsBelly(GameTestHelper helper) {
        groundWreck(helper, -6000, CombinedAssault.modLocation("humvee"));
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aTankKilledMovingStopsOnItsBelly(GameTestHelper helper) {
        groundWreck(helper, -6200, CombinedAssault.modLocation("m1a2"));
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aTruckWreckOnItsSideStops(GameTestHelper helper) {
        groundWreck(helper, -6400, CombinedAssault.modLocation("humvee"), 90, false, DRAG_SLIDE);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aTruckWreckOnItsRoofStops(GameTestHelper helper) {
        groundWreck(helper, -6600, CombinedAssault.modLocation("humvee"), 180, false, DRAG_SLIDE);
    }

    /** High-centred on a 1-block ridge under the belly: gear intact, wheels hanging. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aTruckWreckSlidingOnItsBellyStops(GameTestHelper helper) {
        groundWreck(helper, -6800, CombinedAssault.modLocation("humvee"), 0, true, DRAG_SLIDE);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aTankWreckSlidingOnItsBellyStops(GameTestHelper helper) {
        groundWreck(helper, -7200, CombinedAssault.modLocation("m1a2"), 0, true, DRAG_SLIDE);
    }

    /** Parked gear down. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aJetWreckOnItsGearStops(GameTestHelper helper) {
        groundWreck(helper, -7800, CombinedAssault.modLocation("a10"), 0, false, DRAG_SLIDE);
    }

    /** Flan's WW2 jeep, `-PoptionalPacks=flans_ww2`; absent => vacuous. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aFlansJeepKilledMovingStops(GameTestHelper helper) {
        if (!List.of(System.getProperty("combinedassault.optionalPacks", "").split(",")).contains("flans_ww2")) {
            helper.succeed();
            return;
        }
        groundWreck(helper, -7600, ResourceLocation.fromNamespaceAndPath("flans_ww2", "jeep"), 0, false, DRAG_SLIDE);
    }

    private static boolean bvpAbsent(GameTestHelper helper) {
        if (List.of(System.getProperty("combinedassault.optionalPacks", "").split(",")).contains("bvp")) {
            return false;
        }
        helper.succeed();
        return true;
    }

    /** Bert's Vehicle Pack lanes, `-PoptionalPacks=bvp`; absent => vacuous. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aBvpTankKilledMovingStopsOnItsBelly(GameTestHelper helper) {
        if (!bvpAbsent(helper)) {
            groundWreck(helper, -9000, ResourceLocation.fromNamespaceAndPath("bvp", "t72b3"));
        }
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aBvpWheeledApcKilledMovingStops(GameTestHelper helper) {
        if (!bvpAbsent(helper)) {
            groundWreck(helper, -9200, ResourceLocation.fromNamespaceAndPath("bvp", "btr80a"));
        }
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aBvpJetKilledInADiveDoesNotStayWedgedNoseDown(GameTestHelper helper) {
        if (!bvpAbsent(helper)) {
            wreck(helper, -9400, ResourceLocation.fromNamespaceAndPath("bvp", "f_16c"), 30, 0, new Vec3(0, -0.4, 0.3),
                    v -> Math.abs(Mth.wrapDegrees(v.getXRot())) < 15,
                    v -> "pitch " + v.getXRot());
        }
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aBvpHelicopterKilledBankedDoesNotLandFrozenAtItsBank(GameTestHelper helper) {
        if (!bvpAbsent(helper)) {
            wreck(helper, -9600, ResourceLocation.fromNamespaceAndPath("bvp", "ah_64d"), 0, 45, Vec3.ZERO,
                    v -> Math.abs(Mth.wrapDegrees(v.getZRot()) - 45) > 10,
                    v -> "roll " + v.getZRot());
        }
    }

    private static void groundWreck(GameTestHelper helper, int lane, ResourceLocation id) {
        groundWreck(helper, lane, id, 0, false, SLIDE);
    }

    /**
     * Killed through {@code hurt} at {@link #KILL_SPEED} forwards; posed first ({@code roll} deg, raised clear of the
     * floor, or high-centred on a ridge) and settled; mu 0.5 drag => {@code v^2 / 2 mu g} ~ 3.7 blocks; frictionless => off the floor.
     */
    private static void groundWreck(GameTestHelper helper, int lane, ResourceLocation id, float roll, boolean ridge,
                                    double limit) {
        Vec3[] killedAt = new Vec3[1];
        TestLanes.run(helper, lane, 12, 16, id, (seq, vehicle) -> seq
                .thenExecute(() -> {
                    AbstractVehicle v = vehicle.get();
                    if (ridge) {
                        BlockPos under = v.blockPosition().below();
                        for (int z = -RIDGE; z <= RIDGE; z++) {
                            helper.getLevel().setBlockAndUpdate(under.offset(0, 1, z), Blocks.STONE.defaultBlockState());
                        }
                    }
                    double lift = roll == 0 ? (ridge ? 1.2 : 0) : v.getBbHeight() + 0.5;
                    v.setPos(v.getX(), v.getY() + lift, v.getZ() - 10);
                    v.setZRot(roll);
                    v.updateOBBs();
                })
                .thenIdle(roll == 0 && !ridge ? 0 : POSE_SETTLE)
                .thenExecute(() -> {
                    AbstractVehicle v = vehicle.get();
                    killedAt[0] = v.position();
                    v.setDeltaMovement(0, 0, KILL_SPEED);
                    for (VehicleSystems.Cell cell : VehicleSystems.cells(v)) {
                        cell.members().forEach(m -> m.setHealth(0));
                    }
                    v.hurt(helper.getLevel().damageSources().generic(), 1);
                    helper.assertTrue(v.isDestroyed(), id + " survived the kill");
                })
                .thenIdle(REST)
                .thenExecute(() -> {
                    AbstractVehicle v = vehicle.get();
                    Vec3 slid = v.position().subtract(killedAt[0]);
                    String state = String.format(Locale.ROOT, "%s (roll %.0f%s) slid %.2f (x %.2f z %.2f), speed %.3f, yaw %.1f, roll %.1f",
                            id, roll, ridge ? ", ridge" : "", slid.horizontalDistance(), slid.x, slid.z, v.getDeltaMovement().horizontalDistance(),
                            Mth.wrapDegrees(v.getYRot()), Mth.wrapDegrees(v.getZRot()));
                    CombinedAssault.LOGGER.info("[wreck] {}", state);
                    helper.assertTrue(v.getDeltaMovement().horizontalDistance() < 0.01, "wreck still sliding: " + state);
                    helper.assertTrue(slid.horizontalDistance() < limit, "wreck slid: " + state);
                }), at -> {
            helper.getLevel().getEntitiesOfClass(VehiclePart.class, new net.minecraft.world.phys.AABB(at).inflate(24))
                    .forEach(VehiclePart::discard);
            for (int z = -RIDGE; z <= RIDGE; z++) {
                helper.getLevel().setBlockAndUpdate(at.offset(0, 1, z), Blocks.AIR.defaultBlockState());
            }
        });
    }

    private static void wreck(GameTestHelper helper, int lane, ResourceLocation id, float pitch, float roll, Vec3 velocity,
                              java.util.function.Predicate<AbstractVehicle> turned,
                              java.util.function.Function<AbstractVehicle, String> attitude) {
        double[] floorY = new double[1];
        TestLanes.run(helper, lane, 24, 48, id, (seq, vehicle) -> seq
                .thenExecute(() -> {
                    AbstractVehicle v = vehicle.get();
                    floorY[0] = v.getY();
                    v.setPos(v.getX(), v.getY() + DROP, v.getZ() - 6);
                    v.setXRot(pitch);
                    v.setZRot(roll);
                    v.setDeltaMovement(velocity);
                    v.setDestroyed();
                })
                .thenWaitUntil(() -> helper.assertTrue(vehicle.get().getY() < floorY[0] + 3, id + " never landed"))
                .thenIdle(REST)
                .thenExecute(() -> {
                    AbstractVehicle v = vehicle.get();
                    double speed = v.getDeltaMovement().length();
                    String state = id + " " + attitude.apply(v) + ", speed " + speed + ", y " + (v.getY() - floorY[0]);
                    helper.assertTrue(turned.test(v), "wreck held its kill attitude: " + state);
                    helper.assertTrue(speed < 0.1, "wreck still moving: " + state);
                    helper.assertTrue(v.getY() > floorY[0] - 1, "wreck sank: " + state);
                }), at -> {});
    }
}
