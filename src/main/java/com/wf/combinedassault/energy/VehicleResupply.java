package com.wf.combinedassault.energy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.all.AllConfigs;
import com.wf.combinedassault.compat.gtceu.GregTechCompat;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

/**
 * Refuelling and charging from the block a vehicle is parked on.
 *
 * <p>A tank under a fuel vehicle fills it; a Forge Energy or GregTech block under an electric one
 * charges it. Nothing pushes into an entity in either energy system, so pulling from underneath is
 * the only way a pump or a cable ever reaches a vehicle.
 */
public final class VehicleResupply {

    /** Ticks between lookups. The transfer budget scales with it, so the rate does not change. */
    public static final int INTERVAL = 10;
    /** Pump rate from a tank underneath, in mB/t. A bucket a second. */
    public static final int PUMP_MB_PER_TICK = 50;

    private VehicleResupply() {
    }

    public static void tick(AbstractVehicle vehicle) {
        if (vehicle.level().isClientSide() || vehicle.tickCount % INTERVAL != 0) {
            return;
        }
        if (AllConfigs.common != null && !AllConfigs.common.parkedResupply.get()) {
            return;
        }
        if (vehicle.rawEnergy() >= vehicle.energyInfo.capacityUnits()) {
            return;
        }
        Level level = vehicle.level();
        BlockPos below = BlockPos.containing(vehicle.position()).below();
        if (!level.isLoaded(below)) {
            return;
        }
        if (vehicle.isElectric()) {
            int budget = vehicle.energyInfo.chargeRateFe() * INTERVAL;
            IEnergyStorage source = level.getCapability(Capabilities.EnergyStorage.BLOCK, below, Direction.UP);
            int moved = pullCharge(vehicle, source, budget);
            if (moved <= 0) {
                GregTechCompat.pullFromBlock(vehicle, level, below, Direction.UP, budget);
            }
        } else {
            IFluidHandler source = level.getCapability(Capabilities.FluidHandler.BLOCK, below, Direction.UP);
            pullFuel(vehicle, source, PUMP_MB_PER_TICK * INTERVAL);
        }
    }

    /**
     * Moves up to {@code budgetFe} out of a Forge Energy source into an electric vehicle.
     *
     * @return the FE moved
     */
    public static int pullCharge(AbstractVehicle vehicle, @Nullable IEnergyStorage source, int budgetFe) {
        if (source == null || budgetFe <= 0 || !vehicle.isElectric() || !source.canExtract()) {
            return 0;
        }
        int room = vehicle.energyInfo.feCapacity() - vehicle.energyBuffer().getEnergyStored();
        int want = Math.min(room, budgetFe);
        if (want <= 0) {
            return 0;
        }
        int moved = source.extractEnergy(want, false);
        if (moved > 0) {
            vehicle.addCharge(moved);
        }
        return moved;
    }

    /**
     * Moves up to {@code budgetMb} of an acceptable fluid out of a tank into a fuel vehicle.
     *
     * @return the mB moved
     */
    public static int pullFuel(AbstractVehicle vehicle, @Nullable IFluidHandler source, int budgetMb) {
        if (source == null || budgetMb <= 0 || vehicle.isElectric()) {
            return 0;
        }
        VehicleFuelTank tank = vehicle.fuelTank();
        Fluid wanted = vehicle.fuelFluid();
        FluidStack available;
        if (wanted != Fluids.EMPTY && tank.storedMb() > 0) {
            // Already holding something: only ask for more of it, so a pump cannot mix the tank.
            available = source.drain(new FluidStack(wanted, budgetMb), IFluidHandler.FluidAction.SIMULATE);
        } else {
            available = source.drain(budgetMb, IFluidHandler.FluidAction.SIMULATE);
        }
        if (available.isEmpty() || !tank.canAccept(available.getFluid())) {
            return 0;
        }
        int room = tank.fill(available, IFluidHandler.FluidAction.SIMULATE);
        if (room <= 0) {
            return 0;
        }
        FluidStack drained = source.drain(available.copyWithAmount(room), IFluidHandler.FluidAction.EXECUTE);
        if (drained.isEmpty()) {
            return 0;
        }
        return tank.fill(drained, IFluidHandler.FluidAction.EXECUTE);
    }

}
