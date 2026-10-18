package com.wf.combinedassault.custom.part.data;

import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Separate glTF carried by a part, drawn and hit as a subtree of the vehicle glTF node {@code node}.
 *
 * @param kit hardware kit the hardpoint expansion made this mount for: exists only while fitted; null = always
 */
public record PartMount(ResourceLocation model, String node, @Nullable ResourceLocation kit) {

    public boolean carriedBy(PartUnit<?> part) {
        return kit == null || part instanceof WeaponUnit unit && kit.equals(unit.kit());
    }
}
