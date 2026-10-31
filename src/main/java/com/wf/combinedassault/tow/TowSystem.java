package com.wf.combinedassault.tow;

import com.wf.combinedassault.tow.gametest.TowGameTest;
import com.wf.combinedassault.vehicle.gametest.GameTestSetup;
import java.util.Set;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.command.RootCommand;
import com.wf.combinedassault.tow.net.TowCoupleMessage;
import com.wf.combinedassault.tow.trial.TowTrials;
import com.wf.wflib.core.api.ServerTicks;
import com.wf.wflib.core.api.TickOrder;
import com.wf.combinedassault.CombinedAssaultSystem;

public final class TowSystem implements CombinedAssaultSystem {

    @Override
    public String id() {
        return "tow";
    }

    @Override
    public Set<String> requires() {
        return Set.of("wflib:physics");
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
        GameTestSetup.tests(modBus, TowGameTest.class);
        RootCommand.SUBCOMMANDS.register(CombinedAssault.modLocation("tow"), 0, TowCommand::get);
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            PayloadRegistrar registrar = event.registrar("tow-1");
            registrar.playToServer(TowCoupleMessage.TYPE, TowCoupleMessage.STREAM_CODEC, TowCoupleMessage::handle);
        });
        ServerTicks.LEVEL_POST.register(CombinedAssault.modLocation("tow"), TickOrder.COUPLING, TowSolver::tick);
        NeoForge.EVENT_BUS.register(TowEvents.class);
        NeoForge.EVENT_BUS.register(TowTrials.class);
    }

    @Override
    public void registerClient(IEventBus modBus) {
        TowClient.start(modBus);
    }
}
