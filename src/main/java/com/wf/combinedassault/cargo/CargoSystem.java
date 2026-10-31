package com.wf.combinedassault.cargo;

import com.wf.combinedassault.cargo.gametest.CargoGameTest;
import com.wf.combinedassault.cargo.gametest.CargoLoadGameTest;
import com.wf.combinedassault.cargo.gametest.VehicleStorageGameTest;
import com.wf.combinedassault.vehicle.gametest.GameTestSetup;
import java.util.Set;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import com.wf.combinedassault.vehicle.VehicleTabs;
import com.wf.combinedassault.cargo.client.VehicleStorageScreen;
import com.wf.combinedassault.cargo.message.ClientOpenParachute;
import com.wf.combinedassault.CombinedAssaultSystem;

public final class CargoSystem implements CombinedAssaultSystem {

    @Override
    public String id() {
        return "cargo";
    }

    @Override
    public Set<String> requires() {
        return Set.of("wflib:physics", "wflib:magazine");
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
        GameTestSetup.testsNeeding(modBus, java.util.Set.of("aircraft", "tow"), CargoGameTest.class, CargoLoadGameTest.class);
        GameTestSetup.tests(modBus, VehicleStorageGameTest.class);
        CargoContent.ITEMS.register(modBus);
        CargoContent.ENTITIES.register(modBus);
        CargoContent.MENUS.register(modBus);
        VehicleTabs.addMisc(CargoContent.CARGO_PARACHUTE);
        VehicleTabs.addMisc(CargoContent.PARACHUTE_PACK);
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            PayloadRegistrar registrar = event.registrar("cargo-1");
            registrar.playToServer(ClientOpenParachute.TYPE, ClientOpenParachute.STREAM_CODEC, ClientOpenParachute::handle);
        });
        NeoForge.EVENT_BUS.register(ParachutePackItem.class);
        UltimateWeightCompat.init();
        ItemMass.init();
    }

    @Override
    public void registerClient(IEventBus modBus) {
        modBus.addListener(RegisterMenuScreensEvent.class,
                event -> event.register(CargoContent.VEHICLE_STORAGE.get(), VehicleStorageScreen::new));
    }
}
