package com.wf.combinedassault.weapon.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.dynamics.part.PartClipTracks;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.AccessActions;
import com.wf.combinedassault.vehicle.part.SwitchableUnit;
import com.wf.combinedassault.weapon.kit.Hardpoints;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/** Heli door guns: hardpoint kits behind a {@code door}; no fit while shut, no close while fitted. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class HeliDoorGunGameTest {

    private static final String TEMPLATE = "empty";

    /** vehicle, hardpoint part, door part, kit fitted at spawn. */
    private static final List<String[]> GUNS = List.of(
            new String[]{"mh60l", "door_gun_r", "gun_window_r", "m134"},
            new String[]{"mh60l", "door_gun_l", "gun_window_l", "m134"},
            new String[]{"w3", "door_gun", "cabin_door_r", "pkm"},
            new String[]{"ch47", "door_gun_r", "gun_window_r", "m134"},
            new String[]{"ch47", "door_gun_l", "gun_window_l", "m134"},
            new String[]{"ch47", "ramp_gun", "cargo_doors", "m2"});

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void eachDoorGunSpawnsFittedBehindAnOpenDoor(GameTestHelper helper) {
        for (String[] g : GUNS) {
            AbstractVehicle heli = spawn(helper, g[0]);
            if (heli == null) {
                return;
            }
            WeaponUnit unit = (WeaponUnit) heli.getPartUnit(g[1]).orElseThrow();
            SwitchableUnit<?> door = (SwitchableUnit<?>) heli.getPartUnit(g[2]).orElseThrow();
            ResourceLocation kit = unit.kit();
            boolean doorOpen = door.isOn();
            boolean linked = unit.hardpointDoor() == door;
            boolean shuts = heli.mayClose(door, null);
            ResourceLocation gun = unit.currentWeaponId();
            heli.discard();
            if (!CombinedAssault.modLocation(g[3]).equals(kit) || !doorOpen || !linked || shuts || gun == null) {
                helper.fail(String.join("/", g) + ": kit " + kit + ", door open " + doorOpen + ", linked " + linked
                        + ", may close " + shuts + ", gun " + gun);
                return;
            }
        }
        helper.succeed();
    }

    /** Fitted: toggle refused (door stays open). Off: closes; shut => fit refused {@code door_shut}; reopened => fits. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aDoorAndItsGunInterlock(GameTestHelper helper) {
        for (String[] g : GUNS) {
            AbstractVehicle heli = spawn(helper, g[0]);
            if (heli == null) {
                return;
            }
            WeaponUnit unit = (WeaponUnit) heli.getPartUnit(g[1]).orElseThrow();
            SwitchableUnit<?> door = (SwitchableUnit<?>) heli.getPartUnit(g[2]).orElseThrow();
            PartClipTracks tracks = heli.clipTracks();
            tracks.tick();
            ServerPlayer player = FakePlayerFactory.getMinecraft(helper.getLevel());
            player.setGameMode(GameType.CREATIVE);
            Vec3 pivot = unit.worldPivotPosition();
            Vec3 out = new Vec3(pivot.x - heli.position().x, 0, pivot.z - heli.position().z).normalize();
            boolean stayedOpen = !AccessActions.toggle(player, heli, door.getIndex()) && door.isOn();
            unit.fitKit(-1);
            boolean closed = AccessActions.toggle(player, heli, door.getIndex()) && !door.isOn();
            for (int t = 0; t < 60; t++) {
                tracks.tick();
            }
            int kit = unit.hardpoint().indexOf(CombinedAssault.modLocation(g[3]));
            player.moveTo(pivot.x + out.x * 1.2, pivot.y + 0.3 - player.getEyeHeight(), pivot.z + out.z * 1.2);
            Component shut = Hardpoints.fitRefusal(player, unit, kit);
            door.setOn(true);
            for (int t = 0; t < 60; t++) {
                tracks.tick();
            }
            Component open = Hardpoints.fitRefusal(player, unit, kit);
            heli.discard();
            if (!stayedOpen || !closed || !key(shut, "hardpoint.combinedassault.door_shut") || key(open, "hardpoint.combinedassault.door_shut")) {
                helper.fail(String.join("/", g) + ": stayed open " + stayedOpen + ", closed " + closed + ", shut refusal "
                        + key(shut) + ", open refusal " + key(open));
                return;
            }
        }
        helper.succeed();
    }

    /** Pilot boarding closes the doors, not one holding a gun open. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void pilotBoardingLeavesAGunDoorOpen(GameTestHelper helper) {
        AbstractVehicle heli = spawn(helper, "w3");
        if (heli == null) {
            return;
        }
        helper.runAfterDelay(5, () -> {
            SwitchableUnit<?> gunDoor = (SwitchableUnit<?>) heli.getPartUnit("cabin_door_r").orElseThrow();
            SwitchableUnit<?> other = (SwitchableUnit<?>) heli.getPartUnit("cabin_door_l").orElseThrow();
            other.setOn(true);
            ArmorStand pilot = new ArmorStand(helper.getLevel(), heli.getX(), heli.getY(), heli.getZ());
            helper.getLevel().addFreshEntity(pilot);
            boolean boarded = heli.board(pilot, 0);
            boolean gunDoorOpen = gunDoor.isOn();
            boolean otherClosed = !other.isOn();
            heli.discard();
            pilot.discard();
            if (!boarded || !gunDoorOpen || !otherClosed) {
                helper.fail("boarded " + boarded + ", gun door open " + gunDoorOpen + ", other closed " + otherClosed);
                return;
            }
            helper.succeed();
        });
    }

    /** m134.glb socket_bolt_0: breech 0.132 ahead of the pitch pivot, barrel 0.572. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void theMinigunMuzzleIsAtItsBarrelTip(GameTestHelper helper) {
        AbstractVehicle heli = spawn(helper, "mh60l");
        if (heli == null) {
            return;
        }
        WeaponUnit unit = (WeaponUnit) heli.getPartUnit("door_gun_r").orElseThrow();
        double reach = unit.aimContext().from.distanceTo(unit.worldPivotPosition());
        heli.discard();
        helper.assertTrue(Math.abs(reach - 0.704) < 1e-3, "muzzle " + reach + " from the pivot, want 0.704");
        helper.succeed();
    }

    private static boolean key(@Nullable Component c, String key) {
        return c != null && c.getContents() instanceof TranslatableContents t && t.getKey().equals(key);
    }

    private static String key(@Nullable Component c) {
        return c == null ? "none" : c.getContents() instanceof TranslatableContents t ? t.getKey() : c.getString();
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, String id) {
        ResourceLocation rl = CombinedAssault.modLocation(id);
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(rl);
        if (data.isEmpty()) {
            helper.fail(id + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(rl);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail(id + " could not be added to the world");
            return null;
        }
        return vehicle;
    }
}
