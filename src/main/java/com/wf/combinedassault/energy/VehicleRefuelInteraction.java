package com.wf.combinedassault.energy;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidActionResult;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import com.wf.combinedassault.compat.gtceu.GregTechCompat;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

/**
 * Refuelling and charging by hand.
 *
 * <p>Click with a full container to fuel, sneak-click with any container to draw fuel back out, click
 * with a battery to charge. A refusal is answered in the action bar and eats the click, because
 * silently boarding the vehicle instead is the one outcome nobody meant.
 */
public final class VehicleRefuelInteraction {

    private VehicleRefuelInteraction() {
    }

    /** @return whether the click was consumed */
    public static boolean handle(AbstractVehicle vehicle, Player player, InteractionHand hand, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return vehicle.isElectric()
                ? charge(vehicle, player, stack)
                : fuel(vehicle, player, hand, stack);
    }

    private static boolean charge(AbstractVehicle vehicle, Player player, ItemStack stack) {
        int room = vehicle.energyInfo.feCapacity() - vehicle.energyBuffer().getEnergyStored();
        int moved = 0;
        boolean isBattery = false;
        IEnergyStorage battery = stack.getCapability(Capabilities.EnergyStorage.ITEM);
        if (battery != null && battery.canExtract()) {
            isBattery = true;
            if (room > 0) {
                moved = vehicle.addCharge(battery.extractEnergy(room, false));
            }
        }
        if (moved == 0 && GregTechCompat.isAvailable()) {
            int fromEu = GregTechCompat.dischargeHeldItem(vehicle, stack, Math.max(room, 0));
            if (fromEu > 0) {
                isBattery = true;
                moved = fromEu;
            }
        }
        if (moved > 0) {
            player.displayClientMessage(Component.translatable("message.combinedassault.charge.transferred", moved), true);
            return true;
        }
        if (isBattery && room <= 0) {
            player.displayClientMessage(Component.translatable("message.combinedassault.charge.full"), true);
            return true;
        }
        // An electric vehicle cannot be fuelled, and a player holding a bucket deserves to be told
        // that rather than to find themselves in the driver's seat.
        if (!FluidUtil.getFluidContained(stack).orElse(FluidStack.EMPTY).isEmpty()) {
            player.displayClientMessage(Component.translatable("message.combinedassault.fuel.electric"), true);
            return true;
        }
        return false;
    }

    private static boolean fuel(AbstractVehicle vehicle, Player player, InteractionHand hand, ItemStack stack) {
        VehicleFuelTank tank = vehicle.fuelTank();
        IItemHandler inventory = new InvWrapper(player.getInventory());
        if (player.isShiftKeyDown()) {
            FluidActionResult result = FluidUtil.tryFillContainerAndStow(
                    stack, tank, inventory, Integer.MAX_VALUE, player, true);
            if (result.isSuccess()) {
                player.setItemInHand(hand, result.getResult());
                return true;
            }
            return false;
        }
        FluidStack held = FluidUtil.getFluidContained(stack).orElse(FluidStack.EMPTY);
        if (held.isEmpty()) {
            return false;
        }
        if (!vehicle.fuelSpec().accepts(held.getFluid())) {
            player.displayClientMessage(Component.translatable("message.combinedassault.fuel.rejected",
                    held.getHoverName(), String.join(", ", vehicle.fuelSpec().describe())), true);
            return true;
        }
        if (tank.wouldMix(held.getFluid())) {
            player.displayClientMessage(Component.translatable("message.combinedassault.fuel.mixed",
                    tank.getFluidInTank(0).getHoverName(), held.getHoverName()), true);
            return true;
        }
        FluidActionResult result = FluidUtil.tryEmptyContainerAndStow(
                stack, tank, inventory, Integer.MAX_VALUE, player, true);
        if (result.isSuccess()) {
            player.setItemInHand(hand, result.getResult());
            return true;
        }
        player.displayClientMessage(Component.translatable("message.combinedassault.fuel.full"), true);
        return true;
    }

}
