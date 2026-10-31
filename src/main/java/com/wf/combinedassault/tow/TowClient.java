package com.wf.combinedassault.tow;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import com.wf.combinedassault.tow.client.TowInput;
import com.wf.combinedassault.tow.client.TowKeys;

/** Server-unsafe half of {@link TowSystem}. */
final class TowClient {

    private TowClient() {
    }

    static void start(IEventBus modBus) {
        modBus.register(TowKeys.class);
        NeoForge.EVENT_BUS.register(TowInput.class);
    }
}
