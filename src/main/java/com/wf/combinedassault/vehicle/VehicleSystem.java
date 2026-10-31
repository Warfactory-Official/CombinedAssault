package com.wf.combinedassault.vehicle;

import com.wf.combinedassault.vehicle.pose.gametest.SeatPoseGameTest;
import com.wf.combinedassault.vehicle.part.gametest.PickupGameTest;
import com.wf.combinedassault.vehicle.gametest.WreckGameTest;
import com.wf.combinedassault.vehicle.part.gametest.AccessGameTest;
import com.wf.combinedassault.vehicle.gametest.GameTestSetup;
import java.util.Set;
import com.wf.combinedassault.vehicle.audio.VehicleSoundEvents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.command.RootCommand;
import com.wf.combinedassault.vehicle.command.SpawnCommand;
import com.wf.combinedassault.vehicle.command.SummonCommand;
import com.wf.combinedassault.vehicle.compat.CreateCompat;
import com.wf.combinedassault.vehicle.compat.sable.SableCompat;
import com.wf.combinedassault.vehicle.control.InputHandler;
import com.wf.combinedassault.vehicle.crew.CrewCommand;
import com.wf.combinedassault.vehicle.light.IrSightMessage;
import com.wf.combinedassault.vehicle.light.LightToggleMessage;
import com.wf.combinedassault.vehicle.message.ClientVehicleAction;
import com.wf.combinedassault.vehicle.message.ClientVehicleChangeDisplay;
import com.wf.combinedassault.vehicle.message.ClientVehicleChangeSeat;
import com.wf.combinedassault.vehicle.message.ClientVehicleMoveControl;
import com.wf.combinedassault.vehicle.message.ServerEntityDataUpdate;
import com.wf.combinedassault.vehicle.message.ServerVehicleChangeDisplay;
import com.wf.combinedassault.vehicle.message.ServerVehicleSeatsChange;
import com.wf.combinedassault.vehicle.part.AccessActions;
import com.wf.combinedassault.vehicle.pose.SeatPoses;
import com.wf.combinedassault.CombinedAssaultSystem;

public final class VehicleSystem implements CombinedAssaultSystem {

    @Override
    public String id() {
        return "vehicle";
    }

    @Override
    public boolean mandatory() {
        return true;
    }

    @Override
    public Set<String> requires() {
        return Set.of("wflib:physics", "wflib:armor", "wflib:probe", "wflib:laser", "wflib:drone", "wflib:nvg");
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
        GameTestSetup.testsNeeding(modBus, java.util.Set.of("naval"), SeatPoseGameTest.class);
        GameTestSetup.testsNeeding(modBus, java.util.Set.of("aircraft", "uav"), PickupGameTest.class);
        GameTestSetup.testsNeeding(modBus, java.util.Set.of("aircraft"), WreckGameTest.class);
        GameTestSetup.testsNeeding(modBus, java.util.Set.of("aircraft", "cargo"), AccessGameTest.class);
        VehicleContent.ITEMS.register(modBus);
        VehicleContent.ENTITIES.register(modBus);
        VehicleContent.RECIPE_SERIALIZERS.register(modBus);
        VehicleSoundEvents.register(modBus);
        container.registerConfig(ModConfig.Type.COMMON, VehicleConfig.COMMON_SPEC, "combinedassault-vehicle.toml");
        container.registerConfig(ModConfig.Type.SERVER, VehicleConfig.SERVER_SPEC, "combinedassault-vehicle-server.toml");
        modBus.addListener(ModConfigEvent.Loading.class, VehicleConfig::onLoad);
        modBus.addListener(ModConfigEvent.Reloading.class, VehicleConfig::onLoad);
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            PayloadRegistrar registrar = event.registrar("vehicle-1");
            registrar.playToServer(ClientVehicleMoveControl.TYPE, ClientVehicleMoveControl.STREAM_CODEC, ClientVehicleMoveControl::handle);
            registrar.playToServer(ClientVehicleAction.TYPE, ClientVehicleAction.STREAM_CODEC, ClientVehicleAction::handle);
            registrar.playToClient(ServerVehicleSeatsChange.TYPE, ServerVehicleSeatsChange.STREAM_CODEC, ServerVehicleSeatsChange::handle);
            registrar.playToServer(ClientVehicleChangeSeat.TYPE, ClientVehicleChangeSeat.STREAM_CODEC, ClientVehicleChangeSeat::handle);
            registrar.playToClient(ServerEntityDataUpdate.TYPE, ServerEntityDataUpdate.STREAM_CODEC, ServerEntityDataUpdate::handle);
            registrar.playToClient(ServerVehicleChangeDisplay.TYPE, ServerVehicleChangeDisplay.STREAM_CODEC, ServerVehicleChangeDisplay::handle);
            registrar.playToServer(ClientVehicleChangeDisplay.TYPE, ClientVehicleChangeDisplay.STREAM_CODEC, ClientVehicleChangeDisplay::handle);
            registrar.playToServer(LightToggleMessage.TYPE, LightToggleMessage.STREAM_CODEC, LightToggleMessage::handle);
            registrar.playToServer(IrSightMessage.TYPE, IrSightMessage.STREAM_CODEC, IrSightMessage::handle);
        });
        RootCommand.SUBCOMMANDS.register(CombinedAssault.modLocation("summon"), 0, SummonCommand::get);
        RootCommand.SUBCOMMANDS.register(CombinedAssault.modLocation("spawn"), 0, SpawnCommand::get);
        RootCommand.SUBCOMMANDS.register(CombinedAssault.modLocation("crew"), 0, CrewCommand::get);
        NeoForge.EVENT_BUS.register(VehicleEvents.class);
        AccessActions.register();
        SeatPoses.register();
        SableCompat.init(modBus);
        CreateCompat.init();
    }

    @Override
    public void registerClient(IEventBus modBus) {
        VehicleClient.start(modBus);
    }
}
