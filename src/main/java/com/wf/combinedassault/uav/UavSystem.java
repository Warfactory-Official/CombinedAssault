package com.wf.combinedassault.uav;

import com.wf.combinedassault.uav.autonomy.gametest.AutopilotGameTest;
import com.wf.combinedassault.uav.autonomy.gametest.AutopilotClassesGameTest;
import com.wf.combinedassault.uav.fpv.gametest.DroneRideGameTest;
import com.wf.combinedassault.uav.fpv.gametest.FpvFlightGameTest;
import com.wf.combinedassault.uav.multirotor.gametest.AmazogGameTest;
import com.wf.combinedassault.uav.multirotor.gametest.RotorDamageGameTest;
import com.wf.combinedassault.uav.multirotor.gametest.MultirotorGameTest;
import com.wf.combinedassault.uav.gametest.CounterplayGameTest;
import com.wf.combinedassault.uav.gametest.DatalinkGameTest;
import com.wf.combinedassault.uav.gametest.PdaUavGameTest;
import com.wf.combinedassault.vehicle.gametest.GameTestSetup;
import com.wf.combinedassault.uav.fpv.payload.FpvPayloadUnitType;
import com.wf.combinedassault.pack.BuiltinPartUnits;
import java.util.Set;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import com.wf.combinedassault.uav.fpv.net.FpvTriggerMessage;
import com.wf.combinedassault.uav.fpv.payload.FpvPayloadTypes;
import com.wf.combinedassault.CombinedAssaultSystem;

public final class UavSystem implements CombinedAssaultSystem {

    @Override
    public String id() {
        return "uav";
    }

    @Override
    public Set<String> requires() {
        return Set.of("aircraft", "wflib:drone");
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
        GameTestSetup.tests(modBus, AutopilotGameTest.class, AutopilotClassesGameTest.class, DroneRideGameTest.class, FpvFlightGameTest.class, AmazogGameTest.class, RotorDamageGameTest.class, MultirotorGameTest.class, CounterplayGameTest.class, DatalinkGameTest.class, PdaUavGameTest.class);
        BuiltinPartUnits.add(FpvPayloadUnitType.PAYLOAD);
        FpvPayloadTypes.registerTypes();
        UavCamera.register();
        UavTablet.register();
        NeoForge.EVENT_BUS.register(UavEvents.class);
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            PayloadRegistrar registrar = event.registrar("uav-1");
            registrar.playToServer(FpvTriggerMessage.TYPE, FpvTriggerMessage.STREAM_CODEC, FpvTriggerMessage::handle);
        });
    }

    @Override
    public void registerClient(IEventBus modBus) {
        UavClient.start(modBus);
    }
}
