package com.wf.combinedassault.energy;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.VehicleSprayPod;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Vehicle's {@code FluidHandler.ENTITY}: tank 0 = fuel, then one tank per {@link VehicleSprayPod}. Fuel-spec fluids
 * go to fuel first and overflow (tank full / other fuel in it) to the first pod that takes them; anything else to
 * the first pod that takes it.
 */
public class VehicleFluids implements IFluidHandler {

    private final AbstractVehicle vehicle;

    public VehicleFluids(AbstractVehicle vehicle) {
        this.vehicle = vehicle;
    }

    public List<VehicleSprayPod> pods() {
        List<VehicleSprayPod> out = new ArrayList<>();
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof WeaponUnit unit) {
                for (var weapon : unit.weapons) {
                    if (weapon instanceof VehicleSprayPod pod) {
                        out.add(pod);
                    }
                }
            }
        }
        return out;
    }

    private IFluidHandler tank(int tank) {
        return tank == 0 ? vehicle.fuelTank() : pods().get(tank - 1);
    }

    @Override
    public int getTanks() {
        return 1 + pods().size();
    }

    @Override
    public @NotNull FluidStack getFluidInTank(int tank) {
        return tank(tank).getFluidInTank(0);
    }

    @Override
    public int getTankCapacity(int tank) {
        return tank(tank).getTankCapacity(0);
    }

    @Override
    public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
        return tank(tank).isFluidValid(0, stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (vehicle.fuelSpec().accepts(resource.getFluid())) {
            int fuel = vehicle.fuelTank().fill(resource, action);
            if (fuel > 0) {
                return fuel;
            }
        }
        for (VehicleSprayPod pod : pods()) {
            int filled = pod.fill(resource, action);
            if (filled > 0) {
                return filled;
            }
        }
        return 0;
    }

    @Override
    public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
        FluidStack out = vehicle.fuelTank().drain(resource, action);
        for (VehicleSprayPod pod : pods()) {
            if (!out.isEmpty()) {
                break;
            }
            out = pod.drain(resource, action);
        }
        return out;
    }

    @Override
    public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
        FluidStack out = vehicle.fuelTank().drain(maxDrain, action);
        for (VehicleSprayPod pod : pods()) {
            if (!out.isEmpty()) {
                break;
            }
            out = pod.drain(maxDrain, action);
        }
        return out;
    }
}
