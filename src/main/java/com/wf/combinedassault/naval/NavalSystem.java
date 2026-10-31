package com.wf.combinedassault.naval;

import com.wf.combinedassault.naval.gametest.ShipGameTest;
import com.wf.combinedassault.naval.gametest.RiverineGunsGameTest;
import com.wf.combinedassault.vehicle.gametest.GameTestSetup;
import java.util.Set;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.client.DisplayTypes;
import com.wf.combinedassault.pack.VehicleDataTypes;
import com.wf.combinedassault.pack.client.vehicle.VehicleDisplayPojo;
import com.wf.combinedassault.vehicle.command.RootCommand;
import com.wf.combinedassault.pack.serialize.GsonUtil;
import com.wf.combinedassault.naval.client.ShipVehicleDisplay;
import com.wf.combinedassault.naval.client.VesselVehicleDisplay;
import com.wf.combinedassault.naval.data.ShipVehicleData;
import com.wf.combinedassault.naval.data.ShipVehicleDataPojo;
import com.wf.combinedassault.naval.data.VesselVehicleData;
import com.wf.combinedassault.naval.data.VesselVehicleDataPojo;
import com.wf.combinedassault.naval.trial.Trials;
import com.wf.combinedassault.render.animation.context.AnimationContextFactory;
import com.wf.combinedassault.CombinedAssaultSystem;

public final class NavalSystem implements CombinedAssaultSystem {

    @Override
    public String id() {
        return "naval";
    }

    @Override
    public Set<String> requires() {
        return Set.of("wflib:physics");
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
        GameTestSetup.tests(modBus, ShipGameTest.class, RiverineGunsGameTest.class);
        NavalContent.ENTITIES.register(modBus);
        VehicleDataTypes.register("vessel_vehicle", json -> {
            VesselVehicleData data = new VesselVehicleData();
            data.build(GsonUtil.GSON.fromJson(json, VesselVehicleDataPojo.class));
            return data;
        });
        VehicleDataTypes.register("ship", json -> {
            ShipVehicleData data = new ShipVehicleData();
            data.build(GsonUtil.GSON.fromJson(json, ShipVehicleDataPojo.class));
            return data;
        });
        DisplayTypes.register("vessel_vehicle",
                json -> new VesselVehicleDisplay(GsonUtil.GSON.fromJson(json, VehicleDisplayPojo.class)),
                AnimationContextFactory.vesselVehicle());
        DisplayTypes.register("ship",
                json -> new ShipVehicleDisplay(GsonUtil.GSON.fromJson(json, VehicleDisplayPojo.class)),
                AnimationContextFactory.ship());
        RootCommand.SUBCOMMANDS.register(CombinedAssault.modLocation("naval"), 0, NavalCommand::get);
        NeoForge.EVENT_BUS.register(Trials.class);
    }
}
