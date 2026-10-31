package com.wf.combinedassault.weapon;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import com.wf.combinedassault.weapon.client.AmmoSelect;
import com.wf.combinedassault.weapon.client.BarrelFx;
import com.wf.combinedassault.weapon.client.BarrelKeys;
import com.wf.combinedassault.weapon.client.BarrelNodes;
import com.wf.combinedassault.weapon.client.StationAds;
import com.wf.combinedassault.weapon.client.StationPip;
import com.wf.combinedassault.weapon.client.StationRecoil;

/** Server-unsafe half of {@link WeaponSystem}. */
final class WeaponClient {

    private WeaponClient() {
    }

    static void start(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, BarrelKeys::onRegisterKeys);
        modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(BarrelNodes::register));
        NeoForge.EVENT_BUS.register(BarrelKeys.class);
        NeoForge.EVENT_BUS.register(BarrelFx.class);
        NeoForge.EVENT_BUS.register(AmmoSelect.class);
        NeoForge.EVENT_BUS.register(StationAds.class);
        NeoForge.EVENT_BUS.register(StationPip.class);
        NeoForge.EVENT_BUS.register(StationRecoil.class);
    }
}
