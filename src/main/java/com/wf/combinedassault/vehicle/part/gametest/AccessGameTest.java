package com.wf.combinedassault.vehicle.part.gametest;

import net.minecraft.core.BlockPos;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.MenuProvider;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.part.AccessActions;
import com.wf.combinedassault.vehicle.part.DoorUnit;
import com.wf.combinedassault.vehicle.part.PartClipTracks;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.SwitchableUnit;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class AccessGameTest {

    private static final String TEMPLATE = "empty";

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
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

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
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
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
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

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
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

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
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
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
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
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
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

    /** Seat node: rear_right boarded though driver free; taken seat refused. Armour stands: FakePlayer fails the mount gate. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
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
