package com.wf.combinedassault.ballistics;

import com.wf.combinedassault.ballistics.addon.client.AddonArmorRenderer;
import net.neoforged.neoforge.common.NeoForge;

/** Server-unsafe half of {@link BallisticsSystem}. */
final class BallisticsClient {

    private BallisticsClient() {
    }

    static void start() {
        NeoForge.EVENT_BUS.register(AddonArmorRenderer.class);
    }
}
