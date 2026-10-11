package com.wf.combinedassault.custom.part.data;

import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Separate glTF carried by a part, drawn and hit as a subtree of the vehicle glTF node {@code node}.
 *
 * @param weapon one of the part's {@code weapons}: mount exists only while it is selected; null = always
 * @param name   lang key: vehicle's display name while carried; weapon mounts only
 */
public record PartMount(ResourceLocation model, String node, @Nullable ResourceLocation weapon, @Nullable String name) {

    public boolean carriedBy(PartUnit<?> part) {
        return weapon == null || part instanceof WeaponUnit unit && weapon.equals(unit.currentWeaponId());
    }
}
