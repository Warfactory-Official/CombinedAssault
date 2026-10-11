package com.wf.combinedassault.storage;

import net.minecraft.world.item.ItemStack;
import com.wf.combinedassault.compat.ultimateweight.UltimateWeightCompat;
import com.wf.combinedassault.entity.ContainerCraft;

/** Hold mass, recounted on dirty + {@link #RECOUNT_INTERVAL} throttle; no Ultimate Weight => always 0. */
public final class CargoMass {

    /** Ticks. */
    private static final int RECOUNT_INTERVAL = 20;

    private double kilograms;
    private boolean dirty = true;
    private int cooldown;

    /** As of the last recount. */
    public double kilograms() {
        return kilograms;
    }

    /** Cheap; call per stack moved. */
    public void markDirty() {
        dirty = true;
    }

    /** @return changed; caller rebuilds mass only then */
    public boolean tick(ContainerCraft craft) {
        if (!UltimateWeightCompat.isAvailable()) {
            // No Ultimate Weight: settle at 0, then skip.
            if (kilograms == 0) {
                return false;
            }
            kilograms = 0;
            return true;
        }
        if (!dirty) {
            return false;
        }
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        dirty = false;
        cooldown = RECOUNT_INTERVAL;
        double total = 0;
        for (ItemStack stack : craft.getItemStacks()) {
            if (!stack.isEmpty()) {
                total += UltimateWeightCompat.kilograms(stack);
            }
        }
        if (total == kilograms) {
            return false;
        }
        kilograms = total;
        return true;
    }

    /** Forces a recount next tick, bypassing the throttle. */
    public void reset() {
        kilograms = 0;
        dirty = true;
        cooldown = 0;
    }

}
