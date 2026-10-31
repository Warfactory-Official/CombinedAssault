package com.wf.combinedassault.render;

import java.util.Set;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import com.wf.combinedassault.CombinedAssaultSystem;

/** Client-only: models, renderers, particles, shaders, hit/wake fx, GemRender bridge. */
public final class RenderSystem implements CombinedAssaultSystem {

    @Override
    public String id() {
        return "render";
    }

    @Override
    public boolean mandatory() {
        return true;
    }

    @Override
    public Set<String> requires() {
        return Set.of("wflib:physics", "wflib:armor");
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
        RenderParticles.register(modBus);
    }

    @Override
    public void registerClient(IEventBus modBus) {
        RenderClient.start(modBus);
    }
}
