package com.wf.combinedassault.labs;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.CombinedAssaultSystem;
import com.wf.combinedassault.labs.gametest.VehicleParityGameTest;
import com.wf.combinedassault.labs.gametest.VehicleStageGameTest;
import com.wf.combinedassault.vehicle.gametest.GameTestSetup;
import com.wf.wflib.labs.api.*;
import com.wf.wflib.packs.api.Pack;
import com.wf.wflib.packs.api.PackEntry;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class LabsSystem implements CombinedAssaultSystem {

    @Override
    public String id() {
        return "labs";
    }

    @Override
    public Set<String> requires() {
        return Set.of("wflib:labs", "ballistics", "vehicle", "airdefense", "energy");
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
        GameTestSetup.tests(modBus, VehicleParityGameTest.class, VehicleStageGameTest.class, com.wf.combinedassault.ballistics.spall.gametest.SpallGameTest.class);
        LabProducers.register(new VehicleProducer());
        LabProducers.register(new VehicleSummaryProducer());
        LabProducers.register(new VehicleSystemsProducer());
        LabProducers.register(new VehicleHazardsProducer());
        LabProducers.register(new CrewExposureProducer());
        LabProducers.register(new VehicleSideProducers.Launchers());
        LabProducers.register(new VehicleSideProducers.GridTable());
        LabProducers.register(new VehicleMeshProducer());
        LabProducers.register(new VehicleArmorProducer());
        ScenarioStages.register(new VehicleStage());
        LabContents.register(new Content());
        LabShelters.registerLazy(CombinedAssault.MOD_ID + ":vehicles", () -> VehicleShelter.all(
                net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer().overworld()));
        com.wf.wflib.labs.api.LabDefenders.registerLazy(CombinedAssault.MOD_ID + ":air_defense",
                () -> VehicleDefender.all(net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer().overworld()));
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, VehicleRecorder::strike);
        NeoForge.EVENT_BUS.addListener(VehicleRecorder::hit);
    }

    private static final class Content implements LabContent {
        @Override
        public String id() {
            return CombinedAssault.MOD_ID + ":vehicles";
        }

        @Override
        public Map<String, String> packs() {
            Map<String, String> out = new TreeMap<>();
            for (Pack p : CombinedAssault.packs().packs()) {
                PackEntry e = PackEntry.of(p);
                out.put(e.id(), e.sha256());
            }
            return out;
        }

        @Override
        public Set<String> configFiles() {
            return Set.of("combinedassault-vehicle.toml", "combinedassault-vehicle-server.toml", "combinedassault-dynamics.toml");
        }

        @Override
        public boolean dev(String contentId) {
            return CombinedAssault.packs().packs().stream()
                    .anyMatch(p -> p.origin() != Pack.Origin.JAR && contentId.startsWith(p.id() + ":"));
        }
    }
}
