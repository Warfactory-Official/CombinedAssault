package com.wf.combinedassault.radar;

import com.wf.combinedassault.pack.BuiltinPartUnits;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import com.wf.combinedassault.radar.message.ClientRadarAction;
import com.wf.combinedassault.radar.message.ServerBroadcastEntities;
import com.wf.combinedassault.CombinedAssaultSystem;

public final class RadarSystem implements CombinedAssaultSystem {

    @Override
    public String id() {
        return "radar";
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
        BuiltinPartUnits.add(RadarPartTypes.RADAR);
        RadarContent.ENTITIES.register(modBus);
        container.registerConfig(ModConfig.Type.COMMON, RadarConfig.SPEC, "combinedassault-radar.toml");
        modBus.addListener(ModConfigEvent.Loading.class, RadarConfig::onLoad);
        modBus.addListener(ModConfigEvent.Reloading.class, RadarConfig::onLoad);
        RadarConfig.loadExternal();
        NeoForge.EVENT_BUS.register(RadarBroadcast.class);
        ReconBridge.register();
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            PayloadRegistrar registrar = event.registrar("radar-1");
            registrar.playToServer(ClientRadarAction.TYPE, ClientRadarAction.STREAM_CODEC, ClientRadarAction::handle);
            registrar.playToClient(ServerBroadcastEntities.TYPE, ServerBroadcastEntities.STREAM_CODEC, ServerBroadcastEntities::handle);
        });
    }
}
