package com.wf.combinedassault.energy.gametest;

import com.mojang.authlib.GameProfile;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.compat.gtceu.GregTechCompat;
import com.wf.combinedassault.energy.VehicleBattery;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.gametest.TestLanes;
import com.wf.combinedassault.vehicle.part.BatteryActions;
import com.wf.combinedassault.vehicle.pojo.EnergyInfo;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The battery bay: a slot distinct from the generic hold, type-agnostic ({@link EnergyInfo#batterySlot}),
 * that only ever holds what it was given back.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class VehicleBatteryGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation HUMVEE = CombinedAssault.modLocation("humvee");

    /** A fresh {@link EnergyInfo}, never the pack's shared one: mutating that would leak into every other humvee. */
    private static AbstractVehicle humvee(GameTestHelper helper, boolean batterySlot) {
        AbstractVehicle vehicle = TestLanes.spawn(helper, HUMVEE, helper.absoluteVec(new Vec3(1.5, 2, 1.5)));
        EnergyInfo info = new EnergyInfo();
        info.batterySlot = batterySlot;
        vehicle.setEnergyInfo(info);
        return vehicle;
    }

    private static ServerPlayer player(GameTestHelper helper, AbstractVehicle vehicle) {
        String name = "ca_battery_" + vehicle.getUUID().toString().substring(0, 8);
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        Vec3 at = vehicle.position().add(1.5, 0, 0);
        player.moveTo(at.x, at.y, at.z);
        return player;
    }

    /** A pack that never declared a battery slot keeps refusing one, so an existing vehicle's behaviour cannot drift. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aVehicleWithoutABatterySlotRefusesOne(GameTestHelper helper) {
        AbstractVehicle vehicle = humvee(helper, false);
        helper.runAfterDelay(2, () -> {
            ServerPlayer player = player(helper, vehicle);
            player.getInventory().add(new ItemStack(Items.IRON_INGOT));
            boolean loaded = BatteryActions.load(player, vehicle);
            player.getInventory().clearContent();
            vehicle.discard();
            helper.assertTrue(!loaded && vehicle.batteryItem().isEmpty(), "loaded " + loaded);
            helper.succeed();
        });
    }

    /** Neither GT nor a plain FE capability on it: junk never earns a seat in the bay. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void onlyARealBatteryFitsTheSlot(GameTestHelper helper) {
        AbstractVehicle vehicle = humvee(helper, true);
        helper.runAfterDelay(2, () -> {
            ServerPlayer player = player(helper, vehicle);
            player.getInventory().add(new ItemStack(Items.IRON_INGOT));
            boolean loaded = BatteryActions.load(player, vehicle);
            int kept = player.getInventory().countItem(Items.IRON_INGOT);
            player.getInventory().clearContent();
            vehicle.discard();
            helper.assertTrue(!loaded && vehicle.batteryItem().isEmpty() && kept == 1,
                    "loaded " + loaded + ", kept " + kept);
            helper.succeed();
        });
    }

    /** Taking the bay's battery back hands over the exact stack, slot and all, and empties the bay. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void unloadingGivesBackTheExactItem(GameTestHelper helper) {
        AbstractVehicle vehicle = humvee(helper, true);
        helper.runAfterDelay(2, () -> {
            vehicle.setBatteryItem(new ItemStack(Items.DIAMOND));
            ServerPlayer player = player(helper, vehicle);
            boolean unloaded = BatteryActions.unload(player, vehicle);
            boolean secondUnload = BatteryActions.unload(player, vehicle);
            int got = player.getInventory().countItem(Items.DIAMOND);
            player.getInventory().clearContent();
            vehicle.discard();
            helper.assertTrue(unloaded && !secondUnload && vehicle.batteryItem().isEmpty() && got == 1,
                    "unloaded " + unloaded + ", second " + secondUnload + ", got " + got);
            helper.succeed();
        });
    }

    /** Without a working FE or GT capability the tick is a silent no-op: no crash, no drift, no ejection. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aNonPoweredItemInTheBayDoesNothing(GameTestHelper helper) {
        AbstractVehicle vehicle = humvee(helper, true);
        helper.runAfterDelay(2, () -> {
            vehicle.setBatteryItem(new ItemStack(Items.REDSTONE_BLOCK));
            float before = vehicle.getEnergy();
            helper.runAfterDelay(20, () -> {
                boolean unchanged = Math.abs(vehicle.getEnergy() - before) < 1e-6;
                boolean stillSeated = !vehicle.batteryItem().isEmpty();
                vehicle.discard();
                helper.assertTrue(unchanged && stillSeated, "energy drifted by "
                        + (vehicle.getEnergy() - before) + ", seated " + stillSeated);
                helper.succeed();
            });
        });
    }

    /** What is in the bay is part of the vehicle, not of the session. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void theBatterySurvivesSaveAndReload(GameTestHelper helper) {
        AbstractVehicle vehicle = humvee(helper, true);
        helper.runAfterDelay(2, () -> {
            vehicle.setBatteryItem(new ItemStack(Items.DIAMOND));
            CompoundTag saved = new CompoundTag();
            vehicle.addAdditionalSaveData(saved);
            vehicle.discard();

            AbstractVehicle reloaded = TestLanes.spawn(helper, HUMVEE, helper.absoluteVec(new Vec3(3.5, 2, 1.5)));
            reloaded.readAdditionalSaveData(saved);
            boolean kept = reloaded.batteryItem().is(Items.DIAMOND) && reloaded.batteryItem().getCount() == 1;
            reloaded.discard();
            helper.assertTrue(kept, "reloaded bay holds " + reloaded.batteryItem());
            helper.succeed();
        });
    }

    /** Without GregTech the item-draining helpers answer nothing rather than exploding. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void gregTechHelpersAreSilentWithoutGregTech(GameTestHelper helper) {
        if (GregTechCompat.isAvailable()) {
            helper.fail("GregTech is loaded in this run, so this test proves nothing");
            return;
        }
        ItemStack battery = new ItemStack(Items.REDSTONE_BLOCK);
        boolean isElectric = GregTechCompat.isElectricItem(battery);
        int drained = GregTechCompat.drainItem(battery, 10000);
        boolean isBattery = VehicleBattery.isBattery(battery);
        helper.assertTrue(!isElectric && drained == 0 && !isBattery,
                "isElectricItem " + isElectric + ", drained " + drained + ", isBattery " + isBattery);
        helper.succeed();
    }

}
