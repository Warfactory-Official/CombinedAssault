package com.wf.combinedassault.energy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.ItemCapability;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * GregTech EU -> vehicle FE. Reflection, not compile dep: GTCEu API moves per version, jar version-specific.
 * Absent GT => 0 everywhere. Routes: held battery, slotted battery ({@link VehicleBattery}),
 * or GT block below with output face up (GT has no entity push).
 */
public final class GregTechCompat {

    private static final String MOD_ID = "gtceu";
    /** Fallback EU->FE ratio; matches GTCEu's default converter. */
    private static final int DEFAULT_EU_TO_FE = 4;

    private static boolean available;
    private static ItemCapability<?, Void> electricItemCap;
    private static BlockCapability<?, Direction> energyContainerCap;
    private static MethodHandle discharge;
    private static MethodHandle getCharge;
    private static MethodHandle outputsEnergy;
    private static MethodHandle removeEnergy;
    private static MethodHandle getEnergyStored;
    private static MethodHandle ratio;

    private GregTechCompat() {
    }

    @SuppressWarnings("unchecked")
    public static void init() {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return;
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Class<?> capabilities = Class.forName("com.gregtechceu.gtceu.api.capability.GTCapability");
            Class<?> electricItem = Class.forName("com.gregtechceu.gtceu.api.capability.IElectricItem");
            Class<?> energyContainer = Class.forName("com.gregtechceu.gtceu.api.capability.IEnergyContainer");
            Class<?> feCompat = Class.forName("com.gregtechceu.gtceu.api.capability.compat.FeCompat");

            electricItemCap = (ItemCapability<?, Void>) capabilities.getField("CAPABILITY_ELECTRIC_ITEM").get(null);
            energyContainerCap = (BlockCapability<?, Direction>) capabilities.getField("CAPABILITY_ENERGY_CONTAINER").get(null);

            discharge = lookup.findVirtual(electricItem, "discharge",
                    MethodType.methodType(long.class, long.class, int.class, boolean.class, boolean.class, boolean.class));
            getCharge = lookup.findVirtual(electricItem, "getCharge", MethodType.methodType(long.class));
            outputsEnergy = lookup.findVirtual(energyContainer, "outputsEnergy",
                    MethodType.methodType(boolean.class, Direction.class));
            removeEnergy = lookup.findVirtual(energyContainer, "removeEnergy", MethodType.methodType(long.class, long.class));
            getEnergyStored = lookup.findVirtual(energyContainer, "getEnergyStored", MethodType.methodType(long.class));
            ratio = lookup.findStatic(feCompat, "ratio", MethodType.methodType(int.class, boolean.class));
            available = true;
        } catch (Throwable t) {
            CombinedAssault.LOGGER.warn("GregTech is loaded but its energy API did not bind; vehicles will not take EU", t);
            available = false;
        }
    }

    /** @return FE headroom; pull must not exceed this. */
    private static int roomFe(AbstractVehicle vehicle) {
        return Math.max(0, vehicle.energyInfo.feCapacity() - vehicle.energyBuffer().getEnergyStored());
    }

    public static boolean isAvailable() {
        return available;
    }

    /** @return EU->FE from GTCEu's own converter config. */
    public static int euToFeRatio() {
        if (!available) {
            return DEFAULT_EU_TO_FE;
        }
        try {
            int value = (int) ratio.invoke(false);
            return value > 0 ? value : DEFAULT_EU_TO_FE;
        } catch (Throwable t) {
            return DEFAULT_EU_TO_FE;
        }
    }

    /** @return FE credited. */
    public static int dischargeHeldItem(AbstractVehicle vehicle, ItemStack stack, int budgetFe) {
        if (!vehicle.isElectric()) {
            return 0;
        }
        int moved = drainItem(stack, Math.min(budgetFe, roomFe(vehicle)));
        return moved <= 0 ? 0 : vehicle.addCharge(moved);
    }

    /** @return true if {@code stack} exposes GT's electric-item capability, charged or not. */
    public static boolean isElectricItem(ItemStack stack) {
        if (!available || stack.isEmpty()) {
            return false;
        }
        try {
            return stack.getCapability(electricItemCap, null) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** @return FE pulled out of the item's own charge; nothing credited anywhere. */
    public static int drainItem(ItemStack stack, int budgetFe) {
        if (!available || stack.isEmpty() || budgetFe <= 0) {
            return 0;
        }
        try {
            Object item = stack.getCapability(electricItemCap, null);
            if (item == null || (long) getCharge.invoke(item) <= 0) {
                return 0;
            }
            int feRatio = euToFeRatio();
            long wantEu = budgetFe / feRatio;
            if (wantEu <= 0) {
                return 0;
            }
            long moved = (long) discharge.invoke(item, wantEu, Integer.MAX_VALUE, true, true, false);
            return moved <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, moved * feRatio);
        } catch (Throwable t) {
            CombinedAssault.LOGGER.warn("GregTech battery discharge failed", t);
            return 0;
        }
    }

    /** @return FE credited. */
    public static int pullFromBlock(AbstractVehicle vehicle, Level level, BlockPos pos, Direction side, int budgetFe) {
        if (!available || budgetFe <= 0 || !vehicle.isElectric()) {
            return 0;
        }
        try {
            Object container = level.getCapability(energyContainerCap, pos, side);
            // Output face only: blocks siphoning a machine's own input buffer.
            if (container == null || !(boolean) outputsEnergy.invoke(container, side)) {
                return 0;
            }
            int feRatio = euToFeRatio();
            long wantEu = Math.min(Math.min(budgetFe, roomFe(vehicle)) / feRatio,
                    (long) getEnergyStored.invoke(container));
            if (wantEu <= 0) {
                return 0;
            }
            long moved = (long) removeEnergy.invoke(container, wantEu);
            if (moved <= 0) {
                return 0;
            }
            return vehicle.addCharge((int) Math.min(Integer.MAX_VALUE, moved * feRatio));
        } catch (Throwable t) {
            CombinedAssault.LOGGER.warn("GregTech energy pull failed", t);
            return 0;
        }
    }

}
