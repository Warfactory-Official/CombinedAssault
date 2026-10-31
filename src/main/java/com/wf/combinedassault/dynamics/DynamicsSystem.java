package com.wf.combinedassault.dynamics;

import com.wf.combinedassault.dynamics.gametest.StepDeterminismGameTest;
import com.wf.combinedassault.vehicle.gametest.GameTestSetup;
import java.util.Set;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.dynamics.gametest.CollisionProviderGameTest;
import com.wf.combinedassault.vehicle.command.RootCommand;
import com.wf.combinedassault.CombinedAssaultSystem;

public final class DynamicsSystem implements CombinedAssaultSystem {

    @Override
    public String id() {
        return "dynamics";
    }

    @Override
    public boolean mandatory() {
        return true;
    }

    @Override
    public Set<String> requires() {
        return Set.of("wflib:physics");
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
        GameTestSetup.testsNeeding(modBus, java.util.Set.of("naval"), StepDeterminismGameTest.class);
        container.registerConfig(ModConfig.Type.COMMON, DynamicsConfig.SPEC, "combinedassault-dynamics.toml");
        modBus.addListener(ModConfigEvent.Loading.class, DynamicsConfig::onLoad);
        modBus.addListener(ModConfigEvent.Reloading.class, DynamicsConfig::onLoad);
        RootCommand.SUBCOMMANDS.register(CombinedAssault.modLocation("physicstrace"), 0, PhysicsTraceCommand::get);
        CollisionProviderGameTest.install();
    }
}
