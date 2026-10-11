package com.wf.combinedassault.vehicle.parenting.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.parenting.TickClock;

@EventBusSubscriber(modid = CombinedAssault.MOD_ID, value = Dist.CLIENT)
public final class TickClockClient {

    private TickClockClient() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Pre event) {
        TickClock.advanceClient();
    }
}
