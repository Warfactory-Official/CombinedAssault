package com.wf.combinedassault.hud;

import java.util.Set;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import com.wf.combinedassault.CombinedAssaultSystem;

/** Client-only: vehicle HUD sources, overlays, first-person view handling. */
public final class HudSystem implements CombinedAssaultSystem {

    @Override
    public String id() {
        return "hud";
    }

    @Override
    public boolean mandatory() {
        return true;
    }

    @Override
    public Set<String> requires() {
        return Set.of("wflib:magazine", "wflib:armor");
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
    }

    @Override
    public void registerClient(IEventBus modBus) {
        modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(CaHudSource::register));
        NeoForge.EVENT_BUS.register(OverlayHandler.class);
        NeoForge.EVENT_BUS.register(AimPoint.class);
        NeoForge.EVENT_BUS.register(ArmorDebugText.class);
        NeoForge.EVENT_BUS.register(FirstPersonHandler.class);
        NeoForge.EVENT_BUS.register(LocalVehiclePlayerEvent.class);
    }
}
