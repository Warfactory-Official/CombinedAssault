package com.wf.combinedassault.uav;

import com.wf.combinedassault.uav.fpv.client.FpvClientInput;
import com.wf.combinedassault.uav.fpv.client.FpvKeys;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/** Server-unsafe half of {@link UavSystem}. */
final class UavClient {

    private UavClient() {
    }

    static void start(IEventBus modBus) {
        modBus.register(FpvKeys.class);
        NeoForge.EVENT_BUS.register(FpvClientInput.class);
    }
}
