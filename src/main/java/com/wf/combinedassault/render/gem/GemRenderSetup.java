package com.wf.combinedassault.render.gem;

import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.Consumer;

@OnlyIn(Dist.CLIENT)
public final class GemRenderSetup {

    private GemRenderSetup() {
    }

    public static void init() {
        VehicleGemVisual.register();
        com.wf.combinedassault.vehicle.light.client.VehicleLights.register();
    }

    public static void registerListeners(Consumer<PreparableReloadListener> consumer) {
        consumer.accept(VehiclePaints.INSTANCE);
    }
}
