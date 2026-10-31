package com.wf.combinedassault.energy;

import net.neoforged.neoforge.energy.IEnergyStorage;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

/**
 * An electric vehicle's battery as Forge Energy.
 *
 * <p>Like the fuel tank this is a view of the vehicle's energy, converted at {@code fe_per_unit}, so
 * consumption, the fuel gauge and mass all keep working unchanged. Receive only: a charger can fill a
 * vehicle, but a cable cannot quietly drain one parked next to it.
 */
public class VehicleEnergyBuffer implements IEnergyStorage {

    private final AbstractVehicle vehicle;

    public VehicleEnergyBuffer(AbstractVehicle vehicle) {
        this.vehicle = vehicle;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        if (!vehicle.isElectric() || maxReceive <= 0) {
            return 0;
        }
        int room = getMaxEnergyStored() - getEnergyStored();
        int accepted = Math.min(Math.min(maxReceive, room), vehicle.energyInfo.chargeRateFe());
        if (accepted <= 0) {
            return 0;
        }
        if (!simulate) {
            vehicle.setEnergy(vehicle.rawEnergy() + (float) accepted / vehicle.energyInfo.fePerUnit());
        }
        return accepted;
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        return 0;
    }

    @Override
    public int getEnergyStored() {
        if (!vehicle.isElectric()) {
            return 0;
        }
        long stored = (long) (vehicle.rawEnergy() * vehicle.energyInfo.fePerUnit());
        return (int) Math.max(0, Math.min(getMaxEnergyStored(), stored));
    }

    @Override
    public int getMaxEnergyStored() {
        return vehicle.isElectric() ? vehicle.energyInfo.feCapacity() : 0;
    }

    @Override
    public boolean canExtract() {
        return false;
    }

    @Override
    public boolean canReceive() {
        return vehicle.isElectric();
    }

}
