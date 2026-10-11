package com.wf.combinedassault.fpv.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import com.wf.combinedassault.all.AllKeys;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.fpv.net.FpvTriggerMessage;
import com.wf.combinedassault.fpv.payload.PayloadUnit;
import com.wf.combinedassault.network.message.ClientVehicleAction;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;

/** Arm rides the host's vehicle-action packet (hardpoint is a switchable unit); fire needed one of its own. */
@EventBusSubscriber(value = Dist.CLIENT)
public final class FpvClientInput {

    private FpvClientInput() {
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        handle(event.getKey(), event.getScanCode(), event.getAction());
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        handle(event.getButton(), 0, event.getAction());
    }

    private static void handle(int key, int scanCode, int action) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (action != GLFW.GLFW_PRESS || player == null || player.isSpectator() || mc.screen != null) {
            return;
        }
        if (!LocalVehiclePlayer.instance.onVehicle()) {
            return;
        }
        AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
        PayloadUnit hardpoint = PayloadUnit.on(vehicle);
        if (hardpoint == null || !hardpoint.isLoaded()) {
            return;
        }
        if (matches(FpvKeys.ARM_PAYLOAD, key, scanCode)) {
            ClientVehicleAction arm = new ClientVehicleAction();
            arm.vehicleEntityId = vehicle.getId();
            arm.togglePartUnitState = true;
            arm.partUnitIndex = hardpoint.getIndex();
            PacketDistributor.sendToServer(arm);
        } else if (hardpoint.isArmed() && matches(AllKeys.MAIN_WEAPON_SHOOT, key, scanCode)) {
            PacketDistributor.sendToServer(new FpvTriggerMessage(vehicle.getId()));
        }
    }

    private static boolean matches(KeyMapping mapping, int key, int scanCode) {
        return mapping.matches(key, scanCode) || mapping.matchesMouse(key);
    }

}
