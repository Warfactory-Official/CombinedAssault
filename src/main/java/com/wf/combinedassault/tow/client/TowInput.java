package com.wf.combinedassault.tow.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.tow.net.TowCoupleMessage;

/** Client sends only its own vehicle id; hitch/trailer/mode choice is server-side (coupling moves a vehicle the player isn't riding - not client-trustable). */
@EventBusSubscriber(value = Dist.CLIENT)
public final class TowInput {

    private TowInput() {
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isSpectator() || mc.screen != null) {
            return;
        }
        if (!TowKeys.TOGGLE_COUPLING.matches(event.getKey(), event.getScanCode())) {
            return;
        }
        if (player.getVehicle() instanceof AbstractVehicle tractor && !tractor.towing.hitches.isEmpty()) {
            PacketDistributor.sendToServer(new TowCoupleMessage(tractor.getId()));
        }
    }

}
