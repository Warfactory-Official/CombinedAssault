package com.wf.combinedassault.uav.fpv.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

/** No keybind for firing: reuses the host's vehicle-shoot key, so rebinding shoot rebinds detonate too. */
public final class FpvKeys {

    public static final KeyMapping ARM_PAYLOAD = new KeyMapping(
            "key.combinedassault.arm_payload.desc",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_5,
            "key.category.combinedassault");

    public static final KeyMapping RADIO_STATION = new KeyMapping(
            "key.combinedassault.radio_station.desc",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.category.combinedassault");

    private FpvKeys() {
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(ARM_PAYLOAD);
        event.register(RADIO_STATION);
    }

}
