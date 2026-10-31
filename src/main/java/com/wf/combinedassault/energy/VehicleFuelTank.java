package com.wf.combinedassault.energy;

import net.minecraft.util.Mth;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.energy.pojo.EnergyInfo;

/**
 * A vehicle's fuel tank as a Forge fluid handler.
 *
 * <p>Holds one fluid at a time. The stored amount is a view of the vehicle's energy rather than a
 * second copy of it, so anything that burned fuel before still does, and the fluid the tank is
 * holding is the only extra state.
 *
 * <p>No mixing: while the tank has anything in it, only more of the same goes in. Running it dry
 * clears the fluid, which is what lets a vehicle change fuels without a drain.
 */
public class VehicleFuelTank implements IFluidHandler {

    private final AbstractVehicle vehicle;

    public VehicleFuelTank(AbstractVehicle vehicle) {
        this.vehicle = vehicle;
    }

    @Override
    public int getTanks() {
        return 1;
    }

    @Override
    public @NotNull FluidStack getFluidInTank(int tank) {
        int stored = storedMb();
        Fluid fluid = vehicle.fuelFluid();
        return stored <= 0 || fluid == Fluids.EMPTY ? FluidStack.EMPTY : new FluidStack(fluid, stored);
    }

    @Override
    public int getTankCapacity(int tank) {
        return vehicle.energyInfo.tankCapacityMb();
    }

    @Override
    public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
        return canAccept(stack.getFluid());
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource.isEmpty() || !canAccept(resource.getFluid())) {
            return 0;
        }
        int room = getTankCapacity(0) - storedMb();
        int filled = Math.min(room, resource.getAmount());
        if (filled <= 0) {
            return 0;
        }
        if (action.execute()) {
            vehicle.setFuelFluid(resource.getFluid());
            vehicle.setEnergy(vehicle.rawEnergy() + (float) filled / EnergyInfo.MB_PER_UNIT);
        }
        return filled;
    }

    @Override
    public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource.isEmpty() || !resource.is(vehicle.fuelFluid())) {
            return FluidStack.EMPTY;
        }
        return drain(resource.getAmount(), action);
    }

    @Override
    public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
        Fluid fluid = vehicle.fuelFluid();
        int drained = Math.min(maxDrain, storedMb());
        if (drained <= 0 || fluid == Fluids.EMPTY) {
            return FluidStack.EMPTY;
        }
        if (action.execute()) {
            float left = vehicle.rawEnergy() - (float) drained / EnergyInfo.MB_PER_UNIT;
            vehicle.setEnergy(Math.max(0, left));
            if (vehicle.rawEnergy() <= 0) {
                vehicle.setFuelFluid(Fluids.EMPTY);
            }
        }
        return new FluidStack(fluid, drained);
    }

    /** Whether this fluid can go in right now: burnable, and not on top of a different one. */
    public boolean canAccept(Fluid fluid) {
        if (vehicle.isElectric() || !vehicle.fuelSpec().accepts(fluid)) {
            return false;
        }
        Fluid held = vehicle.fuelFluid();
        return storedMb() <= 0 || held == Fluids.EMPTY || held == fluid;
    }

    /** True when the only thing wrong with this fluid is that something else is already in there. */
    public boolean wouldMix(Fluid fluid) {
        return !vehicle.isElectric()
                && vehicle.fuelSpec().accepts(fluid)
                && storedMb() > 0
                && vehicle.fuelFluid() != Fluids.EMPTY
                && vehicle.fuelFluid() != fluid;
    }

    public int storedMb() {
        return Mth.clamp(Mth.floor(vehicle.rawEnergy() * EnergyInfo.MB_PER_UNIT), 0, getTankCapacity(0));
    }

}
