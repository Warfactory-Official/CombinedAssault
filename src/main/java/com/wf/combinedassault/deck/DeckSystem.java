package com.wf.combinedassault.deck;

import com.wf.combinedassault.deck.gametest.DeckParentingGameTest;
import com.wf.combinedassault.deck.rail.gametest.FlatcarCarryGameTest;
import com.wf.combinedassault.deck.gametest.NonVehicleCarrierGameTest;
import com.wf.combinedassault.deck.gametest.VehicleMountGameTest;
import com.wf.combinedassault.vehicle.gametest.GameTestSetup;
import java.util.Set;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import com.wf.combinedassault.deck.rail.RailCompat;
import com.wf.combinedassault.CombinedAssaultSystem;

public final class DeckSystem implements CombinedAssaultSystem {

    @Override
    public String id() {
        return "deck";
    }

    @Override
    public Set<String> requires() {
        return Set.of("wflib:physics");
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
        GameTestSetup.testsNeeding(modBus, java.util.Set.of("naval"), DeckParentingGameTest.class);
        GameTestSetup.tests(modBus, FlatcarCarryGameTest.class, NonVehicleCarrierGameTest.class);
        GameTestSetup.testsNeeding(modBus, java.util.Set.of("energy"), VehicleMountGameTest.class);
        RailCompat.init();
    }
}
