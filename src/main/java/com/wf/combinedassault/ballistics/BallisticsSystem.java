package com.wf.combinedassault.ballistics;

import com.wf.combinedassault.ballistics.component.gametest.RotorSystemsGameTest;
import com.wf.combinedassault.vehicle.gametest.GameTestSetup;
import java.util.Set;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.VehicleTabs;
import com.wf.combinedassault.ballistics.addon.AddonActions;
import com.wf.combinedassault.ballistics.addon.AddonStrikes;
import com.wf.combinedassault.ballistics.component.ArmorRepair;
import com.wf.combinedassault.ballistics.component.SystemRepair;
import com.wf.combinedassault.ballistics.message.ServerAddonArmorSync;
import com.wf.combinedassault.ballistics.message.ServerHitEffect;
import com.wf.combinedassault.ballistics.message.ServerHitVehicleEvent;
import com.wf.combinedassault.ballistics.message.ServerVehicleExplosion;
import com.wf.combinedassault.ballistics.message.ServerVehicleHurtEntity;
import com.wf.combinedassault.ballistics.message.ServerVehicleWarn;
import com.wf.combinedassault.vehicle.command.RootCommand;
import com.wf.combinedassault.CombinedAssaultSystem;

public final class BallisticsSystem implements CombinedAssaultSystem {

    @Override
    public String id() {
        return "ballistics";
    }

    @Override
    public boolean mandatory() {
        return true;
    }

    @Override
    public Set<String> requires() {
        return Set.of("wflib:armor", "wflib:physics", "wflib:probe");
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
        GameTestSetup.testsNeeding(modBus, java.util.Set.of("aircraft"), RotorSystemsGameTest.class);
        BallisticsContent.ITEMS.register(modBus);
        VehicleTabs.addMisc(BallisticsContent.REPAIR_TOOL);
        VehicleTabs.addMisc(BallisticsContent.ERA_BRICK);
        VehicleTabs.addMisc(BallisticsContent.HEAVY_ERA_BRICK);
        VehicleTabs.addMisc(BallisticsContent.SLAT_PANEL);
        AddonActions.register();
        SystemRepair.register();
        ArmorRepair.register();
        RootCommand.SUBCOMMANDS.register(CombinedAssault.modLocation("debug"), 0, DebugCommand::get);
        RootCommand.SUBCOMMANDS.register(CombinedAssault.modLocation("ballistics"), 0, BallisticsCommand::get);
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            PayloadRegistrar registrar = event.registrar("ballistics-1");
            registrar.playToClient(ServerHitVehicleEvent.TYPE, ServerHitVehicleEvent.STREAM_CODEC, ServerHitVehicleEvent::handle);
            registrar.playToClient(ServerVehicleWarn.TYPE, ServerVehicleWarn.STREAM_CODEC, ServerVehicleWarn::handle);
            registrar.playToClient(ServerVehicleHurtEntity.TYPE, ServerVehicleHurtEntity.STREAM_CODEC, ServerVehicleHurtEntity::handle);
            registrar.playToClient(ServerVehicleExplosion.TYPE, ServerVehicleExplosion.STREAM_CODEC, ServerVehicleExplosion::handle);
            registrar.playToClient(ServerAddonArmorSync.TYPE, ServerAddonArmorSync.STREAM_CODEC, ServerAddonArmorSync::handle);
            registrar.playToClient(ServerHitEffect.TYPE, ServerHitEffect.STREAM_CODEC, ServerHitEffect::handle);
        });
        NeoForge.EVENT_BUS.register(BallisticsEvents.class);
        NeoForge.EVENT_BUS.register(ThermalAttacks.class);
        NeoForge.EVENT_BUS.register(AddonStrikes.class);
    }

    @Override
    public void registerClient(IEventBus modBus) {
        BallisticsClient.start();
    }
}
