package com.wf.combinedassault.naval.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllItems;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.ShipVehicle;
import com.wf.combinedassault.naval.trial.TrialCrew;
import com.wf.combinedassault.vehicle.parenting.VehicleHarness;

import java.util.List;
import java.util.Optional;

/**
 * Ships in a stone basin of still water. Pool template = the arena; nothing here writes outside it.
 * Hull sits at the template's centre, in the arena's own ticking chunk.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class ShipGameTest {

    private static final String POOL = "water_pool";
    private static final String TRENCH = "sea_trench";
    private static final ResourceLocation BOAT = CombinedAssault.modLocation("riverine");
    private static final ResourceLocation SUB = CombinedAssault.modLocation("kilo");
    private static final ResourceLocation RIDER = CombinedAssault.modLocation("humvee");

    /**
     * Water top, relative to {@code absolutePos(ZERO)}: template 1 block above the structure block,
     * water fills template blocks 1..10 (1..36), source = 8/9 high.
     */
    private static final double POOL_SURFACE = 11 + 8.0 / 9.0;
    private static final double TRENCH_SURFACE = 37 + 8.0 / 9.0;

    /** The hull's waterline mark settles on the surface, and stays there. */
    @GameTest(batch = "naval", template = POOL, timeoutTicks = 400)
    public static void aBoatFloatsAtItsWaterline(GameTestHelper helper) {
        ShipVehicle boat = spawn(helper, BOAT, 24, 13, 24, 0);
        if (boat == null) {
            return;
        }
        double surface = helper.absolutePos(BlockPos.ZERO).getY() + POOL_SURFACE;
        helper.startSequence()
                .thenIdle(200)
                .thenExecute(() -> {
                    double line = boat.getY() + boat.params().waterline;
                    if (!boat.afloat()) {
                        helper.fail("the boat does not read as afloat");
                    }
                    if (Math.abs(line - surface) > 0.15) {
                        helper.fail("waterline at " + line + ", surface at " + surface);
                    }
                    if (Math.abs(boat.getDeltaMovement().y) > 0.01) {
                        helper.fail("still heaving at " + boat.getDeltaMovement().y);
                    }
                    if (!boat.isInWater()) {
                        helper.fail("isInWater is false while afloat");
                    }
                    if (boat.aground()) {
                        helper.fail("reads aground in ten blocks of water");
                    }
                })
                .thenExecute(() -> discard(boat))
                .thenSucceed();
    }

    /** Full ahead moves the hull along its heading; astern brings it back toward a stop. */
    @GameTest(batch = "naval", template = POOL, timeoutTicks = 600)
    public static void aBoatAnswersTheTelegraph(GameTestHelper helper) {
        ShipVehicle boat = spawn(helper, BOAT, 24, 13, 10, 0);
        if (boat == null) {
            return;
        }
        double[] start = new double[1];
        helper.startSequence()
                .thenIdle(60)
                .thenExecute(() -> {
                    start[0] = boat.getZ();
                    boat.toggleEngine(true);
                    boat.setPower(100);
                    boat.setHelm(1.0, 0.0);
                })
                .thenIdle(50)
                .thenExecute(() -> {
                    double moved = boat.getZ() - start[0];
                    if (moved < 4.0) {
                        helper.fail("full ahead for 50 ticks moved only " + moved);
                    }
                    if (boat.surge() < 0.3) {
                        helper.fail("surge only " + boat.surge());
                    }
                    if (Math.abs(boat.getX() - helper.absolutePos(new BlockPos(24, 0, 0)).getX() - 0.5) > 1.0) {
                        helper.fail("drifted sideways to x offset " + (boat.getX() - helper.absolutePos(new BlockPos(24, 0, 0)).getX()));
                    }
                    boat.setHelm(-1.0, 0.0);
                })
                .thenIdle(60)
                .thenExecute(() -> {
                    if (boat.surge() > 0.15) {
                        helper.fail("astern did not check the way: surge " + boat.surge());
                    }
                })
                .thenExecute(() -> discard(boat))
                .thenSucceed();
    }

    /** Rudder over changes heading; stops changing once rudder is amidships and way is off. */
    @GameTest(batch = "naval", template = POOL, timeoutTicks = 600)
    public static void aBoatAnswersTheRudder(GameTestHelper helper) {
        ShipVehicle boat = spawn(helper, BOAT, 24, 13, 24, 0);
        if (boat == null) {
            return;
        }
        helper.startSequence()
                .thenIdle(60)
                .thenExecute(() -> {
                    boat.toggleEngine(true);
                    boat.setPower(100);
                    boat.setHelm(0.4, 1.0);
                })
                .thenIdle(60)
                .thenExecute(() -> {
                    float yaw = Mth.wrapDegrees(boat.getYRot());
                    if (yaw < 10) {
                        helper.fail("full starboard rudder for 60 ticks turned only " + yaw + " degrees");
                    }
                    if (boat.model().yawRate <= 0) {
                        helper.fail("yaw rate is not to starboard: " + boat.model().yawRate);
                    }
                })
                .thenExecute(() -> discard(boat))
                .thenSucceed();
    }

    /** A bank at the waterline stops the hull dead; it neither climbs it nor reads as beached. */
    @GameTest(batch = "naval", template = POOL, timeoutTicks = 600)
    public static void aBoatStopsAtTheBank(GameTestHelper helper) {
        // Stone from the floor to above the surface across the far end of the pool: a river bank.
        for (int x = 1; x < 47; x++) {
            for (int z = 30; z < 47; z++) {
                for (int y = 2; y <= 12; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        ShipVehicle boat = spawn(helper, BOAT, 24, 13, 12, 0);
        if (boat == null) {
            return;
        }
        helper.startSequence()
                .thenIdle(60)
                .thenExecute(() -> {
                    boat.toggleEngine(true);
                    boat.setPower(100);
                    boat.setHelm(1.0, 0.0);
                })
                .thenIdle(200)
                .thenExecute(() -> {
                    double z = boat.getZ() - helper.absolutePos(BlockPos.ZERO).getZ();
                    // The bow is 4.5 blocks ahead of the origin; the bank starts at z = 30.
                    if (z > 30 - 4.5 + 1.0) {
                        helper.fail("the hull drove into the bank: local z " + z);
                    }
                    if (z < 20) {
                        helper.fail("the hull never reached the bank: local z " + z);
                    }
                    if (Math.abs(boat.surge()) > 0.05) {
                        helper.fail("still making way against the bank: surge " + boat.surge());
                    }
                    if (!boat.afloat()) {
                        helper.fail("reads beached with most of the hull in water");
                    }
                    double line = boat.getY() + boat.params().waterline;
                    if (line > helper.absolutePos(BlockPos.ZERO).getY() + POOL_SURFACE + 0.5) {
                        helper.fail("the hull climbed the bank: waterline " + line);
                    }
                })
                .thenExecute(() -> discard(boat))
                .thenSucceed();
    }

    /** A shoal under the surface grounds the hull: it stops on the shelf face and the readout says so. */
    @GameTest(batch = "naval", template = TRENCH, timeoutTicks = 800)
    public static void aSubmarineGroundsOnAShoal(GameTestHelper helper) {
        // Stone up to y 33 at the far end: four blocks of water over it, against a keel 7.6 under the line.
        for (int x = 78; x < 95; x++) {
            for (int z = 1; z < 23; z++) {
                for (int y = 2; y <= 33; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        // Stern 31.4 behind the origin: x 30 put it in the end wall.
        ShipVehicle sub = spawn(helper, SUB, 33, 38, 12, -90);
        if (sub == null) {
            return;
        }
        helper.startSequence()
                .thenIdle(120)
                .thenExecute(() -> {
                    sub.toggleEngine(true);
                    sub.setPower(100);
                    sub.setHelm(1.0, 0.0);
                })
                .thenIdle(400)
                .thenExecute(() -> {
                    double x = sub.getX() - helper.absolutePos(BlockPos.ZERO).getX();
                    // The bow is 36.5 ahead of the origin; the shelf face is at x = 78.
                    if (x > 78 - 36.5 + 1.0) {
                        helper.fail("the hull drove onto the shoal: local x " + x);
                    }
                    if (x < 36) {
                        helper.fail("the hull never reached the shoal: local x " + x);
                    }
                    if (!sub.aground()) {
                        helper.fail("grounded hull does not read as aground: keel " + sub.keelClearance());
                    }
                    if (sub.keelClearance() > 0) {
                        helper.fail("keel clearance over the shoal should be negative, is " + sub.keelClearance());
                    }
                })
                .thenExecute(() -> discard(sub))
                .thenSucceed();
    }

    /** A submarine dives to its setting, holds it, and comes back up. */
    @GameTest(batch = "naval", template = TRENCH, timeoutTicks = 1400)
    public static void aSubmarineDivesAndSurfaces(GameTestHelper helper) {
        ShipVehicle sub = spawn(helper, SUB, 48, 38, 12, 90);
        if (sub == null) {
            return;
        }
        double surface = helper.absolutePos(BlockPos.ZERO).getY() + TRENCH_SURFACE;
        helper.startSequence()
                .thenIdle(160)
                .thenExecute(() -> {
                    double line = sub.getY() + sub.params().waterline;
                    if (Math.abs(line - surface) > 0.2) {
                        helper.fail("surfaced waterline at " + line + ", surface at " + surface);
                    }
                    sub.toggleEngine(true);
                    sub.setPower(100);
                    sub.setDepthSetting(15);
                })
                .thenIdle(500)
                .thenExecute(() -> {
                    double depth = surface - (sub.getY() + sub.params().waterline);
                    if (Math.abs(depth - 15) > 0.5) {
                        helper.fail("set 15, at depth " + depth);
                    }
                    if (!sub.model().submerged) {
                        helper.fail("does not read as submerged at " + depth);
                    }
                    sub.setDepthSetting(0);
                })
                .thenIdle(500)
                .thenExecute(() -> {
                    double depth = surface - (sub.getY() + sub.params().waterline);
                    if (depth > 0.5) {
                        helper.fail("did not surface: depth " + depth);
                    }
                })
                .thenExecute(() -> discard(sub))
                .thenSucceed();
    }

    /** The depth setting is clamped to the water there is. */
    @GameTest(batch = "naval", template = TRENCH, timeoutTicks = 200)
    public static void aSubmarineCannotDiveThroughTheSeabed(GameTestHelper helper) {
        ShipVehicle sub = spawn(helper, SUB, 48, 38, 12, 90);
        if (sub == null) {
            return;
        }
        helper.startSequence()
                .thenIdle(40)
                .thenExecute(() -> {
                    sub.setDepthSetting(200);
                    sub.model().step(sub.params(), new com.wf.combinedassault.naval.ShipHydrodynamics.Pose(),
                            new com.wf.combinedassault.naval.ShipHydrodynamics.Controls(), sub.environment(),
                            1, 1, 71, 30, d -> d);
                    double deepest = sub.environment().waterDepth - sub.params().draft - sub.params().minKeelClearance;
                    if (sub.model().depthSetting > deepest + 1.0e-6) {
                        helper.fail("setting " + sub.model().depthSetting + " exceeds the water " + deepest);
                    }
                    if (sub.model().depthSetting < 20) {
                        helper.fail("36 blocks of water should allow a deeper setting than " + sub.model().depthSetting);
                    }
                })
                .thenExecute(() -> discard(sub))
                .thenSucceed();
    }

    @Nullable
    private static ShipVehicle spawn(GameTestHelper helper, ResourceLocation id, int x, int y, int z, float yaw) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            helper.fail("the test vehicle " + id + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(x, y, z));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, yaw);
        vehicle.setDisplayId(id);
        if (!(vehicle instanceof ShipVehicle ship)) {
            helper.fail(id + " is not a ship");
            return null;
        }
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail("the test vehicle " + id + " could not be added to the world");
            return null;
        }
        vehicle.setEnergy(vehicle.energyInfo.capacityUnits());
        return ship;
    }

    /**
     * Spawn item places a hull on water the player looks at, clear of the player, floating at its
     * waterline. Crosshair ignores fluids => over water this is the only placement path.
     */
    @GameTest(batch = "naval", template = POOL, timeoutTicks = 100)
    public static void theSpawnItemLaunchesOnWater(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        BlockPos at = helper.absolutePos(new BlockPos(24, 16, 6));
        player.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 30.0F);
        ItemStack stack = AllItems.VEHICLE_SPAWN_ITEM.get().createInstance(BOAT);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        if (!stack.use(helper.getLevel(), player, InteractionHand.MAIN_HAND).getResult().consumesAction()) {
            helper.fail("the item found no water in front of the player");
            return;
        }
        double surface = helper.absolutePos(BlockPos.ZERO).getY() + POOL_SURFACE;
        // Arena's own bounds: test arenas sit 8 blocks apart; a wider box would count neighbours' hulls.
        List<ShipVehicle> placed = helper.getLevel().getEntitiesOfClass(ShipVehicle.class, helper.getBounds());
        if (placed.size() != 1) {
            helper.fail("expected one hull on the water, found " + placed.size());
            return;
        }
        ShipVehicle boat = placed.get(0);
        double line = boat.getY() + boat.params().waterline;
        if (Math.abs(line - surface) > 0.2) {
            helper.fail("placed with its waterline at " + line + ", surface at " + surface);
            return;
        }
        // Far enough ahead of the player that the hull isn't placed around whoever launched it.
        double ahead = boat.getZ() - player.getZ();
        if (ahead < boat.getBbWidth()) {
            helper.fail("placed " + ahead + " blocks ahead, which is on top of the player");
            return;
        }
        if (!stack.isEmpty() && stack.getCount() != 0) {
            helper.fail("the item was not consumed");
            return;
        }
        discard(boat);
        helper.succeed();
    }

    /**
     * Starting a parked vehicle's engine wakes it off the deck. Without this, a carrier plane answers
     * throttle with nothing: harnessed vehicles skip their own physics, and the engine has to be able
     * to end that.
     */
    @GameTest(batch = "naval", template = POOL, timeoutTicks = 400)
    public static void anEngineWakesAParkedVehicle(GameTestHelper helper) {
        ShipVehicle boat = spawn(helper, BOAT, 24, 13, 8, 0);
        AbstractVehicle rider = spawnRider(helper, RIDER, 24, 15, 8, 0);
        if (boat == null || rider == null) {
            return;
        }
        helper.startSequence()
                .thenIdle(120)
                .thenExecute(() -> {
                    if (!VehicleHarness.isHarnessed(rider)) {
                        helper.fail("the rider never parked itself on the deck, so this proves nothing");
                    }
                    rider.toggleEngine(true);
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    if (VehicleHarness.isHarnessed(rider)) {
                        helper.fail("the engine is running and it is still asleep on the deck");
                    }
                    if (!rider.isEngineOn()) {
                        helper.fail("the engine switched itself off");
                    }
                })
                .thenExecute(() -> {
                    discard(rider);
                    discard(boat);
                })
                .thenSucceed();
    }

    /** Spawns anything, not just a hull, for the deck tests. */
    @Nullable
    private static AbstractVehicle spawnRider(GameTestHelper helper, ResourceLocation id,
                                              int x, int y, int z, float yaw) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            helper.fail("the test vehicle " + id + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(x, y, z));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, yaw);
        vehicle.setDisplayId(id);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail("the test vehicle " + id + " could not be added to the world");
            return null;
        }
        vehicle.setEnergy(vehicle.energyInfo.capacityUnits());
        return vehicle;
    }

    /**
     * A vehicle standing on deck rides it awake, no harness-sleep needed first. Engine left running so
     * it never settles: the path that used to leave a car standing still in world space while the deck
     * slid out from under it.
     */
    @GameTest(batch = "naval", template = POOL, timeoutTicks = 600)
    public static void aLooseVehicleRidesTheDeck(GameTestHelper helper) {
        ShipVehicle boat = spawn(helper, BOAT, 24, 13, 8, 0);
        AbstractVehicle rider = spawnRider(helper, RIDER, 24, 15, 8, 0);
        if (boat == null || rider == null) {
            return;
        }
        double[] start = new double[3];
        // Started before it can settle: 20 still ticks on deck is all a harness needs.
        rider.toggleEngine(true);
        helper.startSequence()
                .thenIdle(100)
                .thenExecute(() -> {
                    if (VehicleHarness.isHarnessed(rider)) {
                        helper.fail("the rider fell asleep with its engine running");
                    }
                    start[0] = rider.getX() - boat.getX();
                    start[1] = rider.getY() - boat.getY();
                    start[2] = rider.getZ() - boat.getZ();
                    if (Math.abs(start[1]) > 3.0) {
                        helper.fail("the rider is not on the deck: " + start[1] + " above the hull");
                    }
                    boat.toggleEngine(true);
                    boat.setPower(100);
                    boat.setHelm(1.0, 0.0);
                })
                .thenIdle(160)
                .thenExecute(() -> {
                    double sailed = boat.getZ() - helper.absolutePos(new BlockPos(24, 13, 8)).getZ() - 0.5;
                    if (sailed < 4.0) {
                        helper.fail("the boat never got under way: " + sailed + " blocks");
                    }
                    if (VehicleHarness.isHarnessed(rider)) {
                        helper.fail("the rider harnessed itself, so this measured the asleep path");
                    }
                    double dx = rider.getX() - boat.getX() - start[0];
                    double dz = rider.getZ() - boat.getZ() - start[2];
                    double slid = Math.sqrt(dx * dx + dz * dz);
                    // Small hull pitches under way; rider loses grip occasionally => creeps. What matters: still aboard,
                    // not stationary. Carrier (no pitch) holds to 0.0007 blocks/tick.
                    if (slid > 3.0) {
                        helper.fail("the rider slid " + slid + " blocks across a deck that sailed " + sailed);
                    }
                })
                .thenExecute(() -> {
                    discard(rider);
                    discard(boat);
                })
                .thenSucceed();
    }

    /**
     * A hand walking a deck makes the same ground whether the ship is stopped or under way.
     *
     * <p>Hull under way trims; rider's movement is clipped in the hull's frame => clip returns a
     * horizontal difference that is purely the frame transform round-trip. {@code Entity.move} infers
     * a wall from exactly that difference, zeroes the rider's horizontal velocity for the tick =>
     * walks at 1 tick of acceleration instead of terminal speed; player also stops sprinting. 1 degree
     * of trim crosses the threshold.
     */
    @GameTest(batch = "naval", template = POOL, timeoutTicks = 600)
    public static void aHandKeepsItsPaceWhenTheDeckIsUnderWay(GameTestHelper helper) {
        ShipVehicle boat = spawn(helper, BOAT, 24, 13, 4, 0);
        if (boat == null) {
            return;
        }
        TrialCrew crew = new TrialCrew();
        // Fastest tick per window: mean dominated by turns at a beat this short; top-of-walk speed is what a per-tick velocity loss halves.
        double[] rest = {0};
        double[] way = {0};
        double[] trim = {0};
        int[] tick = {0};
        helper.startSequence()
                .thenIdle(60)
                .thenExecute(() -> {
                    if (!crew.muster(helper.getLevel(), boat, 1, 0, null)) {
                        helper.fail("the boat has no deck to muster a hand on");
                    }
                })
                .thenExecuteFor(190, () -> {
                    // Every tick: pacing order points at a deck that moved since last tick; a gap would show as one huge step.
                    crew.tick(boat);
                    int t = ++tick[0];
                    if (t > 40 && t <= 100) {
                        rest[0] = Math.max(rest[0], crew.topPace);
                    }
                    if (t == 100) {
                        boat.toggleEngine(true);
                        boat.setPower(100);
                        boat.setHelm(1.0, 0.0);
                    }
                    if (t > 140) {
                        way[0] = Math.max(way[0], crew.topPace);
                        trim[0] = Math.max(trim[0], Math.abs(boat.getXRot()));
                    }
                })
                .thenExecute(() -> {
                    if (trim[0] < 1.0) {
                        helper.fail("the hull never trimmed past " + trim[0] + " degrees, so this proves nothing");
                    }
                    if (rest[0] < 0.08) {
                        helper.fail("the hand did not walk at rest either: " + rest[0] + " blocks a tick");
                    }
                    if (crew.aboard < 1) {
                        helper.fail("the hand went over the side");
                    }
                    // Same window length either side. Trimmed deck costs a little real pace (part of the walk now uphill); halving it is the defect.
                    if (way[0] < rest[0] * 0.75) {
                        helper.fail("under way the hand walked at " + way[0] + " blocks a tick against "
                                + rest[0] + " at rest, at " + trim[0] + " degrees of trim");
                    }
                })
                .thenExecute(() -> {
                    crew.dismiss();
                    discard(boat);
                })
                .thenSucceed();
    }

    private static void discard(AbstractVehicle vehicle) {
        if (!vehicle.isRemoved()) {
            vehicle.discard();
        }
    }

}
