package com.wf.combinedassault.camo;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import com.wf.combinedassault.vehicle.VehicleTabs;
import com.wf.combinedassault.camo.client.CamoBrush;
import com.wf.combinedassault.camo.client.VehicleCamoRenderer;
import com.wf.combinedassault.camo.message.ClientCamoAction;
import com.wf.combinedassault.camo.message.ServerCamoAction;
import com.wf.combinedassault.camo.message.ServerCamoSync;
import com.wf.combinedassault.vehicle.command.RootCommand;
import com.wf.combinedassault.CombinedAssaultSystem;

public final class CamoSystem implements CombinedAssaultSystem {

    @Override
    public String id() {
        return "camo";
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
        CamoContent.ITEMS.register(modBus);
        VehicleTabs.addMisc(CamoContent.CAMO_KIT);
        container.registerConfig(ModConfig.Type.COMMON, CamoConfig.SPEC, "combinedassault-camo.toml");
        modBus.addListener(ModConfigEvent.Loading.class, CamoConfig::onLoad);
        modBus.addListener(ModConfigEvent.Reloading.class, CamoConfig::onLoad);
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            PayloadRegistrar registrar = event.registrar("camo-1");
            registrar.playToServer(ClientCamoAction.TYPE, ClientCamoAction.STREAM_CODEC, ClientCamoAction::handle);
            registrar.playToClient(ServerCamoAction.TYPE, ServerCamoAction.STREAM_CODEC, ServerCamoAction::handle);
            registrar.playToClient(ServerCamoSync.TYPE, ServerCamoSync.STREAM_CODEC, ServerCamoSync::handle);
        });
        RootCommand.SUBCOMMANDS.register(com.wf.combinedassault.CombinedAssault.modLocation("camo"), 0, CamoCommand::get);
    }

    @Override
    public void registerClient(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(CamoBrush.class);
        NeoForge.EVENT_BUS.register(VehicleCamoRenderer.class);
    }
}
