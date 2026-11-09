package com.wf.combinedassault.vehicle.part.gametest;

import com.mojang.authlib.GameProfile;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.dynamics.part.PartClipTracks;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.AccessActions;
import com.wf.combinedassault.vehicle.part.DoorUnit;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.SwitchableUnit;
import com.wf.combinedassault.vehicle.util.VectorUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;

@PrefixGameTestTemplate(false)
public class AccessGameTest {

    private static final String TEMPLATE = "empty";

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aTailgateOpensAndCloses(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "humvee");
        if (humvee == null) {
            return;
        }
        SwitchableUnit<?> tailgate = (SwitchableUnit<?>) humvee.getPartUnit("tailgate").orElseThrow();
        boolean opened = AccessActions.toggle(player(helper), humvee, tailgate.getIndex()) && tailgate.isOn();
        boolean closed = AccessActions.toggle(player(helper), humvee, tailgate.getIndex()) && !tailgate.isOn();
        humvee.discard();
        if (!opened || !closed) {
            helper.fail("tailgate opened " + opened + ", closed " + closed);
            return;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aHatchIsADoor(GameTestHelper helper) {
        AbstractVehicle tank = spawn(helper, "m1a2");
        if (tank == null) {
            return;
        }
        SwitchableUnit<?> hatch = (SwitchableUnit<?>) tank.getPartUnit("loader_hatch").orElseThrow();
        boolean opened = AccessActions.toggle(player(helper), tank, hatch.getIndex()) && hatch.isOn();
        tank.discard();
        if (!opened) {
            helper.fail("loader hatch did not open");
            return;
        }
        helper.succeed();
    }

    /** Storage node on foot opens the hold; ride-inventory from a remote UAV link is refused. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void storageOpensOnFootNotOverAUavLink(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "humvee");
        AbstractVehicle ciws = spawn(helper, "mk15");
        if (humvee == null || ciws == null) {
            return;
        }
        int[] opened = new int[1];
        // FakePlayer#openMenu is a no-op: count the calls.
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "access_storage")) {
            @Override
            public OptionalInt openMenu(@Nullable MenuProvider provider, @Nullable Consumer<RegistryFriendlyByteBuf> extra) {
                opened[0]++;
                return OptionalInt.empty();
            }
        };
        humvee.openCustomInventoryScreen(player);
        boolean onFoot = opened[0] == 1;
        ciws.setDetachedBodyAnchor(player, player.position());
        ciws.openCustomInventoryScreen(player);
        boolean remote = opened[0] == 2;
        ciws.setDetachedBodyAnchor(player, null);
        humvee.discard();
        ciws.discard();
        if (!onFoot || remote) {
            helper.fail("on foot opened " + onFoot + ", over the link opened " + remote);
            return;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void landingGearIsNotADoor(GameTestHelper helper) {
        AbstractVehicle jet = spawn(helper, "a10");
        if (jet == null) {
            return;
        }
        PartUnit<?> gear = jet.getPartUnit("landing_gear").orElseThrow();
        boolean before = ((SwitchableUnit<?>) gear).isOn();
        boolean toggled = AccessActions.toggle(player(helper), jet, gear.getIndex());
        boolean after = ((SwitchableUnit<?>) gear).isOn();
        boolean canopy = AccessActions.isDoor(jet.getPartUnit("canopy").orElseThrow());
        jet.discard();
        if (toggled || before != after || !canopy) {
            helper.fail("gear toggled " + toggled + " (" + before + " -> " + after + "), canopy a door " + canopy);
            return;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aDestroyedDoorIsJammed(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "humvee");
        if (humvee == null) {
            return;
        }
        SwitchableUnit<?> door = (SwitchableUnit<?>) humvee.getPartUnit("door_fl").orElseThrow();
        door.setHealth(0);
        boolean toggled = AccessActions.toggle(player(helper), humvee, door.getIndex());
        boolean open = door.isOn();
        humvee.discard();
        if (!door.isDestroyed() || toggled || open) {
            helper.fail("destroyed " + door.isDestroyed() + ", toggled " + toggled + ", open " + open);
            return;
        }
        helper.succeed();
    }

    /** Port (+X) side: eye on door_fl => door_fl; nearest fallback door_fl; aimed at the centre => never starboard. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aPortClickOpensThePortDoor(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "humvee");
        if (humvee == null) {
            return;
        }
        helper.runAfterDelay(5, () -> {
            FakePlayer player = player(helper);
            Vec3 base = humvee.position();
            player.setPos(base.x + 2.6, base.y, base.z + 0.5);
            Vec3 eye = player.getEyePosition();
            Vec3 door = humvee.getPartUnit("door_fl").orElseThrow().worldPosition(new Vec3(1.2, 1.2, 0.5));
            Vec3 aim = door.subtract(eye).normalize();
            PartUnit<?> hit = VectorUtil.pickPartUnit(humvee, eye, eye.add(aim.scale(4)));
            DoorUnit nearest = humvee.getNearestDoorUnit(player);
            // Through the B-pillar gap, across the cab: the far door's inside is the first part surface.
            Vec3 centre = humvee.getBoundingBox().getCenter();
            PartUnit<?> centred = VectorUtil.pickPartUnit(humvee, eye, eye.add(centre.subtract(eye).normalize().scale(4)));
            humvee.discard();
            String hitId = hit == null ? null : hit.getId();
            String centredId = centred == null ? null : centred.getId();
            if (!"door_fl".equals(hitId) || nearest == null || !"door_fl".equals(nearest.getId())
                    || centredId != null && centredId.endsWith("r")) {
                helper.fail("hit " + hitId + ", nearest " + (nearest == null ? null : nearest.getId())
                        + ", centre pick " + centredId + ", door world " + door + ", eye " + eye);
                return;
            }
            helper.succeed();
        });
    }

    /** Clip-driven hit node follows its part: C5 visor (clip cargo_doors) swings up with the doors. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aClipMovesItsHitGeometry(GameTestHelper helper) {
        AbstractVehicle c5 = spawn(helper, "c5");
        if (c5 == null) {
            return;
        }
        // Tracks + hits stepped by hand: a 70 m hull spills into neighbouring arenas and the world may stop ticking it.
        PartClipTracks tracks = c5.clipTracks();
        int visor = c5.hits().model().indexOf("visor");
        tracks.tick();
        c5.hits().refreshClips();
        Vec3 closed = c5.hits().centre(visor).subtract(c5.position());
        SwitchableUnit<?> doors = (SwitchableUnit<?>) c5.getPartUnit("cargo_doors").orElseThrow();
        boolean toggled = AccessActions.toggle(player(helper), c5, doors.getIndex());
        for (int t = 0; t < 60; t++) {
            tracks.tick();
            c5.hits().refreshClips();
        }
        Vec3 open = c5.hits().centre(visor).subtract(c5.position());
        float progress = tracks.track("cargo_doors").progress(1);
        c5.discard();
        if (!toggled || progress != 1 || open.distanceTo(closed) < 1) {
            helper.fail("toggled " + toggled + ", progress " + progress + ", visor hit centre " + closed + " -> " + open);
            return;
        }
        helper.succeed();
    }

    /** Humvee top hatch: one clip keys both leaves; leaf b nests under leaf a's hinge, inside the roof_mount yaw frame. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aTopHatchFoldsBothLeaves(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "humvee");
        if (humvee == null) {
            return;
        }
        PartClipTracks tracks = humvee.clipTracks();
        int a = humvee.hits().model().indexOf("top_hatch_leaf_a");
        int b = humvee.hits().model().indexOf("top_hatch_leaf_b");
        tracks.tick();
        humvee.hits().refreshClips();
        Vec3 closedA = humvee.hits().centre(a).subtract(humvee.position());
        Vec3 closedB = humvee.hits().centre(b).subtract(humvee.position());
        SwitchableUnit<?> hatch = (SwitchableUnit<?>) humvee.getPartUnit("top_hatch").orElseThrow();
        boolean toggled = AccessActions.toggle(player(helper), humvee, hatch.getIndex());
        for (int t = 0; t < 40; t++) {
            tracks.tick();
            humvee.hits().refreshClips();
        }
        Vec3 openA = humvee.hits().centre(a).subtract(humvee.position());
        Vec3 openB = humvee.hits().centre(b).subtract(humvee.position());
        float progress = tracks.track("top_hatch").progress(1);
        humvee.discard();
        // Closed: both leaves level over the ring; open: stacked outboard, leaf b on top.
        if (!toggled || progress != 1 || openA.distanceTo(closedA) < 0.3 || openB.distanceTo(closedB) < 0.7
                || openB.y <= openA.y || Math.abs(closedA.y - closedB.y) > 0.01) {
            helper.fail("toggled " + toggled + ", progress " + progress + ", leaf a " + closedA + " -> " + openA
                    + ", leaf b " + closedB + " -> " + openB);
            return;
        }
        helper.succeed();
    }

    /** Roof seat ({@code hatch: top_hatch}): boarding refused while closed; open + clip run out => boards. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aClosedHatchRefusesBoarding(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "humvee");
        if (humvee == null) {
            return;
        }
        int roof = roofSeat(humvee);
        // Unticked track reads its target: settle it closed first.
        humvee.clipTracks().tick();
        SwitchableUnit<?> hatch = (SwitchableUnit<?>) humvee.getPartUnit("top_hatch").orElseThrow();
        Vec3 at = humvee.position();
        ArmorStand rider = new ArmorStand(helper.getLevel(), at.x, at.y, at.z);
        helper.getLevel().addFreshEntity(rider);
        boolean closedBoarded = humvee.board(rider, roof);
        hatch.setOn(true);
        boolean openingBoarded = humvee.board(rider, roof);
        for (int t = 0; t < 40; t++) {
            humvee.clipTracks().tick();
        }
        boolean openBoarded = humvee.board(rider, roof);
        PartUnit<?> seat = humvee.getOwnOperatorUnit(rider);
        humvee.discard();
        rider.discard();
        if (closedBoarded || openingBoarded || !openBoarded || seat == null || !seat.getId().equals("roof_mount")) {
            helper.fail("boarded closed " + closedBoarded + ", opening " + openingBoarded + ", open " + openBoarded
                    + " into " + (seat == null ? null : seat.getId()));
            return;
        }
        helper.succeed();
    }

    /** Rear seat -> roof seat: the switch opens the hatch, the rider moves only once its clip has run out. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void aSeatSwitchOpensTheHatchFirst(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "humvee");
        if (humvee == null) {
            return;
        }
        int roof = roofSeat(humvee);
        int rear = humvee.seats.stream().filter(seat -> seat.partUnit.getId().equals("rear_left"))
                .findFirst().orElseThrow().seatIndex;
        SwitchableUnit<?> hatch = (SwitchableUnit<?>) humvee.getPartUnit("top_hatch").orElseThrow();
        Vec3 at = humvee.position();
        ArmorStand rider = new ArmorStand(helper.getLevel(), at.x, at.y, at.z);
        rider.setInvulnerable(true);
        helper.getLevel().addFreshEntity(rider);
        String[] state = new String[1];
        // Boarded after the hull's first tick: that tick ejects riders attached before it.
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    boolean boarded = humvee.board(rider, rear);
                    boolean movedAtOnce = humvee.changeSeat(rider, roof);
                    state[0] = "boarded " + boarded + ", moved at once " + movedAtOnce + ", hatch on " + hatch.isOn()
                            + ", seat " + seatOf(humvee, rider);
                    helper.assertTrue(boarded && !movedAtOnce && hatch.isOn() && "rear_left".equals(seatOf(humvee, rider)),
                            state[0]);
                })
                .thenIdle(10)
                .thenExecute(() -> helper.assertTrue("rear_left".equals(seatOf(humvee, rider)),
                        state[0] + "; after 10 ticks " + seatOf(humvee, rider) + ", riding " + rider.getVehicle()))
                .thenWaitUntil(() -> helper.assertTrue("roof_mount".equals(seatOf(humvee, rider)),
                        "still in " + seatOf(humvee, rider)))
                .thenExecute(() -> {
                    humvee.discard();
                    rider.discard();
                })
                .thenSucceed();
    }

    private static int roofSeat(AbstractVehicle vehicle) {
        return vehicle.seats.stream().filter(seat -> seat.partUnit.getId().equals("roof_mount"))
                .findFirst().orElseThrow().seatIndex;
    }

    @Nullable
    private static String seatOf(AbstractVehicle vehicle, ArmorStand rider) {
        PartUnit<?> seat = vehicle.getOwnOperatorUnit(rider);
        return seat == null ? null : seat.getId();
    }

    /** Seat node: rear_right boarded though driver free; taken seat refused. Armour stands: FakePlayer fails the mount gate. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aSeatNodeBoardsThatSeat(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "humvee");
        if (humvee == null) {
            return;
        }
        int rear = humvee.seats.stream().filter(seat -> seat.partUnit.getId().equals("rear_right"))
                .findFirst().orElseThrow().seatIndex;
        Vec3 at = humvee.position();
        ArmorStand first = new ArmorStand(helper.getLevel(), at.x, at.y, at.z);
        ArmorStand second = new ArmorStand(helper.getLevel(), at.x, at.y, at.z);
        helper.getLevel().addFreshEntity(first);
        helper.getLevel().addFreshEntity(second);
        boolean boarded = humvee.board(first, rear);
        boolean taken = humvee.board(second, rear);
        PartUnit<?> seat = humvee.getOwnOperatorUnit(first);
        String id = seat == null ? null : seat.getId();
        boolean secondAboard = second.isPassenger();
        humvee.discard();
        first.discard();
        second.discard();
        if (!boarded || taken || !"rear_right".equals(id) || secondAboard) {
            helper.fail("boarded " + boarded + " into " + id + ", taken seat boarded " + taken);
            return;
        }
        helper.succeed();
    }

    private static FakePlayer player(GameTestHelper helper) {
        return FakePlayerFactory.getMinecraft(helper.getLevel());
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, String id) {
        ResourceLocation rl = CombinedAssault.modLocation(id);
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(rl);
        if (data.isEmpty()) {
            helper.fail(rl + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(rl);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail(rl + " could not be added to the world");
            return null;
        }
        return vehicle;
    }
}
