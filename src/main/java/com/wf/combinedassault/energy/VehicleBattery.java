package com.wf.combinedassault.energy;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import com.wf.combinedassault.compat.gtceu.GregTechCompat;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

/**
 * A vehicle's swappable battery bay: one FE or GT battery item, physically in the slot, draining into
 * the tank over time instead of dumping all at once. Type-agnostic ({@link EnergyInfo#energyType}):
 * credited through {@link AbstractVehicle#addEnergy}, the same path a fuel vehicle's tank uses, so a
 * drone declared {@code "fuel"} (pack doc §10.6) can still carry a battery.
 */
public final class VehicleBattery {

    private VehicleBattery() {
    }

    /** @return whether {@code stack} is anything this bay would accept. */
    public static boolean isBattery(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        IEnergyStorage fe = stack.getCapability(Capabilities.EnergyStorage.ITEM);
        return fe != null && fe.canExtract() || GregTechCompat.isElectricItem(stack);
    }

    public static void tick(AbstractVehicle vehicle) {
        if (vehicle.level().isClientSide() || !vehicle.hasBatterySlot()
                || vehicle.tickCount % VehicleResupply.INTERVAL != 0) {
            return;
        }
        ItemStack battery = vehicle.batteryItem();
        if (battery.isEmpty()) {
            return;
        }
        float roomUnits = vehicle.energyInfo.capacityUnits() - vehicle.rawEnergy();
        if (roomUnits <= 0) {
            return;
        }
        int roomFe = (int) Math.min(Integer.MAX_VALUE, (long) (roomUnits * vehicle.energyInfo.fePerUnit()));
        int budgetFe = Math.min(vehicle.energyInfo.chargeRateFe() * VehicleResupply.INTERVAL, roomFe);
        int moved = drain(battery, budgetFe);
        if (moved > 0) {
            vehicle.addEnergy((float) moved / vehicle.energyInfo.fePerUnit());
        }
    }

    private static int drain(ItemStack battery, int budgetFe) {
        IEnergyStorage fe = battery.getCapability(Capabilities.EnergyStorage.ITEM);
        if (fe != null && fe.canExtract()) {
            int moved = fe.extractEnergy(budgetFe, false);
            if (moved > 0) {
                return moved;
            }
        }
        return GregTechCompat.drainItem(battery, budgetFe);
    }

}
