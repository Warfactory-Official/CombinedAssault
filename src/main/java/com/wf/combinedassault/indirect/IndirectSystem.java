package com.wf.combinedassault.indirect;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.CombinedAssaultSystem;
import com.wf.combinedassault.indirect.gametest.*;
import com.wf.combinedassault.indirect.net.CallForFirePacket;
import com.wf.combinedassault.indirect.net.FireRequestPacket;
import com.wf.combinedassault.vehicle.command.RootCommand;
import com.wf.combinedassault.vehicle.gametest.GameTestSetup;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class IndirectSystem implements CombinedAssaultSystem {

    @Override
    public String id() {
        return "indirect";
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
        GameTestSetup.tests(modBus, BallisticSolverGameTest.class, CorrectionGameTest.class, TrajectoryGameTest.class, MortarEmplacementGameTest.class, ReachBandGameTest.class, HimarsGameTest.class);
        RootCommand.SUBCOMMANDS.register(CombinedAssault.modLocation("indirect"), 0, IndirectFireCommand::get);
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            PayloadRegistrar registrar = event.registrar("indirect-1");
            registrar.playToServer(CallForFirePacket.TYPE, CallForFirePacket.STREAM_CODEC, CallForFirePacket::handle);
            registrar.playToClient(FireRequestPacket.TYPE, FireRequestPacket.STREAM_CODEC, FireRequestPacket::handle);
        });
    }

    @Override
    public void registerClient(IEventBus modBus) {
        IndirectClient.start(modBus);
    }
}
