package com.wf.combinedassault.vehicle.part;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.energy.VehicleBattery;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.wflib.probe.ProbeActions;
import com.wf.wflib.probe.ProbeInventory;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Hull battery bay, on foot: swap a carried FE/GT battery item in or out of the slot. */
public final class BatteryActions {

    public static final ResourceLocation LOAD = CombinedAssault.modLocation("battery_load");
    public static final ResourceLocation UNLOAD = CombinedAssault.modLocation("battery_unload");

    private BatteryActions() {
    }

    public static void register() {
        ProbeActions.registerEntity(LOAD, (player, entity, arg) -> entity instanceof AbstractVehicle vehicle
                && load(player, vehicle));
        ProbeActions.registerEntity(UNLOAD, (player, entity, arg) -> entity instanceof AbstractVehicle vehicle
                && unload(player, vehicle));
    }

    public static boolean load(ServerPlayer player, AbstractVehicle vehicle) {
        if (!vehicle.hasBatterySlot() || vehicle.isDestroyed() || !vehicle.batteryItem().isEmpty()) {
            return false;
        }
        ItemStack found = ProbeInventory.find(player, VehicleBattery::isBattery);
        if (found.isEmpty()) {
            return false;
        }
        vehicle.setBatteryItem(found.copyWithCount(1));
        if (!player.getAbilities().instabuild) {
            found.shrink(1);
        }
        return true;
    }

    public static boolean unload(ServerPlayer player, AbstractVehicle vehicle) {
        if (vehicle.isDestroyed() || vehicle.batteryItem().isEmpty()) {
            return false;
        }
        ItemStack battery = vehicle.batteryItem();
        vehicle.setBatteryItem(ItemStack.EMPTY);
        if (!player.getInventory().add(battery)) {
            player.drop(battery, false);
        }
        return true;
    }

}
