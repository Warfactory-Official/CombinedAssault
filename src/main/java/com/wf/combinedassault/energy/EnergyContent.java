package com.wf.combinedassault.energy;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.fluids.SimpleFluidContent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.wf.combinedassault.CombinedAssault;

public final class EnergyContent {

    static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, CombinedAssault.MOD_ID);

    static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS = DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, CombinedAssault.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<SimpleFluidContent>> FLUID = DATA_COMPONENTS
            .register("fluid", () -> DataComponentType.<SimpleFluidContent>builder().persistent(SimpleFluidContent.CODEC).build());

    public static final DeferredHolder<Item, Item> FUEL_TANK = ITEMS.register("fuel_tank",
            () -> new FuelTankItem(new Item.Properties().durability(125)));

    private EnergyContent() {
    }
}
