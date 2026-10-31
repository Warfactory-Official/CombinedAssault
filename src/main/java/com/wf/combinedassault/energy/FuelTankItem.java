package com.wf.combinedassault.energy;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidHandlerItemStack;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.item.VehicleItem;

public class FuelTankItem extends VehicleItem {

    public FuelTankItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactEntity(ItemStack stack, Player player, Entity target, InteractionHand hand) {
        if (!player.level().isClientSide && hand == InteractionHand.MAIN_HAND && target instanceof AbstractVehicle vehicle) {
            if (VehicleRefuelInteraction.handle(vehicle, player, hand, stack)) {
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }

    public static class FuelTankFluidHandler extends FluidHandlerItemStack {

        private final int capacity;

        public FuelTankFluidHandler(ItemStack container, int capacity) {
            super(EnergyContent.FLUID, container, capacity);
            this.capacity = capacity;
            if (getFluid().isEmpty()) {
                // Cans from before the fluid component existed (plus freshly crafted ones) carry their level
                // only in the damage bar; backfill from damage so they don't start/stay empty.
                int level = capacity - container.getDamageValue();
                if (level > 0) {
                    setFluid(new FluidStack(EnergyFluids.FUEL_SOURCE.get(), Math.min(level, capacity)));
                }
            }
        }

        @Override
        public boolean canFillFluidType(FluidStack fluid) {
            ResourceLocation key = BuiltInRegistries.FLUID.getKey(fluid.getFluid());
            if (key == null) {
                return false;
            }
            String id = key.toString();
            return EnergyConfig.get().fuelNameWhiteList()
                    .stream()
                    .anyMatch(id::contains);
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            int filled = super.fill(resource, action);
            if (action.execute()) syncDamageWithFluid();
            return filled;
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            FluidStack drained = super.drain(maxDrain, action);
            if (action.execute()) syncDamageWithFluid();
            return drained;
        }

        private void syncDamageWithFluid() {
            if (container.isEmpty() || !container.isDamageableItem()) {
                return;
            }
            int maxDamage = container.getMaxDamage();
            float ratio = (float) getFluid().getAmount() / capacity;
            int damage = Math.round((1.0f - ratio) * maxDamage);
            container.setDamageValue(Math.clamp(damage, 0, maxDamage));
        }

    }

}
