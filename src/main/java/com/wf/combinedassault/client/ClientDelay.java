package com.wf.combinedassault.client;

import com.wf.combinedassault.CombinedAssault;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;

/** Client-thread tasks run after N unpaused client ticks; dropped with the level. */
@EventBusSubscriber(modid = CombinedAssault.MOD_ID, value = Dist.CLIENT)
public final class ClientDelay {
    private record Task(long at, Runnable run) {
    }

    private static final List<Task> TASKS = new ArrayList<>();
    private static long ticks;

    private ClientDelay() {
    }

    public static void after(int ticks, Runnable run) {
        TASKS.add(new Task(ClientDelay.ticks + ticks, run));
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            TASKS.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        ticks++;
        for (int i = 0; i < TASKS.size(); ) {
            Task t = TASKS.get(i);
            if (t.at <= ticks) {
                TASKS.remove(i);
                t.run.run();
            } else {
                i++;
            }
        }
    }
}
