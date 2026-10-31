package com.wf.combinedassault.weapon;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.weapon.kit.HardwareItem;

public final class WeaponContent {

    static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, CombinedAssault.MOD_ID);
    static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, CombinedAssault.MOD_ID);

    public static final DeferredHolder<Item, HardwareItem> HARDWARE = ITEMS.register("hardware", HardwareItem::new);
    public static final DeferredHolder<Item, Item> AMMO_CREATIVE =
            ITEMS.register("ammo_creative", () -> new Item(new Item.Properties().stacksTo(1)));

    /** {@code hardware} item: its kit id. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> HARDWARE_KIT = DATA_COMPONENTS
            .register("hardware_kit", () -> DataComponentType.<ResourceLocation>builder().persistent(ResourceLocation.CODEC)
                    .networkSynchronized(ResourceLocation.STREAM_CODEC).build());

    private WeaponContent() {
    }
}
