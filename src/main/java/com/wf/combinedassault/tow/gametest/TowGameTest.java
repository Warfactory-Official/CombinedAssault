package com.wf.combinedassault.tow.gametest;

import com.wf.wflib.util.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.tow.HitchInfo;
import com.wf.combinedassault.tow.TowLink;
import com.wf.combinedassault.tow.TowLinks;
import com.wf.combinedassault.tow.TowMode;
import com.wf.combinedassault.tow.TowSolver;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Shipped M983/MIM-104, not invented points: most coupling bugs are pack JSON (signed kingpin offset, axle offset).
 * Couplings are joint rows in the rigid step => driven trains on stone lanes far from the arena grid.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class TowGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation TRACTOR = CombinedAssault.modLocation("m983");
    private static final ResourceLocation TRAILER = CombinedAssault.modLocation("mim104");
    private static final int LANE_X = 3000;
    private static final int LANE_PITCH = 100;
    /** Arena origins in a batch spread >100 in x => lanes overlap in plan; a level per lane keeps them apart. */
    private static final int LANE_RISE = 16;
    private static final int HALF_WIDTH = 40;
    private static final int BACK = 30, AHEAD = 70;
    /** Soft joint: open by load / stiffness, not by error. */
    private static final double HELD = 0.15;

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aTrailerFollowsTheTractorRoundATurn(GameTestHelper helper) {
        Lane lane = lane(helper, 0);
        Train train = couple(helper, lane, TowMode.HITCH);
        if (train == null) {
            return;
        }
        double[] worst = new double[1];
        double[] articulation = new double[1];
        Vec3[] start = new Vec3[1];
        drive(helper, lane, train.tractor, 40, 160, tractor -> {
            if (start[0] == null) {
                start[0] = train.trailer.position();
            }
            tractor.controlUnit.forward = true;
            tractor.controlUnit.right = true;
            worst[0] = Math.max(worst[0], train.gap());
            articulation[0] = Math.max(articulation[0], Math.abs(articulation(train)));
        }, () -> {
            double moved = train.trailer.position().distanceTo(start[0]);
            String report = String.format(Locale.ROOT, "worst gap %.3f, peak articulation %.1f, trailer moved %.1f",
                    worst[0], articulation[0], moved);
            boolean towed = TowLinks.isTowed(train.trailer);
            release(lane, train.tractor, train.trailer);
            if (!towed || worst[0] > HELD || articulation[0] < 5 || moved < 5) {
                helper.fail(report);
            }
        });
    }

    /** Reversing on full lock is how a real rig jackknifes. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aJackknifeIsHeldAtTheStops(GameTestHelper helper) {
        Lane lane = lane(helper, 1);
        Train train = couple(helper, lane, TowMode.HITCH);
        if (train == null) {
            return;
        }
        double[] worst = new double[1];
        drive(helper, lane, train.tractor, 40, 200, tractor -> {
            tractor.controlUnit.backward = true;
            tractor.controlUnit.right = true;
            worst[0] = Math.max(worst[0], Math.abs(articulation(train)));
        }, () -> {
            release(lane, train.tractor, train.trailer);
            if (worst[0] > TowSolver.JACKKNIFE_LIMIT + 2) {
                helper.fail(String.format(Locale.ROOT, "the trailer folded to %.1f degrees, past the fifth wheel's stops", worst[0]));
            }
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aCableOnlyPullsWhenItIsTight(GameTestHelper helper) {
        Lane lane = lane(helper, 2);
        AbstractVehicle tractor = spawn(helper, lane, TRACTOR, 0);
        AbstractVehicle towed = spawn(helper, lane, TRAILER, -12);
        HitchInfo pintle = tractor.towing.hitch("pintle");
        if (pintle == null || !TowLinks.couple(tractor, towed, pintle, TowMode.CABLE)) {
            helper.fail("the tractor's pintle refused the cable");
            return;
        }
        Vec3[] parked = new Vec3[1];
        double[] slackMove = new double[1];
        double[] worst = new double[1];
        drive(helper, lane, tractor, 40, 200, t -> {
            if (parked[0] == null) {
                parked[0] = towed.position();
            }
            double gap = TowLinks.hitchPoint(tractor, pintle).distanceTo(TowLinks.couplingPoint(towed));
            if (gap < pintle.cableLength - 0.5) {
                Vec3 moved = towed.position().subtract(parked[0]);
                slackMove[0] = Math.max(slackMove[0], Math.hypot(moved.x, moved.z));
            }
            worst[0] = Math.max(worst[0], gap);
            tractor.controlUnit.forward = true;
        }, () -> {
            double moved = towed.position().distanceTo(parked[0]);
            String report = String.format(Locale.ROOT, "moved %.3f while slack, %.2f in all, cable stretched to %.2f of %.1f",
                    slackMove[0], moved, worst[0], pintle.cableLength);
            release(lane, tractor, towed);
            if (slackMove[0] > 0.05 || moved < 3 || worst[0] > pintle.cableLength + 0.5) {
                helper.fail(report);
            }
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aCouplingPartsWhenItIsTornOff(GameTestHelper helper) {
        Lane lane = lane(helper, 3);
        Train train = couple(helper, lane, TowMode.HITCH);
        if (train == null) {
            return;
        }
        train.tractor.setPos(train.tractor.getX(), train.tractor.getY(), train.tractor.getZ() + 40);
        TowSolver.tick(helper.getLevel());
        boolean towed = TowLinks.isTowed(train.trailer);
        release(lane, train.tractor, train.trailer);
        if (towed) {
            helper.fail("a coupling dragged forty blocks should have parted");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aChainHoldsTogether(GameTestHelper helper) {
        Lane lane = lane(helper, 4);
        AbstractVehicle lead = spawn(helper, lane, TRACTOR, 0);
        AbstractVehicle second = spawn(helper, lane, TRACTOR, -16);
        HitchInfo pintle = lead.towing.hitch("pintle");
        HitchInfo fifthWheel = second.towing.hitch("fifth_wheel");
        if (pintle == null || fifthWheel == null) {
            helper.fail("the tractor is missing a hitch");
            return;
        }
        AbstractVehicle trailer = spawn(helper, lane, TRAILER, 0);
        seat(trailer, TowLinks.hitchPoint(second, fifthWheel));
        if (!TowLinks.couple(second, trailer, fifthWheel, TowMode.HITCH) || !TowLinks.couple(lead, second, pintle, TowMode.CABLE)) {
            helper.fail("the chain would not couple");
            return;
        }
        if (TowLinks.train(lead).size() != 3) {
            helper.fail("the train should be three vehicles, it is " + TowLinks.train(lead).size());
            return;
        }
        // Reversed coupling would loop the chain; must stay refused.
        if (TowLinks.couple(trailer, lead, fifthWheel, TowMode.HITCH)) {
            helper.fail("a train was allowed to close on itself");
            return;
        }
        double[] worst = new double[2];
        Vec3[] start = new Vec3[1];
        drive(helper, lane, lead, 40, 200, tractor -> {
            if (start[0] == null) {
                start[0] = trailer.position();
            }
            tractor.controlUnit.forward = true;
            worst[0] = Math.max(worst[0], TowLinks.hitchPoint(second, fifthWheel).distanceTo(TowLinks.couplingPoint(trailer)));
            worst[1] = Math.max(worst[1], TowLinks.hitchPoint(lead, pintle).distanceTo(TowLinks.couplingPoint(second)));
        }, () -> {
            double moved = trailer.position().distanceTo(start[0]);
            String report = String.format(Locale.ROOT, "fifth wheel open %.3f, cable %.2f of %.1f, trailer moved %.1f",
                    worst[0], worst[1], pintle.cableLength, moved);
            boolean whole = TowLinks.isTowed(trailer) && TowLinks.isTowed(second);
            release(lane, lead, second, trailer);
            if (!whole || worst[0] > HELD || worst[1] > pintle.cableLength + 0.5 || moved < 3) {
                helper.fail(report);
            }
        });
    }

    /** Same throttle, same lane: the one with the trailer is slower. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aLoadCostsTheTractorAcceleration(GameTestHelper helper) {
        Lane lane = lane(helper, 5);
        Train train = couple(helper, lane, TowMode.HITCH);
        AbstractVehicle solo = spawn(helper, lane, TRACTOR, 0, 20);
        if (train == null) {
            return;
        }
        ArmorStand other = driver(helper.getLevel(), solo);
        drive(helper, lane, train.tractor, 40, 60, tractor -> {
            tractor.controlUnit.forward = true;
            solo.controlUnit.forward = true;
        }, () -> {
            double loaded = horizontal(train.tractor), free = horizontal(solo);
            other.discard();
            release(lane, train.tractor, train.trailer, solo);
            if (!(loaded < free * 0.9)) {
                helper.fail(String.format(Locale.ROOT, "with the trailer %.3f b/t, without %.3f b/t", loaded, free));
            }
        });
    }

    /** Tow ring's whole-block noise (heights -1..1) from z 10 on: the train keeps going (~0.1 b/t; M983 solo ~0.2). */
    @GameTest(template = TEMPLATE, timeoutTicks = 500)
    public static void aTrainCrossesRoughGround(GameTestHelper helper) {
        Lane lane = lane(helper, 10);
        for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
            for (int z = 10; z <= AHEAD; z++) {
                int hash = x * 374761393 + z * 668265263;
                hash = (hash ^ (hash >>> 13)) * 1274126177;
                int height = Math.floorMod(hash ^ (hash >>> 16), 3) - 1;
                if (height < 0) {
                    lane.level.setBlockAndUpdate(lane.at.offset(x, 0, z), Blocks.AIR.defaultBlockState());
                } else if (height > 0) {
                    lane.level.setBlockAndUpdate(lane.at.offset(x, 1, z), Blocks.STONE.defaultBlockState());
                }
            }
        }
        Train train = couple(helper, lane, TowMode.HITCH);
        if (train == null) {
            return;
        }
        double[] start = new double[2];
        int[] tick = new int[1];
        drive(helper, lane, train.tractor, 40, 360, tractor -> {
            if (tick[0]++ == 0) {
                start[0] = train.trailer.getZ();
            } else if (tick[0] == 300) {
                start[1] = train.trailer.getZ();
            }
            tractor.controlUnit.forward = tractor.getZ() < lane.at.getZ() + AHEAD - 12;
        }, () -> {
            double moved = train.trailer.getZ() - start[0], late = train.trailer.getZ() - start[1];
            boolean towed = TowLinks.isTowed(train.trailer);
            String where = String.format(Locale.ROOT, "tractor at z %.1f, %.3f b/t, traction %.2f", train.tractor.getZ() - lane.at.getZ(),
                    horizontal(train.tractor), train.tractor.physicsEngine.tractionShare());
            for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
                for (int z = 10; z <= AHEAD; z++) {
                    lane.level.setBlockAndUpdate(lane.at.offset(x, 1, z), Blocks.AIR.defaultBlockState());
                }
            }
            release(lane, train.tractor, train.trailer);
            if (!towed || moved < 20 || late < 2) {
                helper.fail(String.format(Locale.ROOT, "trailer moved %.1f over rough ground, %.1f in the last 60 ticks, %s; %s",
                        moved, late, towed ? "coupled" : "parted", where));
            }
        });
    }

    /** Ball joint: the kingpin rides the fifth wheel up; the trailer pitches about its axle. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aTrailerPitchesUpToAHitchAboveIt(GameTestHelper helper) {
        Lane lane = lane(helper, 6);
        for (int x = -6; x <= 6; x++) {
            for (int z = -8; z <= 8; z++) {
                helper.getLevel().setBlockAndUpdate(lane.at.offset(x, 1, z), Blocks.STONE.defaultBlockState());
            }
        }
        Train train = couple(helper, lane, TowMode.HITCH);
        if (train == null) {
            return;
        }
        helper.startSequence()
                .thenIdle(80)
                .thenExecute(() -> {
                    float pitch = train.trailer.getXRot();
                    double gap = train.gap();
                    release(lane, train.tractor, train.trailer);
                    for (int x = -6; x <= 6; x++) {
                        for (int z = -8; z <= 8; z++) {
                            helper.getLevel().setBlockAndUpdate(lane.at.offset(x, 1, z), Blocks.AIR.defaultBlockState());
                        }
                    }
                    // Nose-up = negative pitch.
                    if (pitch > -2 || gap > HELD) {
                        helper.fail(String.format(Locale.ROOT, "trailer pitch %.2f, coupling open %.3f", pitch, gap));
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aHitchRefusesWhatItWasNotBuiltFor(GameTestHelper helper) {
        Lane lane = lane(helper, 7);
        AbstractVehicle tractor = spawn(helper, lane, TRACTOR, 0);
        AbstractVehicle trailer = spawn(helper, lane, TRAILER, -9);
        AbstractVehicle car = spawn(helper, lane, CombinedAssault.modLocation("centauro"), 6);
        if (tractor == null || trailer == null || car == null) {
            return;
        }
        HitchInfo fifthWheel = tractor.towing.hitch("fifth_wheel");
        HitchInfo pintle = tractor.towing.hitch("pintle");
        if (fifthWheel == null || pintle == null) {
            helper.fail("the tractor is missing a hitch");
            return;
        }
        // centauro declares no towing at all => NOT_TOWABLE.
        if (TowLinks.check(tractor, car, fifthWheel, TowMode.HITCH, false) != TowLinks.Refusal.NOT_TOWABLE) {
            helper.fail("a vehicle that declares no towing should refuse as NOT_TOWABLE");
            return;
        }
        // Trailer spawned beyond hitch reach => OUT_OF_REACH.
        if (TowLinks.check(tractor, trailer, fifthWheel, TowMode.HITCH, true) != TowLinks.Refusal.OUT_OF_REACH) {
            helper.fail("a trailer four blocks from the fifth wheel should be out of reach");
            return;
        }
        // Pintle = cable-only hitch => rigid request is WRONG_MODE.
        if (TowLinks.check(tractor, trailer, pintle, TowMode.HITCH, false) != TowLinks.Refusal.WRONG_MODE) {
            helper.fail("a cable-only hitch should refuse a rigid coupling");
            return;
        }
        if (!TowLinks.couple(tractor, trailer, fifthWheel, TowMode.HITCH)) {
            helper.fail("the trailer should go on the fifth wheel");
            return;
        }
        if (TowLinks.check(tractor, trailer, fifthWheel, TowMode.HITCH, false) != TowLinks.Refusal.ALREADY_TOWED) {
            helper.fail("a trailer already on a hitch should refuse a second one");
            return;
        }
        release(lane, tractor, trailer, car);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aSavedCouplingFindsItsTractorAgain(GameTestHelper helper) {
        Lane lane = lane(helper, 8);
        Train train = couple(helper, lane, TowMode.HITCH);
        if (train == null) {
            return;
        }
        CompoundTag saved = new CompoundTag();
        train.trailer.addAdditionalSaveData(saved);
        if (!saved.hasUUID("TowTractor")) {
            helper.fail("a coupled trailer should write down what is towing it");
            return;
        }
        TowLinks.uncouple(train.trailer);
        train.trailer.readAdditionalSaveData(saved);
        if (train.trailer.pendingTow() == null) {
            helper.fail("reading the save back should leave a coupling looking for its tractor");
            return;
        }
        for (int tick = 0; tick < 40 && !TowLinks.isTowed(train.trailer); tick++) {
            TowLinks.tickPending(train.trailer);
        }
        TowLink found = train.trailer.towLink();
        if (found == null) {
            helper.fail("the coupling never found its tractor again");
            return;
        }
        if (found.tractor() != train.tractor || !found.hitchId().equals("fifth_wheel")) {
            helper.fail("the coupling came back on the wrong hitch: " + found.hitchId());
            return;
        }
        release(lane, train.tractor, train.trailer);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aTractorUnloadingFirstLeavesTheCouplingWaiting(GameTestHelper helper) {
        Lane lane = lane(helper, 9);
        Train train = couple(helper, lane, TowMode.HITCH);
        if (train == null) {
            return;
        }
        CompoundTag tractorSave = new CompoundTag();
        train.tractor.save(tractorSave);
        train.tractor.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        if (TowLinks.isTowed(train.trailer) || train.trailer.pendingTow() == null) {
            helper.fail("the tractor unloading should leave the coupling waiting, not dropped");
            return;
        }
        CompoundTag trailerSave = new CompoundTag();
        train.trailer.addAdditionalSaveData(trailerSave);
        if (!trailerSave.hasUUID("TowTractor")) {
            helper.fail("a trailer saved after its tractor unloaded lost its coupling");
            return;
        }
        Entity back = EntityType.loadEntityRecursive(tractorSave, helper.getLevel(), entity -> entity);
        if (!(back instanceof AbstractVehicle tractor) || !helper.getLevel().addFreshEntity(tractor)) {
            helper.fail("the tractor could not be read back");
            return;
        }
        for (int tick = 0; tick < 40 && !TowLinks.isTowed(train.trailer); tick++) {
            TowLinks.tickPending(train.trailer);
        }
        AbstractVehicle found = TowLinks.tractorOf(train.trailer);
        release(lane, tractor, train.trailer);
        if (found != tractor) {
            helper.fail("the coupling never found its reloaded tractor");
            return;
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- harness

    private record Lane(BlockPos at, ServerLevel level) {}

    private record Train(AbstractVehicle tractor, AbstractVehicle trailer, HitchInfo hitch) {

        double gap() {
            return TowLinks.hitchPoint(tractor, hitch).distanceTo(TowLinks.couplingPoint(trailer));
        }

    }

    private static Lane lane(GameTestHelper helper, int index) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(BlockPos.ZERO).offset(LANE_X + index * LANE_PITCH, index * LANE_RISE, 0);
        force(level, at, true);
        floor(level, at, Blocks.STONE.defaultBlockState());
        return new Lane(at, level);
    }

    private static void release(Lane lane, AbstractVehicle... vehicles) {
        for (AbstractVehicle vehicle : vehicles) {
            TowLinks.uncouple(vehicle);
            vehicle.discard();
        }
        floor(lane.level, lane.at, Blocks.AIR.defaultBlockState());
        force(lane.level, lane.at, false);
    }

    private static void floor(ServerLevel level, BlockPos at, net.minecraft.world.level.block.state.BlockState state) {
        for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
            for (int z = -BACK; z <= AHEAD; z++) {
                level.setBlockAndUpdate(at.offset(x, 0, z), state);
            }
        }
    }

    private static void force(ServerLevel level, BlockPos at, boolean forced) {
        for (int cx = (at.getX() - HALF_WIDTH) >> 4; cx <= (at.getX() + HALF_WIDTH) >> 4; cx++) {
            for (int cz = (at.getZ() - BACK) >> 4; cz <= (at.getZ() + AHEAD) >> 4; cz++) {
                ForcedChunks.set(level, cx, cz, forced);
            }
        }
    }

    /** Settle, then {@code ticks} of {@code each} (controls reset first), then {@code end}. */
    private static void drive(GameTestHelper helper, Lane lane, AbstractVehicle tractor, int settle, int ticks,
                              Consumer<AbstractVehicle> each, Runnable end) {
        ArmorStand[] driver = new ArmorStand[1];
        GameTestSequence sequence = helper.startSequence()
                .thenIdle(settle)
                .thenExecute(() -> driver[0] = driver(lane.level, tractor));
        for (int tick = 0; tick < ticks; tick++) {
            sequence = sequence.thenExecute(() -> {
                tractor.controlUnit.reset();
                tractor.controlUnit.setOperator(driver[0]);
                each.accept(tractor);
            }).thenIdle(1);
        }
        sequence.thenExecute(() -> {
            driver[0].discard();
            end.run();
        }).thenSucceed();
    }

    private static ArmorStand driver(ServerLevel level, AbstractVehicle vehicle) {
        ArmorStand driver = new ArmorStand(level, vehicle.getX(), vehicle.getY(), vehicle.getZ());
        driver.setNoGravity(true);
        driver.setInvisible(true);
        level.addFreshEntity(driver);
        vehicle.setEnergy(vehicle.energyInfo.capacityUnits());
        vehicle.controlUnit.setOperator(driver);
        vehicle.toggleEngine(true);
        return driver;
    }

    /** Articulation about +y, degrees. */
    private static double articulation(Train train) {
        return Mth.wrapDegrees(train.trailer.getYRot() - train.tractor.getYRot());
    }

    private static double horizontal(AbstractVehicle vehicle) {
        Vec3 v = vehicle.getDeltaMovement();
        return Math.hypot(v.x, v.z);
    }

    @Nullable
    private static Train couple(GameTestHelper helper, Lane lane, TowMode mode) {
        AbstractVehicle tractor = spawn(helper, lane, TRACTOR, 0);
        HitchInfo hitch = tractor.towing.hitch("fifth_wheel");
        if (hitch == null) {
            helper.fail("the tractor declares no fifth wheel; check its towing block");
            return null;
        }
        AbstractVehicle trailer = spawn(helper, lane, TRAILER, 0);
        seat(trailer, TowLinks.hitchPoint(tractor, hitch));
        if (!TowLinks.couple(tractor, trailer, hitch, mode)) {
            helper.fail("the trailer would not couple to the fifth wheel");
            return null;
        }
        return new Train(tractor, trailer, hitch);
    }

    /** Kingpin onto {@code hitch}. */
    private static void seat(AbstractVehicle trailer, Vec3 hitch) {
        Vec3 at = hitch.subtract(trailer.towing.towable.offset);
        trailer.setPos(at.x, at.y, at.z);
        trailer.updateOBBs();
    }

    private static AbstractVehicle spawn(GameTestHelper helper, Lane lane, ResourceLocation id, double z) {
        return spawn(helper, lane, id, z, 0);
    }

    private static AbstractVehicle spawn(GameTestHelper helper, Lane lane, ResourceLocation id, double z, double x) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        helper.assertTrue(data.isPresent(), id + " is not in the roster");
        AbstractVehicle vehicle = data.get().construct(lane.level, Vec3.atBottomCenterOf(lane.at.above()).add(x, 0.1, z), 0, 0);
        vehicle.setDisplayId(id);
        helper.assertTrue(lane.level.addFreshEntity(vehicle), id + " could not be added");
        vehicle.updateOBBs();
        return vehicle;
    }

}
