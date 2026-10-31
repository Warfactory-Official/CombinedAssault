package com.wf.combinedassault.airdefense;

import com.wf.combinedassault.airdefense.gametest.AirDefenseGameTest;
import com.wf.combinedassault.airdefense.gametest.LaserGameTest;
import com.wf.combinedassault.vehicle.gametest.GameTestSetup;
import java.util.Set;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import com.wf.combinedassault.CombinedAssaultSystem;

public final class AirDefenseSystem implements CombinedAssaultSystem {

    @Override
    public String id() {
        return "airdefense";
    }

    @Override
    public Set<String> requires() {
        return Set.of("radar", "wflib:probe");
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
        GameTestSetup.testsNeeding(modBus, java.util.Set.of("aircraft", "uav"), AirDefenseGameTest.class, LaserGameTest.class);
        AirDefenseActions.register();
    }
}
