package com.wf.combinedassault.indirect.client;

import com.wf.combinedassault.indirect.net.FireRequestPacket;
import com.wf.wflib.pda.api.PdaAlert;
import com.wf.wflib.pda.api.client.PdaAlerts;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Observers' fire requests received at this map station; shown and accepted on the map ({@link FireMissionMapTool}). */
public final class FireRequests {
    private static final long LIFE_MILLIS = 120_000L;
    private static final int CAP = 8;
    private static final List<Request> PENDING = new ArrayList<>();

    public record Request(String observer, Vec3 target, long at) {
    }

    private FireRequests() {
    }

    public static void accept(FireRequestPacket message) {
        if (PENDING.size() >= CAP) {
            PENDING.removeFirst();
        }
        PENDING.add(new Request(message.observer(), message.target(), Util.getMillis()));
        Component text = Component.translatable("tips.call_for_fire.received", message.observer(),
                (int) Math.floor(message.target().x), (int) Math.floor(message.target().z));
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(text, true);
        }
        PdaAlerts.post(text, PdaAlert.Tier.WARNING);
    }

    /** Unexpired, oldest first. */
    public static List<Request> pending() {
        long now = Util.getMillis();
        PENDING.removeIf(r -> now - r.at() > LIFE_MILLIS);
        return PENDING;
    }

    public static void dismiss(Request request) {
        PENDING.remove(request);
    }
}
