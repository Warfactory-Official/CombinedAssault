package com.wf.combinedassault.aircraft;

import com.wf.combinedassault.aircraft.gametest.FlapsGameTest;
import com.wf.combinedassault.aircraft.gametest.AircraftPartsGameTest;
import com.wf.combinedassault.vehicle.gametest.GameTestSetup;
import com.wf.combinedassault.aircraft.part.AircraftPartTypes;
import com.wf.combinedassault.pack.BuiltinPartUnits;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import com.wf.combinedassault.aircraft.client.FixedWingVehicleDisplay;
import com.wf.combinedassault.aircraft.client.RotaryWingVehicleDisplay;
import com.wf.combinedassault.aircraft.data.FixedWingVehicleData;
import com.wf.combinedassault.aircraft.data.FixedWingVehicleDataPojo;
import com.wf.combinedassault.aircraft.data.RotaryWingVehicleData;
import com.wf.combinedassault.aircraft.data.RotaryWingVehicleDataPojo;
import com.wf.combinedassault.pack.client.DisplayTypes;
import com.wf.combinedassault.pack.VehicleDataTypes;
import com.wf.combinedassault.pack.client.vehicle.VehicleDisplayPojo;
import com.wf.combinedassault.pack.serialize.GsonUtil;
import com.wf.combinedassault.render.animation.context.AnimationContextFactory;
import com.wf.combinedassault.CombinedAssaultSystem;

public final class AircraftSystem implements CombinedAssaultSystem {

    @Override
    public String id() {
        return "aircraft";
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
        GameTestSetup.testsNeeding(modBus, java.util.Set.of("uav"), FlapsGameTest.class);
        GameTestSetup.tests(modBus, AircraftPartsGameTest.class);
        BuiltinPartUnits.add(AircraftPartTypes.THRUST);
        BuiltinPartUnits.add(AircraftPartTypes.VARIABLE_SWEEP);
        BuiltinPartUnits.add(AircraftPartTypes.FLAPS);
        BuiltinPartUnits.add(AircraftPartTypes.AIRBRAKE);
        AircraftContent.ENTITIES.register(modBus);
        container.registerConfig(ModConfig.Type.COMMON, AircraftConfig.SPEC, "combinedassault-aircraft.toml");
        modBus.addListener(ModConfigEvent.Loading.class, AircraftConfig::onLoad);
        modBus.addListener(ModConfigEvent.Reloading.class, AircraftConfig::onLoad);
        VehicleDataTypes.register("rotary_wing_vehicle", json -> {
            RotaryWingVehicleData data = new RotaryWingVehicleData();
            data.build(GsonUtil.GSON.fromJson(json, RotaryWingVehicleDataPojo.class));
            return data;
        });
        VehicleDataTypes.register("fixed_wing_vehicle", json -> {
            FixedWingVehicleData data = new FixedWingVehicleData();
            data.build(GsonUtil.GSON.fromJson(json, FixedWingVehicleDataPojo.class));
            return data;
        });
        DisplayTypes.register("rotary_wing_vehicle",
                json -> new RotaryWingVehicleDisplay(GsonUtil.GSON.fromJson(json, VehicleDisplayPojo.class)),
                AnimationContextFactory.rotaryWingVehicle());
        DisplayTypes.register("fixed_wing_vehicle",
                json -> new FixedWingVehicleDisplay(GsonUtil.GSON.fromJson(json, VehicleDisplayPojo.class)),
                AnimationContextFactory.fixedWingVehicle());
    }
}
