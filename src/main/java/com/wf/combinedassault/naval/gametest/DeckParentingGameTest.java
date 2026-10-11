package com.wf.combinedassault.naval.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.ShipVehicle;
import com.wf.combinedassault.naval.trial.TrialCrew;
import com.wf.combinedassault.vehicle.PhysicsTrace;
import com.wf.combinedassault.vehicle.parenting.DeckAttachment;
import com.wf.combinedassault.vehicle.parenting.VehicleHarness;
import com.wf.combinedassault.vehicle.parenting.VehicleParenting;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What a moving deck does to things standing on it, measured in the hull's own frame.
 *
 * <p>Each cost here is imposed on something not doing anything: a hand not walking, a vehicle not
 * driven. In the hull's frame these are zero; each regression here came from measuring elsewhere.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class DeckParentingGameTest {

    private static final String POOL = "water_pool";
    private static final ResourceLocation BOAT = CombinedAssault.modLocation("riverine");
    private static final ResourceLocation RIDER = CombinedAssault.modLocation("humvee");

    /** Ticks for the hull to make its speed after the telegraph. */
    private static final int UNDER_WAY = 60;

    /**
     * A hand standing still on a hull under way is not walking, whatever its world position does.
     *
     * <p>Animation driven by world-space step; on a ship that step is the ship's => a hand unmoved on
     * deck plays a full walk cycle on the spot. Subtracting the carry lines up only when it lands
     * inside the hand's own tick, which depends on tick order => right on mobs, wrong on the player.
     */
    @GameTest(batch = "naval", template = POOL, timeoutTicks = 600)
    public static void aStandingHandDoesNotWalk(GameTestHelper helper) {
        ShipVehicle boat = spawn(helper, BOAT, 24, 13, 10, 0);
        if (boat == null) {
            return;
        }
        TrialCrew crew = new TrialCrew();
        IronGolem[] hand = new IronGolem[1];
        Vector3d local = new Vector3d();
        double[] last = new double[2];
        double[] worst = new double[3];
        helper.startSequence()
                .thenIdle(60)
                .thenExecute(() -> {
                    if (!crew.muster(helper.getLevel(), boat, 1, 0, null)) {
                        helper.fail("no deck to muster a hand on");
                        return;
                    }
                    List<IronGolem> found = helper.getLevel()
                            .getEntitiesOfClass(IronGolem.class, helper.getBounds());
                    if (found.size() != 1) {
                        helper.fail("expected one hand aboard, found " + found.size());
                        return;
                    }
                    hand[0] = found.get(0);
                })
                // 0.1 block clear of the deck: must settle before measuring anything.
                .thenIdle(60)
                .thenExecute(() -> {
                    boat.toggleEngine(true);
                    boat.setPower(100);
                    boat.setHelm(1.0, 0.0);
                })
                .thenIdle(UNDER_WAY)
                .thenExecute(() -> {
                    IronGolem mob = hand[0];
                    VehicleParenting.toLocal(boat.deckSnapshot(), mob.getX(), mob.getY(), mob.getZ(), local);
                    last[0] = local.x;
                    last[1] = local.z;
                })
                .thenExecuteFor(80, () -> {
                    IronGolem mob = hand[0];
                    VehicleParenting.toLocal(boat.deckSnapshot(), mob.getX(), mob.getY(), mob.getZ(), local);
                    double dx = local.x - last[0];
                    double dz = local.z - last[1];
                    last[0] = local.x;
                    last[1] = local.z;
                    worst[0] = Math.max(worst[0], mob.walkAnimation.speed());
                    worst[1] = Math.max(worst[1], Math.sqrt(dx * dx + dz * dz));
                    worst[2] = Math.max(worst[2], boat.getDeltaMovement().horizontalDistance());
                })
                .thenExecute(() -> {
                    if (worst[2] < 0.2) {
                        helper.fail("the boat never got under way: " + worst[2] + " blocks a tick");
                    }
                    if (worst[1] > 0.05) {
                        helper.fail("the hand walked off on its own, " + worst[1]
                                + " blocks a tick on the deck, so this proves nothing");
                    }
                    // 0.2 blocks/tick hull speed reads as a full walk cycle: animation saturates at 1/4 block (world-space step).
                    if (worst[0] > 0.1) {
                        helper.fail("standing still on a deck making " + worst[2]
                                + " a tick, the hand animates a walk of " + worst[0]);
                    }
                    crew.dismiss();
                    discard(boat);
                })
                .thenSucceed();
    }

    /**
     * Waking a vehicle parked on a moving deck leaves it where it is.
     *
     * <p>Harness hands a waking vehicle the carried velocity, correct for one leaving. A vehicle woken
     * on the deck is not leaving: {@code DeckCarry} picks it up the same tick and supplies the ship's
     * motion itself => handoff counts it twice, vehicle sets off at the ship's own speed. On a
     * carrier: a plane walking off the bow the moment its pilot climbs in.
     */
    @GameTest(batch = "naval", template = POOL, timeoutTicks = 800)
    public static void wakingOnAMovingDeckDoesNotLurch(GameTestHelper helper) {
        ShipVehicle boat = spawn(helper, BOAT, 24, 13, 8, 0);
        AbstractVehicle rider = spawnRider(helper, RIDER, 24, 15, 8, 0);
        if (boat == null || rider == null) {
            return;
        }
        double[] worst = new double[2];
        helper.startSequence()
                .thenIdle(120)
                .thenExecute(() -> {
                    if (!VehicleHarness.isHarnessed(rider)) {
                        helper.fail("the rider never parked itself on the deck, so this proves nothing");
                        return;
                    }
                    boat.toggleEngine(true);
                    boat.setPower(100);
                    boat.setHelm(1.0, 0.0);
                })
                .thenIdle(UNDER_WAY)
                .thenExecute(() -> {
                    worst[1] = boat.getDeltaMovement().horizontalDistance();
                    // Engine on is the wake condition a climbing-in pilot also trips.
                    rider.toggleEngine(true);
                })
                // The wake itself; further out is the hull settling under 9 blocks of bobbing boat, not this test's concern.
                .thenExecuteFor(10, () -> {
                    worst[0] = Math.max(worst[0], rider.getDeltaMovement().horizontalDistance());
                })
                .thenExecute(() -> {
                    if (worst[1] < 0.2) {
                        helper.fail("the boat never got under way: " + worst[1] + " blocks a tick");
                    }
                    if (VehicleHarness.isHarnessed(rider)) {
                        helper.fail("the engine is running and it is still asleep on the deck");
                    }
                    // Deck-ridden vehicle moves via the carry, not its own velocity; stands still in world between carries.
                    // Handing it the ship's velocity too is what sets it off at the ship's speed.
                    if (worst[0] > 0.2 * worst[1]) {
                        helper.fail("woken on a deck making " + worst[1]
                                + " a tick, the rider was handed " + worst[0] + " of its own");
                    }
                    if (rider.deckCarrierId() < 0) {
                        helper.fail("ten ticks after waking on the deck it is no longer on it");
                    }
                    discard(rider);
                    discard(boat);
                })
                .thenSucceed();
    }

    /**
     * A vehicle on a deck at sea reads as being on the ground.
     *
     * <p>Aircraft decides rolling vs flying from the block under it; none over water => a plane on a
     * flight deck flies its thrust along its nose, not the deck, taking body axes from the aerodynamic
     * cube: breaks both take-off roll and landing. Carrier id synced both sides => same answer either side.
     */
    @GameTest(batch = "naval", template = POOL, timeoutTicks = 400)
    public static void aDeckCountsAsGround(GameTestHelper helper) {
        ShipVehicle boat = spawn(helper, BOAT, 24, 13, 8, 0);
        AbstractVehicle rider = spawnRider(helper, RIDER, 24, 15, 8, 0);
        if (boat == null || rider == null) {
            return;
        }
        helper.startSequence()
                .thenIdle(120)
                .thenExecute(() -> {
                    if (helper.getLevel().getBlockState(rider.blockPosition().below()).isSolid()) {
                        helper.fail("there is a block under the rider, so this proves nothing");
                        return;
                    }
                    if (!rider.groundedOrOnDeck()) {
                        helper.fail("parked on a deck over open water and it reads as airborne");
                    }
                    discard(rider);
                    discard(boat);
                })
                .thenSucceed();
    }

    /** Parked while the ship is under way: the harness carries it, so it keeps none of the ship's velocity. */
    @GameTest(batch = "naval", template = POOL, timeoutTicks = 600)
    public static void parkingOnAMovingDeckKeepsNoVelocity(GameTestHelper helper) {
        ShipVehicle boat = spawn(helper, BOAT, 24, 13, 8, 0);
        AbstractVehicle rider = spawnRider(helper, RIDER, 24, 15, 8, 0);
        if (boat == null || rider == null) {
            return;
        }
        double[] worst = new double[2];
        helper.startSequence()
                .thenIdle(60)
                .thenExecute(() -> {
                    // Engine on: awake, carried by DeckCarry rather than parked before the ship moves.
                    rider.toggleEngine(true);
                    boat.toggleEngine(true);
                    boat.setPower(100);
                    boat.setHelm(1.0, 0.0);
                })
                .thenIdle(UNDER_WAY)
                .thenExecute(() -> {
                    worst[1] = boat.getDeltaMovement().horizontalDistance();
                    rider.toggleEngine(false);
                })
                .thenWaitUntil(() -> {
                    if (!VehicleHarness.isHarnessed(rider)) {
                        throw new net.minecraft.gametest.framework.GameTestAssertException("not parked yet");
                    }
                })
                .thenExecuteFor(5, () -> worst[0] = Math.max(worst[0], rider.getDeltaMovement().length()))
                .thenExecute(() -> {
                    discard(rider);
                    discard(boat);
                    if (worst[1] < 0.2) {
                        helper.fail("the boat never got under way: " + worst[1] + " blocks a tick");
                    }
                    if (worst[0] > 1.0E-3) {
                        helper.fail("parked on a deck making " + worst[1] + " a tick, it holds "
                                + worst[0] + " of velocity it will be handed again on waking");
                    }
                })
                .thenSucceed();
    }

    /** Solved on the tick thread (a trace forces it), an awake vehicle on a moving deck is not rammed by it. */
    @GameTest(batch = "naval", template = POOL, timeoutTicks = 600)
    public static void aDeckDoesNotRamWhatItCarries(GameTestHelper helper) {
        ShipVehicle boat = spawn(helper, BOAT, 24, 13, 8, 0);
        AbstractVehicle rider = spawnRider(helper, RIDER, 24, 15, 8, 0);
        if (boat == null || rider == null) {
            return;
        }
        rider.setPhysicsTrace(new PhysicsTrace(rider, 1000));
        float[] health = new float[2];
        double[] speed = new double[1];
        helper.startSequence()
                .thenIdle(60)
                .thenExecute(() -> {
                    // Engine on keeps it awake: carried by DeckCarry, solved every tick.
                    rider.toggleEngine(true);
                    boat.toggleEngine(true);
                    boat.setPower(100);
                    boat.setHelm(1.0, 0.0);
                    health[0] = rider.getHealth();
                    health[1] = boat.getHealth();
                })
                // Pool end at ~75 ticks: 0.78 b/t into the wall = 16 m/s impact, boat destroyed, rider in the blast.
                .thenExecuteFor(UNDER_WAY, () -> speed[0] = Math.max(speed[0], boat.getDeltaMovement().horizontalDistance()))
                .thenExecute(() -> {
                    boolean aboard = rider.deckCarrierId() == boat.getId();
                    discard(rider);
                    discard(boat);
                    if (speed[0] < 0.2) {
                        helper.fail("the boat never got under way: " + speed[0] + " blocks a tick");
                    } else if (!aboard) {
                        helper.fail("the rider left the deck, so this proves nothing");
                    } else if (rider.getHealth() < health[0] || boat.getHealth() < health[1]) {
                        helper.fail("riding a deck making " + speed[0] + " a tick cost the rider "
                                + (health[0] - rider.getHealth()) + " and the boat " + (health[1] - boat.getHealth()));
                    }
                })
                .thenSucceed();
    }

    /** Rider's chunk loads before its hull's (a player joining, a mob across a chunk line): it waits, then stands where it stood. */
    @GameTest(batch = "naval", template = POOL, timeoutTicks = 400)
    public static void aRiderLoadedBeforeItsHullWaitsForIt(GameTestHelper helper) {
        reloadRider(helper, false);
    }

    /** Hull's chunk unloads first: the rider it carried waits instead of falling. */
    @GameTest(batch = "naval", template = POOL, timeoutTicks = 400)
    public static void aHullUnloadingFirstLeavesItsRiderWaiting(GameTestHelper helper) {
        reloadRider(helper, true);
    }

    private static void reloadRider(GameTestHelper helper, boolean hullUnloadsFirst) {
        ShipVehicle boat = spawn(helper, BOAT, 24, 13, 10, 0);
        if (boat == null) {
            return;
        }
        TrialCrew crew = new TrialCrew();
        IronGolem[] hand = new IronGolem[1];
        CompoundTag boatSave = new CompoundTag();
        Vector3d stood = new Vector3d();
        double[] heldY = new double[2];
        AbstractVehicle[] back = new AbstractVehicle[1];
        helper.startSequence()
                .thenIdle(60)
                .thenExecute(() -> {
                    if (!crew.muster(helper.getLevel(), boat, 1, 0, null)) {
                        helper.fail("no deck to muster a hand on");
                    }
                })
                .thenIdle(60)
                .thenExecute(() -> {
                    IronGolem mob = helper.getLevel().getEntitiesOfClass(IronGolem.class, helper.getBounds()).get(0);
                    DeckAttachment attachment = VehicleParenting.attachmentOf(mob);
                    if (attachment == null || attachment.vehicle() != boat || !attachment.gripped()) {
                        helper.fail("the hand never stood on the deck, so this proves nothing");
                        return;
                    }
                    VehicleParenting.toLocal(boat.deckSnapshot(), mob.getX(), mob.getY(), mob.getZ(), stood);
                    boat.save(boatSave);
                    if (hullUnloadsFirst) {
                        boat.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                        hand[0] = mob;
                    } else {
                        CompoundTag handSave = new CompoundTag();
                        mob.save(handSave);
                        boat.discard();
                        crew.dismiss();
                        if (!(EntityType.loadEntityRecursive(handSave, helper.getLevel(), e -> e) instanceof IronGolem loaded)) {
                            helper.fail("the hand could not be read back");
                            return;
                        }
                        loaded.goalSelector.removeAllGoals(goal -> true);
                        loaded.targetSelector.removeAllGoals(goal -> true);
                        helper.getLevel().addFreshEntity(loaded);
                        hand[0] = loaded;
                    }
                    heldY[0] = hand[0].getY();
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    heldY[1] = hand[0].getY();
                    if (!(EntityType.loadEntityRecursive(boatSave, helper.getLevel(), e -> e) instanceof AbstractVehicle hull)
                            || !helper.getLevel().addFreshEntity(hull)) {
                        helper.fail("the boat could not be read back");
                        return;
                    }
                    back[0] = hull;
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    IronGolem mob = hand[0];
                    AbstractVehicle hull = back[0];
                    DeckAttachment attachment = VehicleParenting.attachmentOf(mob);
                    Vector3d now = VehicleParenting.toLocal(hull.deckSnapshot(), mob.getX(), mob.getY(), mob.getZ(), new Vector3d());
                    mob.discard();
                    discard(hull);
                    if (heldY[0] - heldY[1] > 0.1) {
                        helper.fail(String.format(Locale.ROOT, "with its hull away the hand fell %.2f", heldY[0] - heldY[1]));
                    } else if (attachment == null || attachment.vehicle() != hull || !attachment.gripped()) {
                        helper.fail("the hand never took hold of its reloaded hull");
                    } else if (Math.abs(now.y - stood.y) > 0.1 || now.distance(stood) > 0.5) {
                        helper.fail(String.format(Locale.ROOT, "the hand stood at %s on the deck and came back at %s",
                                stood.toString(NumberFormat.getInstance(Locale.ROOT)), now.toString(NumberFormat.getInstance(Locale.ROOT))));
                    }
                })
                .thenSucceed();
    }

    /** Parked vehicle's chunk loads before its hull's (a world load): it holds where it was, then parks again. */
    @GameTest(batch = "naval", template = POOL, timeoutTicks = 500)
    public static void aParkedVehicleLoadedBeforeItsHullWaitsForIt(GameTestHelper helper) {
        reloadParked(helper, false);
    }

    /** Hull's chunk unloads first: the vehicle parked on it holds instead of falling. */
    @GameTest(batch = "naval", template = POOL, timeoutTicks = 500)
    public static void aHullUnloadingFirstLeavesItsParkedVehicleWaiting(GameTestHelper helper) {
        reloadParked(helper, true);
    }

    private static void reloadParked(GameTestHelper helper, boolean hullUnloadsFirst) {
        ShipVehicle boat = spawn(helper, BOAT, 24, 13, 8, 0);
        AbstractVehicle rider = spawnRider(helper, RIDER, 24, 15, 8, 0);
        if (boat == null || rider == null) {
            return;
        }
        CompoundTag boatSave = new CompoundTag();
        Vector3d stood = new Vector3d();
        double[] heldY = new double[2];
        AbstractVehicle[] parked = new AbstractVehicle[1];
        AbstractVehicle[] back = new AbstractVehicle[1];
        helper.startSequence()
                .thenIdle(120)
                .thenExecute(() -> {
                    if (!VehicleHarness.isHarnessed(rider)) {
                        helper.fail("the rider never parked itself on the deck, so this proves nothing");
                        return;
                    }
                    VehicleParenting.toLocal(boat.deckSnapshot(), rider.getX(), rider.getY(), rider.getZ(), stood);
                    boat.save(boatSave);
                    if (hullUnloadsFirst) {
                        boat.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                        parked[0] = rider;
                    } else {
                        CompoundTag riderSave = new CompoundTag();
                        rider.save(riderSave);
                        boat.discard();
                        rider.discard();
                        if (!(EntityType.loadEntityRecursive(riderSave, helper.getLevel(), e -> e) instanceof AbstractVehicle loaded)
                                || !helper.getLevel().addFreshEntity(loaded)) {
                            helper.fail("the parked vehicle could not be read back");
                            return;
                        }
                        parked[0] = loaded;
                    }
                    if (parked[0].pendingMount() == null) {
                        helper.fail("a parked vehicle without its hull should be waiting for it");
                    }
                    heldY[0] = parked[0].getY();
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    heldY[1] = parked[0].getY();
                    if (!(EntityType.loadEntityRecursive(boatSave, helper.getLevel(), e -> e) instanceof AbstractVehicle hull)
                            || !helper.getLevel().addFreshEntity(hull)) {
                        helper.fail("the boat could not be read back");
                        return;
                    }
                    back[0] = hull;
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    AbstractVehicle vehicle = parked[0];
                    AbstractVehicle hull = back[0];
                    Vector3d now = VehicleParenting.toLocal(hull.deckSnapshot(), vehicle.getX(), vehicle.getY(), vehicle.getZ(), new Vector3d());
                    boolean relinked = VehicleHarness.carrierOf(vehicle) == hull;
                    discard(vehicle);
                    discard(hull);
                    if (heldY[0] - heldY[1] > 0.1) {
                        helper.fail(String.format(Locale.ROOT, "with its hull away the vehicle fell %.2f", heldY[0] - heldY[1]));
                    } else if (!relinked) {
                        helper.fail("the vehicle never parked on its reloaded hull");
                    } else if (now.distance(stood) > 0.1) {
                        helper.fail(String.format(Locale.ROOT, "parked at %s on the deck, came back at %s",
                                stood.toString(NumberFormat.getInstance(Locale.ROOT)), now.toString(NumberFormat.getInstance(Locale.ROOT))));
                    }
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

    @Nullable
    private static ShipVehicle spawn(GameTestHelper helper, ResourceLocation id, int x, int y, int z, float yaw) {
        AbstractVehicle vehicle = spawnRider(helper, id, x, y, z, yaw);
        if (vehicle == null) {
            return null;
        }
        if (!(vehicle instanceof ShipVehicle ship)) {
            helper.fail(id + " is not a ship");
            return null;
        }
        return ship;
    }

    private static void discard(AbstractVehicle vehicle) {
        if (!vehicle.isRemoved()) {
            vehicle.discard();
        }
    }

}
