package com.wf.combinedassault.all;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.fluids.SimpleFluidContent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.wf.combinedassault.CombinedAssault;

public class AllDataComponents {

    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS = DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, CombinedAssault.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<SimpleFluidContent>> FLUID = DATA_COMPONENTS
            .register("fluid", () -> DataComponentType.<SimpleFluidContent>builder().persistent(SimpleFluidContent.CODEC).build());

    /** {@code hardware} item: its kit id. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> HARDWARE_KIT = DATA_COMPONENTS
            .register("hardware_kit", () -> DataComponentType.<ResourceLocation>builder().persistent(ResourceLocation.CODEC)
                    .networkSynchronized(ResourceLocation.STREAM_CODEC).build());

    public static void register(IEventBus eventBus) {
        DATA_COMPONENTS.register(eventBus);
    }

}
