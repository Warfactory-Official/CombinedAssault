package com.wf.combinedassault.hud;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Server-unsafe half of {@link HudSystem}. */
final class HudClient {

    private HudClient() {
    }

    static void start(IEventBus modBus) {
        modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(CaHudSource::register));
        NeoForge.EVENT_BUS.register(OverlayHandler.class);
        NeoForge.EVENT_BUS.register(AimPoint.class);
        NeoForge.EVENT_BUS.register(ArmorDebugText.class);
        NeoForge.EVENT_BUS.register(FirstPersonHandler.class);
        NeoForge.EVENT_BUS.register(LocalVehiclePlayerEvent.class);
    }
}
