package com.wf.combinedassault.tow;

import com.wf.combinedassault.tow.client.TowInput;
import com.wf.combinedassault.tow.client.TowKeys;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/** Server-unsafe half of {@link TowSystem}. */
final class TowClient {

    private TowClient() {
    }

    static void start(IEventBus modBus) {
        modBus.register(TowKeys.class);
        NeoForge.EVENT_BUS.register(TowInput.class);
    }
}
