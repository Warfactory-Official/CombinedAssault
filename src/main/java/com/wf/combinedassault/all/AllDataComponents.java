package com.wf.combinedassault.all;

import com.wf.wflib.drone.cam.CameraChannels;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.fluids.SimpleFluidContent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.wf.combinedassault.CombinedAssault;

public class AllDataComponents {

    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS = DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, CombinedAssault.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<SimpleFluidContent>> FLUID = DATA_COMPONENTS
            .register("fluid", () -> DataComponentType.<SimpleFluidContent>builder().persistent(SimpleFluidContent.CODEC).build());

    /** UAVs linked to a wflib PDA (wflib camera {@code CHANNELS} stays the camera list). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CameraChannels>> UAV_LINKS = DATA_COMPONENTS
            .register("uav_links", () -> DataComponentType.<CameraChannels>builder().persistent(CameraChannels.CODEC)
                    .networkSynchronized(CameraChannels.STREAM_CODEC).build());

    public static void register(IEventBus eventBus) {
        DATA_COMPONENTS.register(eventBus);
    }

}
