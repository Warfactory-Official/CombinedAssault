package com.wf.combinedassault.cargo;

import net.minecraft.world.item.ItemStack;
import com.wf.combinedassault.vehicle.entity.ContainerCraft;

/** Hold mass ({@link ItemMass}), recounted on dirty + {@link #RECOUNT_INTERVAL} throttle. */
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
                total += ItemMass.kilograms(stack);
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
