package com.wf.combinedassault.vehicle.gametest;

import net.neoforged.bus.api.IEventBus;
import com.wf.combinedassault.CombinedAssault;
import com.wf.wflib.core.api.WFSystems;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** A system registers its own gametest classes, so an off system runs none; {@code testsNeeding}: only when the named systems are on too. */
public final class GameTestSetup {

    private GameTestSetup() {
    }

    public static void tests(IEventBus modBus, Class<?>... classes) {
        modBus.addListener(RegisterGameTestsEvent.class, event -> {
            for (Class<?> c : classes) {
                event.register(c);
            }
        });
    }

    public static void testsNeeding(IEventBus modBus, java.util.Set<String> systems, Class<?>... classes) {
        modBus.addListener(RegisterGameTestsEvent.class, event -> {
            if (systems.stream().allMatch(s -> WFSystems.enabled(CombinedAssault.MOD_ID + ":" + s))) {
                for (Class<?> c : classes) {
                    event.register(c);
                }
            }
        });
    }
}
